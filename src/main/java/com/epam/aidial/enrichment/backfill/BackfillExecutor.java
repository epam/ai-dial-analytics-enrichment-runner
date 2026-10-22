package com.epam.aidial.enrichment.backfill;

import com.epam.aidial.enrichment.adas.AdasMaterializeClient;
import com.epam.aidial.enrichment.adas.AdasScanClient;
import com.epam.aidial.enrichment.adas.dto.MaterializeRequest;
import com.epam.aidial.enrichment.adas.dto.MaterializeResponse;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ScanRequest;
import com.epam.aidial.enrichment.adas.dto.ScanResponse;
import com.epam.aidial.enrichment.class1.Class1Properties;
import com.epam.aidial.enrichment.configuration.ScanProperties;
import com.epam.aidial.enrichment.dlq.DlqCircuitBreaker;
import com.epam.aidial.enrichment.observability.Elapsed;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import com.epam.aidial.enrichment.pause.PauseOrigin;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.RunHistoryEntity;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.epam.aidial.enrichment.scan.ChunkBatch;
import com.epam.aidial.enrichment.scan.ChunkBuilder;
import com.epam.aidial.enrichment.scan.CursorGuard;
import com.epam.aidial.enrichment.scan.group.GroupMarkBatch;
import com.epam.aidial.enrichment.scan.group.GroupMarkBuilder;
import com.epam.aidial.enrichment.spend.SpendPauseGate;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.sync.PipelineRouting;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Drains one batch of a backfill run when its {@code backfill-run} task fires, optionally bounded to a version
 * window, then either schedules the next batch (more pages) or marks the run done. A run that is
 * paused/cancelled/done stops the chain.
 *
 * <p><b>Before every page the batch re-checks the conditions it must not drain under</b>, and pauses the run
 * with the reason that names the condition: the pipeline absent from the cache ({@code pipeline_absent}), its
 * declaration changed since the run was started or resumed ({@code pipeline_changed}), the pipeline paused by an
 * operator or the breaker, or its daily spend budget exhausted. None of these is resumed automatically. A batch
 * that fires before this process has populated the pipeline cache even once is <em>held</em>, not paused: an
 * empty cache at startup is the runner's own ignorance, not a fact about the feed, and without the hold every
 * backfill due across a restart would pause itself with a reason that is false.
 *
 * <p>The runner's own transitions — pause at a gate, completion — take effect only on a still-running run, and
 * every page commit is a compare-and-set on the run's state, cursor and pinned generation; a gate's pause is
 * conditional on the generation too. A batch whose run an operator cancelled or paused mid-drain, resumed under a
 * newer generation, or that another execution advanced first, therefore stops at its next commit without
 * chaining, without spending, and without writing over the operator's decision.
 *
 * <p>A batch that fails — a page missing a column the lane dereferences, one whose rows were not all accounted
 * for, an ADAS rejection — leaves the run cursor where it was and does <strong>not</strong> mark the run
 * complete; {@link BackfillBatchFailureHandler} records it and retries.
 *
 * <p>It drives whichever read the pipeline's kind requires, mirroring the live path's routing: a row pipeline's page
 * becomes chunks at backfill priority, a <strong>group</strong> pipeline's page becomes dirty marks (the readiness
 * sweep decides when a marked group is evaluated, so its idle, staleness and cost-ceiling gates still apply),
 * and a <strong>Class-1</strong> pipeline's batch is a materialize call whose window ADAS computes and writes itself.
 * Only the page disposition differs — the run lifecycle is shared.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@LogExecution
public class BackfillExecutor {

    private final RunHistoryRepository runHistoryRepository;
    private final PipelineCache pipelineCache;
    private final AdasScanClient scanClient;
    private final ChunkBuilder chunkBuilder;
    private final GroupMarkBuilder groupMarkBuilder;
    private final AdasMaterializeClient materializeClient;
    private final Class1Properties class1Properties;
    private final RunnerMetrics runnerMetrics;
    private final BackfillPageCommitter committer;
    private final ScanProperties scanProperties;
    private final BackfillService backfillService;
    private final PipelinePauseRegistry pauseRegistry;
    private final SpendPauseGate spendPauseGate;
    private final DlqCircuitBreaker dlqCircuitBreaker;

    public void run(String runId) {
        RunHistoryEntity run = runHistoryRepository.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        if (!RunState.RUNNING.dbValue().equals(run.getState())) {
            log.info("Backfill {} is {}; stopping", runId, run.getState());
            return;
        }
        // Before the lookup: a miss in a cache that has never synced is not "the pipeline is gone". Thrown as a
        // held condition, which the failure handler reschedules without recording a failure.
        pipelineCache.requirePopulated("backfill batch " + runId);

        String pipelineName = run.getPipelineName();
        // Null only on a run from before the generation was recorded; adopted at the first page that finds the
        // pipeline in the cache. Every write this execution makes is conditional on it.
        Long generation = run.getPipelineGeneration();

        BackfillWindow window = BackfillWindow.of(run.getWindowFrom(), run.getWindowTo());

        String cursor = run.getCursor();
        int pages = 0;
        long rows = 0;
        long startedAt = System.nanoTime();
        try {
            while (pages < scanProperties.maxPagesPerFire()) {
                PipelineDto pipeline = pipelineCache.get(pipelineName);
                if (pipeline != null && generation == null) {
                    // A pre-V10 run: adopt the current generation rather than pausing on a mismatch nothing could
                    // have caused.
                    runHistoryRepository.adoptGeneration(runId, pipeline.generation());
                    generation = pipeline.generation();
                }
                if (pausedAtGate(runId, pipelineName, pipeline, generation, pages > 0)) {
                    return;
                }
                String cursorBefore = cursor;
                // The run lifecycle — state, cursor, pause/resume, chaining, gates, stall guard — is shared across
                // all three lanes; only what a page becomes differs, exactly as in the live path.
                PageOutcome outcome = PipelineRouting.isClass1(pipeline)
                        ? materializeWindow(runId, pipelineName, pipeline, window, cursorBefore)
                        : scanPage(runId, pipelineName, pipeline, window, cursorBefore);
                // What the commit persisted, so a blank cursor cannot be carried forward as "from the start of the
                // window".
                cursor = outcome.storedCursor();
                pages++;
                rows += outcome.rows();

                if (!outcome.hasMore()) {
                    if (runHistoryRepository.markDoneIfRunning(runId, Instant.now()) > 0) {
                        logBatch(runId, pipelineName, pages, rows, run.getRowsDone(), startedAt, "completed");
                    }
                    return;
                }
                if (CursorGuard.isStalled(cursorBefore, cursor)) {
                    runnerMetrics.incrementCursorStall(RunnerMetrics.CursorLane.BACKFILL);
                    log.warn("Backfill {} reported more rows but cursor did not advance; pausing for investigation",
                            runId);
                    pause(runId, generation, BackfillPauseReason.CURSOR_STALLED);
                    return;
                }
            }
        } catch (RunSupersededException e) {
            // Not a failure: an operator cancelled or paused the run, or another execution advanced it first.
            // The page rolled back; the later decision stands and this chain ends here.
            log.info("Backfill {} superseded mid-batch; stopping without chaining", runId);
            return;
        }

        // More rows remain but the per-fire page budget is exhausted: chain the next batch.
        backfillService.scheduleRun(runId, cursor);
        logBatch(runId, pipelineName, pages, rows, run.getRowsDone(), startedAt, "chained");
    }

    /**
     * The per-batch progress line: what this batch read, the run's total so far, and what happens next. {@code rows}
     * counts the rows the batch read (or, on the Class-1 lane, wrote); {@code rowsDoneBefore} is the run's
     * {@code rows_done} as this batch found it.
     */
    private static void logBatch(String runId, String pipelineName, int pages, long rows, long rowsDoneBefore,
                                 long startedAt, String next) {
        log.info("backfill batch: run={} pipeline={} pages={} rows={} rows_done={} next={} ms={}", runId, pipelineName,
                pages, rows, rowsDoneBefore + rows, next, Elapsed.millisSince(startedAt));
    }

    /**
     * The conditions a batch must not drain under, re-checked before every page, each pausing the run with the
     * reason that names it. Answers whether the run was paused (or had already stopped running).
     */
    private boolean pausedAtGate(String runId, String pipelineName, PipelineDto pipeline, Long generation,
                                 boolean midRun) {
        String when = midRun ? " mid-run" : "";
        if (pipeline == null) {
            log.warn("Backfill {} paused: pipeline {} is absent from the pipeline cache{} — disabled or deleted in "
                    + "ADAS, or refused by the runner as unexecutable; resume the run once it is back",
                    runId, pipelineName, when);
            pause(runId, generation, BackfillPauseReason.PIPELINE_ABSENT);
            return true;
        }
        if (pipeline.generation() != generation) { // non-null here: adopted above once the pipeline is present
            log.warn("Backfill {} paused: pipeline {} changed from generation {} to {}{}; resume to continue "
                    + "under the new declaration, or cancel and re-run the window", runId, pipelineName, generation,
                    pipeline.generation(), when);
            pause(runId, generation, BackfillPauseReason.PIPELINE_CHANGED);
            return true;
        }
        // Backfill honors the same enqueue guards as the live path: a paused pipeline (operator or breaker)
        // or an exhausted spend budget pauses the run rather than draining history into the DLQ / budget.
        Optional<PipelinePauseRegistry.Pause> pipelinePause = pauseRegistry.current(pipelineName);
        if (pipelinePause.isPresent()) {
            boolean breaker = pipelinePause.get().origin() == PauseOrigin.BREAKER;
            log.warn("Backfill {} paused: pipeline {} was paused by {}{}; resume the pipeline, then the run",
                    runId, pipelineName, breaker ? "the DLQ circuit breaker" : "an operator", when);
            pause(runId, generation, breaker
                    ? BackfillPauseReason.PIPELINE_PAUSED_BREAKER : BackfillPauseReason.PIPELINE_PAUSED_OPERATOR);
            return true;
        }
        if (spendPauseGate.isOverBudget(pipeline)) {
            log.warn("Backfill {} paused: pipeline {} is over its daily spend budget{}; resume after it resets "
                    + "at 00:00 UTC", runId, pipelineName, when);
            pause(runId, generation, BackfillPauseReason.SPEND_BUDGET_EXHAUSTED);
            return true;
        }
        return false;
    }

    /**
     * Pauses a still-running run still pinned to {@code generation}; yields quietly if an operator got there
     * first, or resumed the run under a newer generation since this execution read it.
     */
    private void pause(String runId, Long generation, BackfillPauseReason reason) {
        runHistoryRepository.pauseIfRunning(runId, generation, reason.dbValue(), Instant.now());
    }

    /** What one drained page leaves the loop needing to know: the cursor the commit stored, and whether more remain. */
    private record PageOutcome(String storedCursor, boolean hasMore, long rows) {
    }

    /**
     * The row and group lanes: both scan, and differ only in what the page becomes — chunks for a row pipeline,
     * dirty marks for a group pipeline.
     */
    private PageOutcome scanPage(String runId, String pipelineName, PipelineDto pipeline,
                                 BackfillWindow window, String cursorBefore) {
        ScanResponse page = scanClient.scan(pipelineName,
                new ScanRequest(cursorBefore, scanProperties.resolveScanLimit(pipeline.rowsPerScan()),
                        window.from(), window.to()));

        if (PipelineRouting.isGroup(pipeline)) {
            GroupMarkBatch batch = groupMarkBuilder.build(pipeline, page);
            String stored = committer.commitGroupMarks(runId, pipelineName, pipeline.generation(), cursorBefore,
                    page.nextCursor(), batch);
            dlqCircuitBreaker.record(pipelineName, batch.dlqItems().size(), batch.rowCount());
            return new PageOutcome(stored, page.hasMore(), batch.rowCount());
        }

        ChunkBatch batch = chunkBuilder.build(pipeline,
                scanProperties.resolveChunk(pipeline.rowsPerCall()), page);
        String stored = committer.commit(runId, pipelineName, pipeline.generation(), cursorBefore, page.nextCursor(),
                batch);
        dlqCircuitBreaker.record(pipelineName, batch.dlqItems().size(), batch.rowCount());
        return new PageOutcome(stored, page.hasMore(), batch.rowCount());
    }

    /**
     * The Class-1 lane: ADAS computes and writes the window itself, so there is nothing to enqueue and nothing to
     * dead-letter. This loop is also how an expression-version bump is re-applied to history.
     */
    private PageOutcome materializeWindow(String runId, String pipelineName, PipelineDto pipeline, BackfillWindow window,
                                          String cursorBefore) {
        MaterializeResponse materialized = materializeClient.materialize(pipelineName, new MaterializeRequest(
                cursorBefore, class1Properties.resolveLimit(pipeline.rowsPerScan()),
                window.from(), window.to()));
        // Advance on success only — the same protocol as the live Class-1 tick.
        String stored = committer.commitMaterializedWindow(runId, pipeline.generation(), cursorBefore,
                materialized.nextCursor(), materialized.rowsWritten());
        runnerMetrics.addClass1RowsWritten(pipelineName, materialized.rowsWritten());
        // Deliberately no lag recorded: a historical window's lag is its distance from now, which says nothing
        // about how far behind the pipeline's live tick is — and would overwrite the signal that does.
        return new PageOutcome(stored, materialized.hasMore(), materialized.rowsWritten());
    }
}
