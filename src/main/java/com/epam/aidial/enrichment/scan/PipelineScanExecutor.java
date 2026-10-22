package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.adas.AdasScanClient;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ScanResponse;
import com.epam.aidial.enrichment.configuration.ScanProperties;
import com.epam.aidial.enrichment.dlq.DlqCircuitBreaker;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.exception.PipelineRecreatedException;
import com.epam.aidial.enrichment.observability.Elapsed;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.RunnerTracer;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.PipelineCursorEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.runtime.PipelineRunOutcome;
import com.epam.aidial.enrichment.scan.group.GroupDirtyMarkCommitter;
import com.epam.aidial.enrichment.scan.group.GroupMarkBatch;
import com.epam.aidial.enrichment.scan.group.GroupMarkBuilder;
import com.epam.aidial.enrichment.spend.SpendPauseGate;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.sync.PipelineRouting;
import com.epam.aidial.enrichment.worker.WorkerMdc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * Runs the draining incremental scan for one pipeline when its recurring task fires. Each fire:
 * <ol>
 *   <li>skips entirely if the pipeline is gone from the cache (lifecycle guard), is paused, is over its
 *       daily spend budget, or its in-flight work depth exceeds the backpressure threshold;</li>
 *   <li>otherwise drains pages up to the per-fire budget: scan a page (HTTP, side-effect free), turn it
 *       into work, and commit that work + DLQ items + cursor advance in one transaction.</li>
 * </ol>
 * What a page becomes depends on the pipeline's trigger kind: a row-grain pipeline's page becomes chunk work
 * items, a group pipeline's page becomes per-group dirty marks (the readiness sweep enqueues from those,
 * later and on its own schedule). Everything else about the drain is shared.
 *
 * <p>The cursor advances past sampled-out and un-mappable rows — they are "seen". It does <em>not</em>
 * advance past a row nothing accounted for, nor over a page that did not carry a column the lane
 * dereferences: both fail the tick with the position untouched, so the next cadence re-reads the same range.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@LogExecution
public class PipelineScanExecutor {

    private final PipelineCache pipelineCache;
    private final AdasScanClient scanClient;
    private final ChunkBuilder chunkBuilder;
    private final ScanPageCommitter committer;
    private final GroupMarkBuilder groupMarkBuilder;
    private final GroupDirtyMarkCommitter groupDirtyMarkCommitter;
    private final PipelineCursorRepository cursorRepository;
    private final BackpressureGate backpressureGate;
    private final ScanProperties properties;
    private final PipelinePauseRegistry pauseRegistry;
    private final DlqCircuitBreaker dlqCircuitBreaker;
    private final SpendPauseGate spendPauseGate;
    private final RunnerMetrics runnerMetrics;
    private final RunnerTracer runnerTracer;
    private final PipelineRunOutcome runOutcome;

    public void execute(String pipelineName) {
        PipelineDto pipeline = pipelineCache.get(pipelineName);
        if (pipeline == null) {
            log.debug("Pipeline {} absent from cache; skipping scan", pipelineName);
            return;
        }
        // Records the fire's outcome for the runtime view and rethrows unchanged: the failure line stays the
        // scheduler listener's, and nothing here logs or counts. The catch spans the gates because the spend and
        // backpressure reads query Postgres, and a failure there fails the fire as surely as a failed page does.
        try {
            if (pauseRegistry.isPaused(pipelineName)) {
                log.info("Pipeline {} is paused; skipping scan", pipelineName);
                return;
            }
            // The two gates log their own transitions; the per-fire skip is DEBUG so a closed gate is one line, not one
            // per fire.
            if (spendPauseGate.isOverBudget(pipeline)) {
                log.debug("Pipeline {} is over its daily spend budget; skipping scan", pipelineName);
                return;
            }
            if (backpressureGate.isBackpressured(pipelineName)) {
                log.debug("Backpressure: skipping scan for pipeline {}", pipelineName);
                return;
            }

            boolean hasMore;
            try (WorkerMdc.Scope ignored = WorkerMdc.scope().put(WorkerMdc.PIPELINE_NAME, pipelineName)) {
                hasMore = runnerTracer.inSpan("enrichment.scan-tick", Map.of("pipeline.name", pipelineName),
                        () -> drain(pipeline, pipelineName));
            }
            runOutcome.recordSuccess(pipelineName, hasMore);
        } catch (PipelineRecreatedException e) {
            // Not a failure: the page was scanned for a pipeline that no longer exists, and its commit refused before
            // writing anything. Pages committed earlier in this fire were discarded by the sync's reset, so the next
            // fire starts the new pipeline from the beginning.
            log.info("Pipeline {} was recreated during this fire; page not committed, ending the fire", pipelineName);
        } catch (RuntimeException e) {
            runOutcome.recordFailure(pipelineName, ErrorMessages.classify(e));
            throw e;
        }
    }

    /** Drains up to the page budget and answers whether the last page read reported more rows. */
    private boolean drain(PipelineDto pipeline, String pipelineName) {
        int scanLimit = properties.resolveScanLimit(pipeline.rowsPerScan());
        int chunkSize = properties.resolveChunk(pipeline.rowsPerCall());

        String cursor = cursorRepository.findById(pipelineName)
                .map(PipelineCursorEntity::getCursor)
                .orElse(null);

        long startedAt = System.nanoTime();
        String cursorAtStart = cursor;
        FireSummary summary = new FireSummary();
        int pages = 0;
        // True until a page says otherwise, so a fire that reads no page never reads as caught up.
        boolean hasMore = true;
        while (pages < properties.maxPagesPerFire()) {
            String cursorBefore = cursor;
            ScanResponse page = scanClient.scan(pipelineName, cursorBefore, scanLimit);
            processPage(pipeline, chunkSize, cursorBefore, page, summary);
            pages++;
            hasMore = page.hasMore();
            // Mirrors what the commit persisted, so the loop can never carry a blank cursor forward as if it
            // meant "start from the beginning".
            cursor = CursorGuard.advance(cursorBefore, page.nextCursor());
            if (!page.hasMore()) {
                break;
            }
            if (CursorGuard.isStalled(cursorBefore, cursor)) {
                // ADAS claims more rows but the cursor did not advance (or reset to null): stop rather
                // than busy-re-scanning the same range or restarting from the source. Counted, because the
                // halt is silent otherwise — nothing throws and the pipeline simply stops progressing.
                runnerMetrics.incrementCursorStall(RunnerMetrics.CursorLane.SCAN);
                log.warn("Pipeline {} reported more rows but cursor did not advance; halting drain", pipelineName);
                break;
            }
            // Re-evaluate the gates each page so a mid-drain trip stops within one page, not one fire.
            if (pauseRegistry.isPaused(pipelineName)) {
                log.info("Pipeline {} auto-paused mid-scan; halting drain after {} page(s)", pipelineName, pages);
                break;
            }
            if (spendPauseGate.isOverBudget(pipeline)) {
                log.info("Pipeline {} hit its daily spend budget mid-scan; halting drain after {} page(s)", pipelineName, pages);
                break;
            }
            if (backpressureGate.isBackpressured(pipelineName)) {
                log.info("Pipeline {} backpressured mid-scan; halting drain after {} page(s)", pipelineName, pages);
                break;
            }
        }
        log.info("scan fire: pipeline={} pages={} rows={} chunks={} marks={} sampled_out={} input_map_dlq={} lag_s={} "
                        + "cursor_advanced={} ms={}", pipelineName, pages, summary.rows, summary.chunks, summary.marks,
                summary.sampledOut, summary.deadLettered, summary.lagSeconds == null ? "-" : summary.lagSeconds,
                !Objects.equals(cursorAtStart, cursor), Elapsed.millisSince(startedAt));
        return hasMore;
    }

    /**
     * Turns one scanned page into committed work. A row-grain pipeline's page becomes chunk work items; a
     * group pipeline's page becomes per-group state, from which the readiness sweep later enqueues. Only the
     * disposition differs — the drain loop, its budget, its gates and its cursor handling are shared.
     */
    private void processPage(PipelineDto pipeline, int chunkSize, String cursorBefore, ScanResponse page,
                             FireSummary summary) {
        // An empty page omits lag_seconds, and that absence is the statement "nothing left to read" — zero lag,
        // resolved once here so the committed column and the exported gauge cannot diverge.
        Long lagSeconds = LagSignal.resolve(isEmpty(page), page.lagSeconds());
        if (PipelineRouting.isGroup(pipeline)) {
            GroupMarkBatch batch = markGroups(pipeline, page, lagSeconds);
            summary.add(batch.accounting(), 0, batch.marks().size(), batch.dlqItems().size());
        } else {
            ChunkBatch batch = chunkBuilder.build(pipeline, chunkSize, page);
            committer.commit(pipeline.name(), pipeline.generation(), pipeline.createdAt(), cursorBefore, page.nextCursor(),
                    lagSeconds, batch);
            dlqCircuitBreaker.record(pipeline.name(), batch.dlqItems().size(), batch.rowCount());
            summary.add(batch.accounting(), batch.chunks().size(), 0, batch.dlqItems().size());
        }
        summary.lagSeconds = lagSeconds;
        runnerMetrics.recordLag(pipeline.name(), lagSeconds);
    }

    private GroupMarkBatch markGroups(PipelineDto pipeline, ScanResponse page, Long lagSeconds) {
        GroupMarkBatch batch = runnerTracer.inSpan("enrichment.dirty-mark", Map.of("pipeline.name", pipeline.name()),
                () -> groupMarkBuilder.build(pipeline, page));
        groupDirtyMarkCommitter.commit(pipeline.name(), pipeline.createdAt(), page.nextCursor(), lagSeconds, batch);
        dlqCircuitBreaker.record(pipeline.name(), batch.dlqItems().size(), batch.rowCount());
        return batch;
    }

    /**
     * What one fire did, totalled over its pages for the one summary line the fire logs. {@code marks} is the group
     * lane's counterpart of {@code chunks}; {@code deadLettered} counts the rows the scan itself dead-lettered.
     */
    private static final class FireSummary {
        private long rows;
        private long chunks;
        private long marks;
        private long sampledOut;
        private long deadLettered;
        private Long lagSeconds;

        void add(PageAccounting accounting, int pageChunks, int pageMarks, int pageDeadLettered) {
            rows += accounting.rowCount();
            chunks += pageChunks;
            marks += pageMarks;
            sampledOut += accounting.skippedRows().getOrDefault(RunnerMetrics.SkipReason.SAMPLING, 0);
            deadLettered += pageDeadLettered;
        }
    }

    /** A page ADAS answered with no rows at all — an absent {@code rows} array counts the same as an empty one. */
    private static boolean isEmpty(ScanResponse page) {
        return page.rows() == null || page.rows().isEmpty();
    }
}
