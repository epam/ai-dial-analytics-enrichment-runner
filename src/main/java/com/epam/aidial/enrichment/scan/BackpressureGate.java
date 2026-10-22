package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.configuration.ScanProperties;
import com.epam.aidial.enrichment.observability.TransitionLog;
import com.epam.aidial.enrichment.worker.WorkTasks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The one answer to "does this pipeline already have more work queued than it should?".
 *
 * <p>Both enqueue paths ask it — the scan tick before it drains a page into chunks or dirty marks, and the
 * readiness sweep before it enqueues group work — and they must ask it the same way. The tasks counted and
 * the threshold compared against are the whole of the definition, and two copies of three lines would be
 * free to drift into a pipeline that one path considers busy and the other considers idle.
 *
 * <p>The count spans every task in {@link WorkTasks#PIPELINE_SCOPED} rather than the one the caller is about
 * to enqueue onto: since the compute/write split a pipeline's pending work is spread over
 * {@code enrich-chunk}, {@code enrich-group} and {@code upsert-rows}, and counting one of them would read a
 * pipeline whose writes are backed up behind a slow ADAS as idle and keep enqueuing.
 *
 * <p>The gate logs its own transitions with the depth and threshold that decided them, once per change, so the
 * callers skip quietly while it holds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackpressureGate {

    private final InflightCounter inflightCounter;
    private final ScanProperties scanProperties;

    /** Whether backpressure was last reported on, per pipeline. */
    private final TransitionLog<Boolean> transitions = new TransitionLog<>(Boolean.FALSE);

    /** Whether the pipeline's in-flight work depth is above {@code scan.max-inflight}. */
    public boolean isBackpressured(String pipelineName) {
        long inflight = inflightCounter.countByTasksAndInstancePrefix(
                WorkTasks.PIPELINE_SCOPED, DeterministicId.pipelinePrefix(pipelineName));
        boolean on = inflight > scanProperties.maxInflight();
        if (transitions.changed(pipelineName, on)) {
            log.info("backpressure {}: pipeline={} inflight={} {} scan.max-inflight={}", on ? "on" : "off",
                    pipelineName, inflight, on ? ">" : "<=", scanProperties.maxInflight());
        }
        return on;
    }

    /** Forgets a pipeline's last reported backpressure state when it leaves the synced set, bounding the map. */
    public void evict(String pipelineName) {
        transitions.forget(pipelineName);
    }
}
