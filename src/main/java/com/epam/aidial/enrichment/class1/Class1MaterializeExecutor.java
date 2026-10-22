package com.epam.aidial.enrichment.class1;

import com.epam.aidial.enrichment.adas.AdasMaterializeClient;
import com.epam.aidial.enrichment.adas.dto.MaterializeResponse;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.configuration.AdasProperties;
import com.epam.aidial.enrichment.configuration.ScanProperties;
import com.epam.aidial.enrichment.exception.AdasTransportException;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.exception.PipelineRecreatedException;
import com.epam.aidial.enrichment.observability.CallTimingLog;
import com.epam.aidial.enrichment.observability.Elapsed;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.RunnerTracer;
import com.epam.aidial.enrichment.observability.status.PipelineActivity;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.PipelineCursorEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.persistence.PipelineCursorWriter;
import com.epam.aidial.enrichment.runtime.PipelineRunOutcome;
import com.epam.aidial.enrichment.scan.CursorGuard;
import com.epam.aidial.enrichment.scan.LagSignal;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.WorkerMdc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Runs the Class-1 materialize tick for one pipeline when its recurring task fires. Each fire:
 * <ol>
 *   <li>skips entirely if the pipeline is gone from the cache (lifecycle guard) or is paused;</li>
 *   <li>otherwise drains materialize windows up to the per-fire budget: ask ADAS to materialize one
 *       window from the persisted cursor, then persist the returned cursor.</li>
 * </ol>
 *
 * <p>Unlike the scan tick there is no transaction around the call and nothing to enqueue: the work is
 * already done in ClickHouse when the response returns, so "advance on success" is the whole protocol.
 * A crash between ADAS's write and the cursor persist replays an identical window on the next fire,
 * which the target's {@code ReplacingMergeTree} collapses — at-least-once execution, exactly-once
 * effect. Because ADAS chooses (probes) the window before it writes it, that replay is byte-identical.
 *
 * <p>This lane deliberately touches no queue, no DIAL client, no rate limiter, no spend counter and no
 * dead-letter queue. A failing tick propagates so db-scheduler records the failure and re-fires on
 * cadence with the cursor untouched. A window whose cursor write is refused because the pipeline was recreated
 * is not a failure: the fire ends quietly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@LogExecution
public class Class1MaterializeExecutor {

    private final PipelineCache pipelineCache;
    private final AdasMaterializeClient materializeClient;
    private final PipelineCursorRepository cursorRepository;
    private final PipelineCursorWriter cursorWriter;
    private final Class1Properties class1Properties;
    private final ScanProperties scanProperties;
    private final PipelinePauseRegistry pauseRegistry;
    private final RunnerMetrics runnerMetrics;
    private final RunnerTracer runnerTracer;
    private final AdasProperties adasProperties;
    private final PipelineActivity pipelineActivity;
    private final PipelineRunOutcome runOutcome;

    public void execute(String pipelineName) {
        PipelineDto pipeline = pipelineCache.get(pipelineName);
        if (pipeline == null) {
            log.debug("Pipeline {} absent from cache; skipping materialize", pipelineName);
            return;
        }
        if (pauseRegistry.isPaused(pipelineName)) {
            log.info("Pipeline {} is paused; skipping materialize", pipelineName);
            return;
        }
        // No spend gate and no backpressure gate: this lane makes no model calls and queues nothing.

        DrainPosition position = new DrainPosition();
        try (WorkerMdc.Scope ignored = WorkerMdc.scope().put(WorkerMdc.PIPELINE_NAME, pipelineName)) {
            runnerTracer.inSpan("enrichment.materialize", Map.of("pipeline.name", pipelineName),
                    () -> drain(pipeline, pipelineName, position));
            runOutcome.recordSuccess(pipelineName, position.hasMore);
        } catch (PipelineRecreatedException e) {
            // Not a failure: ADAS has already written this window for the pipeline the tick started under, but the
            // cursor now belongs to the recreated one, so the position is not recorded and the next fire starts it
            // from the beginning.
            log.info("Pipeline {} was recreated during this fire; window {} not recorded, ending the fire", pipelineName,
                    position.windows + 1);
        } catch (RuntimeException e) {
            runOutcome.recordFailure(pipelineName, ErrorMessages.classify(e));
            runnerMetrics.incrementClass1TickFailures();
            // No stack here: the failure propagates, and the scheduler listener's failure line carries the one
            // stack this failure gets.
            log.warn("Materialize tick failed for pipeline {} at window {} of this fire (limit {}, {} earlier window(s) "
                    + "committed); cursor holds at the last committed window, retrying next fire: {}",
                    pipelineName, position.windows + 1, position.limit, position.windows, ErrorMessages.classify(e));
            throw e;
        }
    }

    private void drain(PipelineDto pipeline, String pipelineName, DrainPosition position) {
        int limit = class1Properties.resolveLimit(pipeline.rowsPerScan());
        position.limit = limit;

        String cursor = cursorRepository.findById(pipelineName)
                .map(PipelineCursorEntity::getCursor)
                .orElse(null);

        int windows = 0;
        while (windows < scanProperties.maxPagesPerFire()) {
            String cursorBefore = cursor;
            long start = System.nanoTime();
            MaterializeResponse window = materializeClient.materialize(pipelineName, cursorBefore, limit);
            long elapsedMs = Elapsed.millisSince(start);
            windows++;

            // An empty window omits lag_seconds because there is no window end to subtract; that is zero lag,
            // not an absence of one, and recording it as absent would latch the signal at the last backlog's
            // value on a pipeline that is now caught up.
            Long lagSeconds = LagSignal.resolve(window.rowsWritten() == 0, window.lagSeconds());
            // Reached only on a 2xx, so this is the "advance on success" point. An empty window echoes
            // the caller's cursor (or omits it entirely), which must never overwrite what we hold.
            cursorWriter.lockAndAdvance(pipelineName, pipeline.createdAt(), window.nextCursor(), lagSeconds);
            runnerMetrics.addClass1RowsWritten(pipelineName, window.rowsWritten());
            pipelineActivity.recordWrite(pipelineName, window.rowsWritten());
            runnerMetrics.recordLag(pipelineName, lagSeconds);
            position.windows = windows;
            position.hasMore = window.hasMore();
            if (CallTimingLog.isOverHalf(elapsedMs, adasProperties.readTimeoutMs())) {
                // Warned before it becomes a timeout: the window is sized by the pipeline, so that is the knob.
                log.warn("Materialized window for pipeline {}: {} row(s) written, has_more={} in {}ms (limit {}); took over half of "
                                + "{}={}, consider lowering advanced.rows_per_scan", pipelineName, window.rowsWritten(), window.hasMore(),
                        elapsedMs, limit, AdasTransportException.READ_TIMEOUT_PROPERTY, adasProperties.readTimeoutMs());
            } else if (window.rowsWritten() == 0) {
                // A caught-up pipeline answers every fire with an empty window; at INFO that is one line per cadence
                // saying nothing happened.
                log.debug("Materialized window for pipeline {}: 0 row(s) written, has_more={} in {}ms (limit {})",
                        pipelineName, window.hasMore(), elapsedMs, limit);
            } else {
                log.info("Materialized window for pipeline {}: {} row(s) written, has_more={} in {}ms (limit {})",
                        pipelineName, window.rowsWritten(), window.hasMore(), elapsedMs, limit);
            }

            cursor = CursorGuard.advance(cursorBefore, window.nextCursor());
            if (!window.hasMore()) {
                break;
            }
            if (CursorGuard.isStalled(cursorBefore, window.nextCursor())) {
                // ADAS claims more backlog but the cursor did not advance (or was omitted): stop rather
                // than re-materializing the same window for the rest of the budget. Counted, because
                // nothing throws here — the pipeline just stops progressing, and this lane has no queue depth
                // or DLQ size an operator would notice that in.
                runnerMetrics.incrementCursorStall(RunnerMetrics.CursorLane.CLASS1);
                log.warn("Pipeline {} reported more backlog but cursor did not advance; halting drain", pipelineName);
                break;
            }
            // Re-evaluate the pause gate each window so a mid-drain pause stops within one window.
            if (pauseRegistry.isPaused(pipelineName)) {
                log.info("Pipeline {} paused mid-materialize; halting drain after {} window(s)", pipelineName, windows);
                break;
            }
        }
    }

    /**
     * How far the current fire's drain got, so a failure line can say which window failed and how many before it
     * committed their cursor, and whether the last window reported more backlog.
     */
    private static final class DrainPosition {
        private int limit;
        private int windows;
        // True until a window says otherwise, so a fire that reads no window never reads as caught up.
        private boolean hasMore = true;
    }
}
