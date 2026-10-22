package com.epam.aidial.enrichment.worker.upsert;

import com.epam.aidial.enrichment.adas.AdasUpsertClient;
import com.epam.aidial.enrichment.adas.AdasWorkGate;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.dlq.DlqCircuitBreaker;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.exception.TransientChunkException;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.RunnerTracer;
import com.epam.aidial.enrichment.observability.status.PipelineActivity;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.persistence.GroupStateWriter;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.PreFoldWorkItem;
import com.epam.aidial.enrichment.worker.ProvenanceColumns;
import com.epam.aidial.enrichment.worker.RetryableStatus;
import com.epam.aidial.enrichment.worker.WorkerMdc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a computed result — the second phase of enrichment, in its own work item.
 *
 * <p>Its whole reason for existing is what it does <em>not</em> do: it never calls the model. The rows arrive on
 * the payload, so a transient ADAS failure retries an HTTP call rather than re-deriving labels the runner
 * already had. Under the previous single-phase worker a flaky write cost one model call per attempt.
 *
 * <p>The write is idempotent by construction — fixed rows keyed by grain, collapsing on the target's
 * {@code ReplacingMergeTree} — which is what makes retrying it safe without any coordination.
 *
 * <p>It is not attempted at all while ADAS is incompatible with this build's contract: {@link AdasWorkGate}
 * refuses the item at the top, and {@code UpsertFailureHandler} holds it. Archiving a pending write during a
 * version skew is the one outcome to avoid here — the payload <em>is</em> the paid-for result.
 *
 * <p>For the same reason a write enqueued by an image that <em>predates</em> ADAS's evaluator fold is
 * {@linkplain PreFoldWorkItem adopted onto the live generation} and written, rather than dropped by the
 * stale-declaration guard: the payload <em>is</em> the paid-for result, and its only copy.
 *
 * <p>For a group write, the successful write is also what stamps the group clean. That ordering matters: stamping
 * at compute time would mark a group clean before its row landed, so a write that then failed permanently would
 * leave a group the sweep never revisits and an enrichment table with no row for it. "Clean" means "written" —
 * with one exemption, a write ADAS <em>permanently rejects</em>, where staying dirty would re-evaluate the group
 * against the model on every sweep to reach the same rejection (see {@link #deadLetter}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogExecution
public class UpsertService {

    private final PipelineCache pipelineCache;
    private final AdasUpsertClient upsertClient;
    private final GroupStateWriter groupStateWriter;
    private final DlqItemWriter dlqItemWriter;
    private final JsonMapper jsonMapper;
    private final DlqCircuitBreaker dlqCircuitBreaker;
    private final RunnerMetrics runnerMetrics;
    private final RunnerTracer runnerTracer;
    private final AdasWorkGate adasWorkGate;
    private final Clock clock;
    private final PipelineActivity pipelineActivity;

    public void write(String itemId, UpsertPayload payload) {
        WorkerMdc.Scope mdc = WorkerMdc.scope()
                .put(WorkerMdc.PIPELINE_NAME, payload.getPipelineName())
                .put(WorkerMdc.CHUNK_ID, itemId)
                .put(WorkerMdc.PIPELINE_GENERATION, Long.toString(payload.getPipelineGeneration()));
        if (payload.isGroupWrite()) {
            mdc.put(WorkerMdc.GROUP_KEY, payload.getGroupKey());
        }
        try (mdc) {
            // Held, not attempted. A mismatched ADAS refuses this write permanently as far as the classifier
            // can tell, so the ordinary retry-then-archive path would consume the attempts and archive a
            // computed result for a reason that is about to stop being true.
            adasWorkGate.requireCompatible("write-back for pipeline " + payload.getPipelineName());
            doWrite(itemId, payload);
        }
    }

    private void doWrite(String itemId, UpsertPayload payload) {
        if (payload.getRows() == null || payload.getRows().isEmpty()) {
            log.debug("Upsert item for pipeline {} carries no rows; nothing to write", payload.getPipelineName());
            return;
        }

        // Asked before the lookup: this lane's response to an absent pipeline is to archive a result the
        // model was already paid for, which is right for a pipeline that has genuinely gone and wrong for a
        // process that has not finished starting. Held instead, so a restart does not hand an operator a DLQ
        // full of writes to requeue by hand.
        pipelineCache.requirePopulated("write-back for pipeline " + payload.getPipelineName());

        // Re-checked here, not only at compute time: a pipeline re-pinned between the phases must not have a row
        // written that is provenance-stamped with the superseded pipeline generation.
        PipelineDto pipeline = pipelineCache.get(payload.getPipelineName());
        if (pipeline == null) {
            // Absent from the cache means disabled OR deleted: ADAS's feed omits both, so the runner cannot
            // tell a pause from a deletion. The pre-compute lanes drop such an item and are right to — a scan
            // re-runs, a chunk or a group item is re-derived, and nothing has been spent. THIS lane is the one
            // holding a result the model was already paid for, whose only copy is the payload in hand, so
            // dropping it destroys work instead of deferring it. Archived with its rows instead, so a requeue
            // re-attempts the write once the pipeline is back and recomputes nothing.
            //
            // Neither stamped nor counted against the breaker, unlike a rejection ADAS keeps refusing: the
            // write never reached ADAS, so nothing about the group's evaluation state changed, and pausing a
            // pipeline that is already absent would leave a pause entry to outlive its return and hold it back
            // silently.
            //
            // One narrow case is NOT requeueable, and it is the only pre-fold payload that still reaches the
            // DLQ: a write enqueued before the evaluator fold, whose pipeline is absent here. It is archived
            // at generation 0, because the arm below is what would adopt a live one and there is no pipeline
            // to take it from — and DlqRequeueService refuses a generation-0 payload. The rows stay readable
            // in dlq_item.payload for a hand replay or a bounded backfill; see DlqRequeueService.foldEra.
            log.warn("Archiving upsert for pipeline {}: pipeline absent from the cache (disabled or deleted); "
                    + "requeue it once the pipeline is back", payload.getPipelineName());
            archive(itemId, payload,
                    "pipeline absent from the runner's cache (disabled or deleted) when the write ran", null);
            return;
        }
        // Read AFTER this, never before: a pre-fold write's rows are replaced here, and a copy taken at the
        // top of the method would post the retired provenance column ADAS refuses with 422.
        adoptIfPreFold(pipeline, payload);
        List<Map<String, Object>> rows = payload.getRows();
        if (pipeline.generation() != payload.getPipelineGeneration()) {
            // A genuine stale write, which must not land: the row would be provenance-stamped with a
            // superseded generation. Reachable only for a generation the feed actually served, because the
            // pre-fold arm above has already normalised 0 onto the live one.
            log.info("Dropping stale upsert for pipeline {} (computed against generation {})",
                    payload.getPipelineName(), payload.getPipelineGeneration());
            return;
        }

        int inserted;
        try {
            inserted = runnerTracer.inSpan("enrichment.upsert", spanAttributes(payload, rows.size()),
                    () -> upsertClient.upsert(payload.getPipelineName(), rows));
        } catch (RestClientResponseException e) {
            if (RetryableStatus.isRetryable(e)) {
                throw new TransientChunkException(DlqStage.UPSERT, e);
            }
            // A rejection ADAS will keep rejecting — a column the pipeline no longer declares after a mid-flight
            // change. Dead-lettered with its payload so it is requeueable once the pipeline is fixed, and without
            // recomputing anything to reach the same answer.
            deadLetter(itemId, payload, e.getMessage(), e.getResponseBodyAsString(), ErrorMessages.classify(e));
            return;
        } catch (RestClientException e) {
            throw new TransientChunkException(DlqStage.UPSERT, e);
        }

        runnerMetrics.addRowsUpserted(inserted);
        pipelineActivity.recordWrite(payload.getPipelineName(), inserted);
        log.info("Upserted {} enrichment row(s) for pipeline {}", inserted, payload.getPipelineName());

        if (payload.isGroupWrite()) {
            stampGroup(payload);
            // The group is clean and its label has landed: the one point in a group's life that is
            // unambiguously a success, and so the one that feeds the breaker's denominator.
            dlqCircuitBreaker.recordGroupSuccess(payload.getPipelineName());
        }
    }

    /**
     * Normalises a write enqueued before ADAS's evaluator fold onto the live declaration, and strips the
     * provenance column that fold retired, instead of letting the stale guard below drop it.
     *
     * <p><strong>Why it is placed after the cache lookup and before the stale guard.</strong> There is no
     * live generation to adopt until the pipeline has been resolved; and a pre-fold write whose pipeline
     * is absent from the cache takes the arm above it, which archives the rows so nothing paid for is
     * destroyed. What it must never sit behind is the stale comparison, which is what dropped it.
     *
     * <p><strong>Why it assigns rather than just skipping the comparison.</strong> The payload's
     * generation is read again after this point: {@link #archive} and {@code UpsertFailureHandler} stamp
     * it into {@code dlq_item_row_identity_uk}, and {@code DlqRequeueService} refuses a generation-0 row
     * permanently — so a leaked 0 turns any later failure of this write into a dead-letter an operator
     * can never requeue. It is also the {@code pipeline.generation} MDC field, refreshed here with it.
     *
     * <p><strong>Why {@code evaluator_version} is removed and {@code pipeline_generation} is NOT put in
     * its place.</strong> ADAS refuses a write naming the retired spelling with 422, so the key has to
     * go. Adding the adopted generation beside it is the tempting symmetry and it would be a lie: these
     * rows were computed under the pre-fold declaration, and stamping them with the generation this
     * process happens to be serving now asserts a lineage that did not produce them. An absent stamp is
     * honest, and it is permitted — ADAS's write validator requires only that every column named is
     * permitted and that at least one declared output column is present, and its own
     * {@code dropAbsentProvenance} only ever removes provenance columns, never adds them. Nothing in ADAS
     * reads a generation stamp back.
     */
    private void adoptIfPreFold(PipelineDto pipeline, UpsertPayload payload) {
        if (!PreFoldWorkItem.isPreFold(payload.getPipelineGeneration())) {
            return;
        }
        payload.setPipelineGeneration(pipeline.generation());
        payload.setRows(withoutRetiredProvenance(payload.getRows()));
        MDC.put(WorkerMdc.PIPELINE_GENERATION, Long.toString(pipeline.generation()));
        PreFoldWorkItem.reportAdopted(PreFoldWorkItem.Lane.UPSERT, payload.getPipelineName(),
                pipeline.generation(), runnerMetrics);
    }

    /**
     * Copies the rows without the retired {@code evaluator_version} key. Copied rather than mutated in
     * place because the row maps come off the queue's deserializer and nothing here owns them, and because
     * the replaced list is what a later archive stores — so a requeue of this write posts rows ADAS will
     * accept rather than the 422 it started as.
     */
    private static List<Map<String, Object>> withoutRetiredProvenance(List<Map<String, Object>> rows) {
        List<Map<String, Object>> stripped = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.remove(ProvenanceColumns.RETIRED_EVALUATOR_VERSION);
            stripped.add(copy);
        }
        return stripped;
    }

    /**
     * Stamps the group at the version it was evaluated at, charging the evaluation against the pipeline's daily
     * ceiling in the same transaction so an evaluation cannot be recorded without being charged.
     *
     * <p>Called on a successful write — the ordinary case — and on a permanent rejection, where the alternative
     * is an endless re-evaluation loop (see {@link #deadLetter}).
     */
    private void stampGroup(UpsertPayload payload) {
        Instant now = clock.instant();
        GroupStateWriter.ChargeOutcome outcome = groupStateWriter.stampComputedAndCharge(payload.getPipelineName(),
                payload.getGroupKey(), payload.getGroupVersion(), now, payload.isTruncated(),
                LocalDate.ofInstant(now, ZoneOffset.UTC));
        if (outcome.superseded()) {
            // Two versions of this group were in flight and completed out of order: the newer one's stamp
            // stands, and this evaluation is charged anyway because its model call was spent. Worth a line —
            // it means the group's evaluations are outrunning the sweep interval.
            runnerMetrics.recordSupersededGroupEvaluation();
            log.info("Charged a superseded evaluation of group {} for pipeline {} at version {}",
                    payload.getGroupKey(), payload.getPipelineName(), payload.getGroupVersion());
        }
    }

    /**
     * Archives a write ADAS will keep rejecting, <strong>with its payload and its task name</strong>, so a
     * requeue re-attempts the write rather than deserializing these rows as a compute chunk.
     *
     * <p>A group write is also <strong>stamped and charged</strong> here, which is the one place "clean means
     * written" is relaxed — and for the same reason {@link
     * com.epam.aidial.enrichment.worker.group.GroupWorkerService} stamps a poison group. Left dirty, the group
     * would be re-swept every pass, re-fetched and re-evaluated against the model, and rejected again; the
     * dead-letter row would collapse on its unique key, so the loop would be invisible in the DLQ while
     * spending a model call per sweep interval. Stamping it means the re-attempt happens when something
     * actually changes, and the archived payload is what makes the pending write recoverable in the meantime.
     */
    private void deadLetter(String itemId, UpsertPayload payload, String error, String rawResponse, String classified) {
        archive(itemId, payload, error, rawResponse);
        if (payload.isGroupWrite()) {
            stampGroup(payload);
            // One outcome against the pipeline's group window, not a (1, 1) fraction of its own: a group write is
            // a single unit, so a fraction over it would read as a 100% failure rate and pause the pipeline on
            // one group's rejection.
            dlqCircuitBreaker.recordGroupFailure(payload.getPipelineName());
        } else {
            // Every row in the write failed, because the whole write was rejected — and the cause (a column
            // the pipeline no longer declares) is exactly what the breaker exists to catch. Reporting it as one
            // failure out of the row count would keep it below the fraction lever indefinitely.
            int rows = payload.getRows().size();
            dlqCircuitBreaker.record(payload.getPipelineName(), rows, rows);
        }
        // The classified cause, never the stored error: that is the rejection's message, which carries ADAS's body.
        log.warn("Dead-lettered upsert for pipeline {}: {}", payload.getPipelineName(), classified);
    }

    /**
     * Writes the item to the DLQ <strong>with its payload and its task name</strong>, and does nothing else —
     * the archival half of {@link #deadLetter}, without the stamp or the breaker count that a rejection ADAS
     * keeps refusing also earns. Separate because the two callers differ in what the failure says about the
     * pipeline: a permanent rejection is evidence the pipeline is producing writes ADAS refuses, while an absent pipeline
     * is evidence of nothing at all about the work in hand.
     *
     * <p>The partial unique index makes this idempotent for a group write (keyed by its group key) and a plain
     * insert for a row-lane one (no grain key), so a repeat cannot lose the payload the archive exists to keep.
     *
     * <p>The item's id names the backfill run a write belongs to, if any — a backfill chunk's write id extends
     * the chunk's run-namespaced id — so a replay's rejected writes are attributed to their run.
     */
    private void archive(String itemId, UpsertPayload payload, String error, String rawResponse) {
        dlqItemWriter.upsert(DlqItemEntity.builder()
                .pipelineName(payload.getPipelineName())
                .grainKey(payload.getGroupKey())
                .pipelineGeneration(payload.getPipelineGeneration())
                .stage(DlqStage.UPSERT.dbValue())
                .error(error)
                .rawResponse(rawResponse)
                .payload(jsonMapper.writeValueAsString(payload))
                .taskName(UpsertRowsTask.TASK_NAME)
                .runId(DeterministicId.backfillRunIdOf(itemId))
                .build());
    }

    private static Map<String, String> spanAttributes(UpsertPayload payload, int rowCount) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("pipeline.name", payload.getPipelineName());
        attributes.put("rows", Integer.toString(rowCount));
        if (payload.isGroupWrite()) {
            attributes.put("group.key", payload.getGroupKey());
            attributes.put("group.version", Long.toString(payload.getGroupVersion()));
        }
        return attributes;
    }
}
