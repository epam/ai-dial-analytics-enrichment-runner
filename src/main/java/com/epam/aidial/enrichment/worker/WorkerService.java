package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.adas.AdasWorkGate;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.TransformDto;
import com.epam.aidial.enrichment.configuration.WorkerProperties;
import com.epam.aidial.enrichment.dlq.DlqCircuitBreaker;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.OutputMappingException;
import com.epam.aidial.enrichment.exception.ResponseValidationException;
import com.epam.aidial.enrichment.exception.TransientChunkException;
import com.epam.aidial.enrichment.exception.UnresolvedPlaceholderException;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.spend.SpendService;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.upsert.UpsertEnqueuer;
import com.epam.aidial.enrichment.worker.upsert.UpsertPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Processes one enrich-chunk work item: for each row, render the transform's template, call DIAL Core, validate
 * + repair the response, and map outputs to columns with provenance. Rows are processed with bounded
 * parallelism ({@code worker.row-parallelism}) under the shared per-pipeline rate limiter.
 *
 * <p>This is the <strong>compute</strong> phase only. It ends by enqueueing an {@code upsert-rows} item
 * carrying the computed rows, so a failed write retries the write alone — under the previous single-phase
 * worker, an ADAS 5xx re-ran the model calls that produced the rows it already had.
 *
 * <p>Nothing here runs while ADAS is incompatible with this build's contract: {@link AdasWorkGate} refuses
 * the item at the top, so a chunk enqueued before a version skew is held rather than derived — the whole
 * point of gating before the model call rather than at the write.
 *
 * <p>Failure isolation follows the taxonomy: transient/infrastructure errors (DIAL 429/5xx) propagate so the
 * whole chunk retries; permanent per-row errors (non-retryable 4xx,
 * schema-invalid output after repair, output-mapping failure, a template placeholder nothing bound) are
 * isolated to the offending row via the DLQ so one bad row never blocks its chunk. Stale-declaration and
 * pipeline-lifecycle guards drop a chunk whose pipeline was re-declared or disabled mid-flight — and because
 * that guard is keyed on the pipeline's {@code generation}, it now fires for an edit to an execution knob
 * alone, where an evaluator version would have kept the chunk.
 *
 * <p>A chunk enqueued by an image that <em>predates</em> the evaluator fold is the one case that is not a
 * stale declaration and must not be treated as one: it {@linkplain PreFoldWorkItem adopts} the live
 * generation and runs, because this lane's drop is irreversible — the scan cursor advanced in the
 * transaction that enqueued the chunk — and the item has no producer left. See that class for why the
 * chunk lane normalises rather than skipping the stale check.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogExecution
public class WorkerService {

    private final PipelineCache pipelineCache;
    private final TransformInvoker transformInvoker;
    private final UpsertEnqueuer upsertEnqueuer;
    private final DlqCircuitBreaker dlqCircuitBreaker;
    private final SpendService spendService;
    private final RunnerMetrics runnerMetrics;
    private final WorkerProperties workerProperties;
    private final AdasWorkGate adasWorkGate;

    public void process(String chunkId, ChunkPayload payload) {
        try (WorkerMdc.Scope ignored = WorkerMdc.scope()
                .put(WorkerMdc.PIPELINE_NAME, payload.getPipelineName())
                .put(WorkerMdc.CHUNK_ID, chunkId)
                .put(WorkerMdc.PIPELINE_GENERATION, Long.toString(payload.getPipelineGeneration()))) {
            // Before the log line, and before anything is derived: an item that became due during a skew
            // makes no DIAL call at all, and is held by ChunkFailureHandler rather than failed.
            adasWorkGate.requireCompatible("chunk " + chunkId);
            log.info("Processing chunk {} for pipeline {} ({} rows)",
                    chunkId, payload.getPipelineName(), payload.getRows().size());
            doProcess(chunkId, payload);
        }
    }

    private void doProcess(String chunkId, ChunkPayload payload) {
        // Asked before the lookup, because a cache that has never synced cannot answer it. The drop below is
        // irreversible on this lane — ScanPageCommitter advanced the cursor in the same transaction that
        // enqueued this chunk, so nothing will ever read its rows again — and a restarted process picks up
        // due work seconds before its first registry poll lands.
        pipelineCache.requirePopulated("chunk " + chunkId);
        PipelineDto pipeline = pipelineCache.get(payload.getPipelineName());
        if (pipeline == null) {
            log.info("Dropping chunk for pipeline {} (absent from cache)", payload.getPipelineName());
            return;
        }
        adoptIfPreFold(pipeline, payload);
        if (isStale(pipeline, payload)) {
            log.info("Dropping stale chunk for pipeline {} (built against generation {})",
                    payload.getPipelineName(), payload.getPipelineGeneration());
            return;
        }

        TransformDto transform = pipeline.transform();
        // A transient failure on any row aborts the whole chunk (propagates out of mapRows); permanent
        // per-row errors come back as DLQ outcomes so siblings still proceed.
        List<RowOutcome> outcomes = mapRows(pipeline, transform, payload);

        // Re-check before any persistence: a disable/re-pin mid-flight drops the chunk without writing
        // DLQ rows or tripping the breaker on its way out.
        PipelineDto current = pipelineCache.get(payload.getPipelineName());
        if (current == null || isStale(current, payload)) {
            log.info("Dropping chunk for pipeline {}: pipeline changed mid-flight", payload.getPipelineName());
            return;
        }

        List<Map<String, Object>> writeRows = new ArrayList<>();
        List<DlqItemEntity> dlqItems = new ArrayList<>();
        // A backfill chunk's id carries its run; every stage's dead letter names it. Null for the live path.
        String runId = DeterministicId.backfillRunIdOf(chunkId);
        for (RowOutcome outcome : outcomes) {
            if (outcome.writeRow() != null) {
                writeRows.add(outcome.writeRow());
            } else {
                DlqItemEntity dead = outcome.dlqItem();
                dead.setRunId(runId);
                dlqItems.add(dead);
            }
        }

        // Compute ends here. The write is a separate item, so retrying a failed write never re-calls the
        // model; the enqueue and the per-row dead-letters commit together so a computed result cannot be
        // dropped between the phases.
        UpsertPayload write = writeRows.isEmpty() ? null
                : new UpsertPayload(payload.getPipelineName(), payload.getPipelineGeneration(), writeRows);
        upsertEnqueuer.commit(chunkId, write, dlqItems);
        if (!dlqItems.isEmpty()) {
            dlqCircuitBreaker.record(payload.getPipelineName(), dlqItems.size(), payload.getRows().size());
            // Logged whether or not any row survived: a chunk that dead-letters every row has no write line, and
            // without this one it would leave no trace at INFO or above.
            log.warn("Chunk {} for pipeline {} dead-lettered {} of {} row(s) at stage(s) {}", chunkId,
                    payload.getPipelineName(), dlqItems.size(), payload.getRows().size(), stages(dlqItems));
        }
        if (write != null) {
            log.info("Computed {} row(s) for pipeline {}; queued for writing", writeRows.size(), payload.getPipelineName());
        }
    }

    /** The distinct DLQ stages of a chunk's dead letters, in a stable order. Stage names only, never an error text. */
    private static String stages(List<DlqItemEntity> dlqItems) {
        return String.join(",", new TreeSet<>(dlqItems.stream().map(DlqItemEntity::getStage).toList()));
    }

    /**
     * Maps every row in the chunk, honoring {@code worker.row-parallelism}. Results are returned in
     * input order for determinism; a {@link TransientChunkException} from any row aborts the whole
     * chunk so it is retried rather than partially written.
     */
    private List<RowOutcome> mapRows(PipelineDto pipeline, TransformDto transform, ChunkPayload payload) {
        List<ChunkRow> rows = payload.getRows();
        int parallelism = Math.clamp(workerProperties.rowParallelism(), 1, rows.size());
        if (parallelism <= 1) {
            List<RowOutcome> outcomes = new ArrayList<>(rows.size());
            for (ChunkRow row : rows) {
                outcomes.add(processRowWithMdc(pipeline, transform, payload, row, null));
            }
            return outcomes;
        }

        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<RowOutcome>> futures = new ArrayList<>(rows.size());
            for (ChunkRow row : rows) {
                futures.add(pool.submit(() -> processRowWithMdc(pipeline, transform, payload, row, parentMdc)));
            }
            return collect(futures);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Joins row futures in order, re-raising a transient failure so the whole chunk retries. */
    private static List<RowOutcome> collect(List<Future<RowOutcome>> futures) {
        List<RowOutcome> outcomes = new ArrayList<>(futures.size());
        TransientChunkException transientFailure = null;
        RuntimeException otherFailure = null;
        for (Future<RowOutcome> future : futures) {
            try {
                outcomes.add(future.get());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof TransientChunkException transientCause) {
                    if (transientFailure == null) {
                        transientFailure = transientCause;
                    }
                } else if (otherFailure == null) {
                    otherFailure = cause instanceof RuntimeException runtime
                            ? runtime : new IllegalStateException(cause);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransientChunkException(DlqStage.DIAL_CALL, e);
            }
        }
        if (transientFailure != null) {
            throw transientFailure;
        }
        if (otherFailure != null) {
            throw otherFailure;
        }
        return outcomes;
    }

    /**
     * Runs {@link #processRow} with {@code grain_key} pinned in the MDC (and the chunk-scope MDC
     * re-established when running on a worker thread), restoring the prior context afterwards.
     */
    private RowOutcome processRowWithMdc(PipelineDto pipeline, TransformDto transform, ChunkPayload payload,
                                         ChunkRow row, Map<String, String> parentMdc) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (parentMdc != null) {
            MDC.setContextMap(parentMdc);
        }
        MDC.put(WorkerMdc.GRAIN_KEY, row.getGrainKey());
        try {
            return processRow(pipeline, transform, payload, row);
        } finally {
            if (previous != null) {
                MDC.setContextMap(previous);
            } else {
                MDC.clear();
            }
        }
    }

    /**
     * Enriches one row, translating failures into either a chunk-abort (transient) or a per-row DLQ
     * outcome (permanent). Never returns {@code null}.
     */
    private RowOutcome processRow(PipelineDto pipeline, TransformDto transform, ChunkPayload payload,
                                  ChunkRow row) {
        try {
            return RowOutcome.write(enrichRow(pipeline, transform, row));
        } catch (RestClientResponseException e) {
            if (RetryableStatus.isRetryable(e)) {
                throw new TransientChunkException(DlqStage.DIAL_CALL, e); // let the whole chunk retry
            }
            spendService.record(payload.getPipelineName(), payload.getPipelineGeneration(), 0, true);
            return RowOutcome.dead(dlqItem(payload, row.getGrainKey(), DlqStage.DIAL_CALL, e.getMessage(),
                    e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            // Connection/IO failure talking to DIAL (no HTTP status): transient, retry the whole chunk.
            throw new TransientChunkException(DlqStage.DIAL_CALL, e);
        } catch (ResponseValidationException e) {
            runnerMetrics.incrementValidationFailures();
            return RowOutcome.dead(dlqItem(payload, row.getGrainKey(), DlqStage.VALIDATE, e.getMessage(),
                    e.getRawResponse()));
        } catch (OutputMappingException e) {
            return RowOutcome.dead(dlqItem(payload, row.getGrainKey(), DlqStage.OUTPUT_MAP, e.getMessage(), null));
        } catch (UnresolvedPlaceholderException e) {
            // Raised before the request is even assembled, so no DIAL call was made and no spend recorded.
            // Dead-lettered at `input_map` rather than `output_map`: a placeholder nothing bound is a fault
            // in what the transform reads, and an operator reading the DLQ needs the stage to point at the
            // half of the declaration that is wrong. A broken declaration fails every row, which the DLQ
            // makes obvious immediately — one malformed row must not stop a pipeline succeeding on the rest.
            log.warn("Dead-lettering row {} for pipeline {}: {}", row.getGrainKey(), payload.getPipelineName(),
                    e.getMessage());
            return RowOutcome.dead(dlqItem(payload, row.getGrainKey(), DlqStage.INPUT_MAP, e.getMessage(), null));
        } catch (TransientChunkException e) {
            throw e; // rate-limit exhaustion / already-classified transient: back the whole chunk off
        } catch (RuntimeException e) {
            // Any other permanent per-row error (template render, request build, output parse) is
            // isolated to the DLQ so one bad row never fails its whole chunk.
            log.warn("Dead-lettering row {} for pipeline {}: {}", row.getGrainKey(), payload.getPipelineName(),
                    e.toString());
            return RowOutcome.dead(dlqItem(payload, row.getGrainKey(), DlqStage.OUTPUT_MAP, e.getMessage(), null));
        }
    }

    private Map<String, Object> enrichRow(PipelineDto pipeline, TransformDto transform, ChunkRow row) {
        Map<String, Object> columns = transformInvoker.derive(pipeline, transform, row.getInputs());

        Map<String, Object> writeRow = new LinkedHashMap<>();
        writeRow.put(pipeline.grainKey(), row.getGrainKey());
        writeRow.putAll(columns);
        writeRow.put(ProvenanceColumns.PIPELINE_GENERATION, pipeline.generation());
        writeRow.put(ProvenanceColumns.MODEL, transform.model());
        writeRow.put(ProvenanceColumns.ENRICHED_AT, Instant.now().toString());
        if (row.getPartitionValue() != null) {
            // Echoed unchanged for an aligned target, which refuses a row without it; never sent as null, which an
            // unaligned target would refuse as a system column.
            writeRow.put(ChunkRow.PARTITION_ECHO_MEMBER, row.getPartitionValue());
        }
        return writeRow;
    }

    /**
     * Normalises a chunk enqueued before ADAS's evaluator fold onto the live declaration, instead of
     * letting the stale guard below drop it.
     *
     * <p><strong>Placed here, between the cache lookup and the stale guard, and neither earlier nor
     * later.</strong> Later is too late — the stale guard is what would drop it. Earlier is impossible:
     * there is no live generation to adopt until the pipeline has been resolved, and a pre-fold chunk
     * whose pipeline is absent from the cache takes the same drop any other chunk does, because a chunk
     * is re-derivable in principle and the pipeline is gone.
     *
     * <p><strong>Why it assigns rather than just skipping the staleness comparison.</strong> The payload's
     * generation is read four more times after this point, and a 0 left in place is a distinct defect in
     * each: {@link #doProcess} builds the derived {@link UpsertPayload} from it, so the write lane would
     * see a pre-fold payload and strip provenance from a result this lane just paid for;
     * {@link #dlqItem} stamps it into {@code dlq_item_row_identity_uk}, whose rows
     * {@code DlqRequeueService} then refuses to requeue permanently; {@link #processRow} keys failure
     * spend on it, which the daily gate sums across generations but per-generation reporting does not;
     * and it is the {@code pipeline.generation} MDC field on every line the rest of this execution logs.
     * Refreshing the MDC here is part of the same normalisation.
     */
    private void adoptIfPreFold(PipelineDto pipeline, ChunkPayload payload) {
        if (!PreFoldWorkItem.isPreFold(payload.getPipelineGeneration())) {
            return;
        }
        payload.setPipelineGeneration(pipeline.generation());
        MDC.put(WorkerMdc.PIPELINE_GENERATION, Long.toString(pipeline.generation()));
        PreFoldWorkItem.reportAdopted(PreFoldWorkItem.Lane.CHUNK, payload.getPipelineName(),
                pipeline.generation(), runnerMetrics);
    }

    private static boolean isStale(PipelineDto pipeline, ChunkPayload payload) {
        return pipeline.generation() != payload.getPipelineGeneration();
    }

    private static DlqItemEntity dlqItem(ChunkPayload payload, String grainKey, DlqStage stage,
                                         String error, String rawResponse) {
        return DlqItemEntity.builder()
                .pipelineName(payload.getPipelineName())
                .grainKey(grainKey)
                .pipelineGeneration(payload.getPipelineGeneration())
                .stage(stage.dbValue())
                .error(error)
                .rawResponse(rawResponse)
                .build();
    }
}
