package com.epam.aidial.enrichment.dlq;

import com.epam.aidial.enrichment.configuration.DlqProperties;
import com.epam.aidial.enrichment.pause.PauseOrigin;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.worker.HumanDuration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A circuit breaker keyed on dead-letter rate: when a pipeline's dead-lettered rows in a single scan page
 * or worker chunk cross the configured absolute count or fraction, the pipeline is auto-paused so a
 * mis-bound pipeline or a model/endpoint outage does not turn the whole backlog into DLQ. Recovery is a
 * manual resume once the cause is fixed.
 *
 * <p>Two lanes, because the two grains have different denominators. A page or chunk is a batch and is
 * measured as one — {@link #record(String, int, int)}. A group work item is a single unit with no batch to
 * be a fraction of, so it is measured over a window of the pipeline's recent group outcomes instead —
 * {@link #recordGroupOutcome}, which is where that choice is argued.
 *
 * <p>Every pause it creates is a {@link PauseOrigin#BREAKER} pause and therefore <strong>expires</strong>.
 * What this class asserts is an inference about something outside the runner, drawn from a failure rate,
 * and most of what produces it clears without anyone touching the runner; recording that inference as
 * permanent converted a bounded upstream outage into a fourteen-hour silent stop. The window widens on each
 * consecutive trip — see {@link com.epam.aidial.enrichment.configuration.DlqProperties.Breaker#pauseWindow}
 * — and resets when a pipeline fills a full outcome window without tripping, which is recovery judged on
 * the same evidence the trip was made from. An operator's manual resume does not reset it: a resume is a
 * decision, not evidence about the upstream.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqCircuitBreaker {

    private final PipelinePauseRegistry pauseRegistry;
    private final DlqProperties dlqProperties;
    private final Clock clock;

    /** Per pipeline, the outcomes of its most recent group work items; see {@link #recordGroupOutcome}. */
    private final Map<String, OutcomeWindow> groupOutcomes = new ConcurrentHashMap<>();

    /** Per pipeline, how many times in a row it has auto-paused without an intervening recovery. */
    private final Map<String, AtomicInteger> consecutivePauses = new ConcurrentHashMap<>();

    /**
     * Records one group's dead-letter. Counted against the same window its successes are, so what trips the
     * breaker is the <em>rate</em> — see {@link #recordGroupOutcome}.
     */
    public void recordGroupFailure(String pipelineName) {
        recordGroupOutcome(pipelineName, true);
    }

    /**
     * Records one group that completed. Reported for the denominator's sake and for no other reason: without
     * it the group lane cannot tell a pipeline that is failing from a pipeline that is merely busy.
     *
     * <p>Exactly one outcome per group attempt, which is why this is reported where the group is stamped
     * clean — the successful write, or the empty-group stamp that needs no write — and not also at the end of
     * the compute item that preceded it.
     */
    public void recordGroupSuccess(String pipelineName) {
        recordGroupOutcome(pipelineName, false);
    }

    /**
     * Records one group work item's outcome and pauses the pipeline when the recent ones are mostly failures.
     *
     * <p>A group work item is a single unit, so it has no page to be a fraction of: routing it through
     * {@link #record(String, int, int)} as {@code (1, 1)} would read as a 100% failure rate and pause the
     * pipeline on the very first poison group. The denominator is the pipeline's last {@code group-window} outcomes
     * instead, which is what lets this lane answer the question the row lane's fraction answers — is this
     * pipeline failing, or is it working and occasionally hitting a bad group?
     *
     * <p><strong>A running count of failures alone cannot answer that.</strong> It was what this lane used,
     * and because it never decayed, any permanent failure rate above zero reached the threshold eventually —
     * so a pipeline with a stable 3% of groups the model cannot label auto-paused every few thousand groups,
     * indefinitely, and looked from the outside like a pipeline that had broken. A window bounds how far back a
     * failure counts for: 3% of a full window is 3%, at any volume and forever, while an outage is at or near
     * 100% of one within a window's worth of work.
     *
     * <p>The window must be <em>full</em> before it can trip, so it doubles as the minimum sample and a pipeline
     * whose first few groups fail is not paused on the strength of them. Tripping drops the window, so
     * recovery after a resume is judged on a fresh one rather than re-tripping on the failures that caused
     * the pause. Like {@link PipelinePauseRegistry} itself this state is in-memory and single-replica.
     */
    private void recordGroupOutcome(String pipelineName, boolean deadLettered) {
        if (pauseRegistry.isPaused(pipelineName)) {
            return;
        }
        DlqProperties.Breaker breaker = dlqProperties.breaker();
        // A non-positive window or fraction disables the lane rather than tripping on the first outcome
        // (guards against a misconfigured 0), matching how the row lane treats its own levers.
        if (breaker.groupWindow() <= 0 || breaker.fraction() <= 0) {
            return;
        }
        OutcomeWindow window = groupOutcomes.computeIfAbsent(pipelineName, name -> new OutcomeWindow(breaker.groupWindow()));
        OutcomeWindow.Verdict verdict = window.record(deadLettered, breaker.fraction());
        if (verdict.recovered()) {
            // A full window that did not trip is recovery, on the same evidence a trip is made from — so the
            // next outage starts again at the shortest pause rather than inheriting the last one's widening.
            consecutivePauses.remove(pipelineName);
        }
        if (verdict.failures() == 0) {
            return;
        }
        // Paused before the window is dropped, not after: in the other order a concurrent outcome for the
        // same pipeline still reads it as unpaused and re-creates a window that then outlives the pause.
        autoPause(pipelineName, verdict.failures() + " of its last " + breaker.groupWindow()
                + " group(s) dead-lettered");
        groupOutcomes.remove(pipelineName);
    }

    /**
     * Auto-pauses a pipeline for the window its consecutive-trip count earns, and says so at WARN including
     * when it will lift itself — the line an operator reads first, and previously the only place a pause was
     * ever announced.
     */
    private void autoPause(String pipelineName, String reason) {
        int attempts = consecutivePauses.computeIfAbsent(pipelineName, name -> new AtomicInteger())
                .incrementAndGet();
        Duration window = dlqProperties.breaker().pauseWindow(attempts);
        if (window == null) {
            pauseRegistry.pause(pipelineName, PauseOrigin.BREAKER, reason);
            log.warn("DLQ circuit breaker tripped for pipeline {} ({}); auto-pausing enqueue until an operator "
                    + "resumes it (expiry is disabled)", pipelineName, reason);
            return;
        }
        pauseRegistry.pauseUntil(pipelineName, PauseOrigin.BREAKER, reason, clock.instant().plus(window));
        log.warn("DLQ circuit breaker tripped for pipeline {} ({}); auto-pausing enqueue for {} (consecutive "
                + "pause {})", pipelineName, reason, HumanDuration.format(window), attempts);
    }

    /** Forgets a pipeline's group outcomes and pause history when it leaves the synced set, bounding the maps. */
    public void evict(String pipelineName) {
        groupOutcomes.remove(pipelineName);
        consecutivePauses.remove(pipelineName);
    }

    public void record(String pipelineName, int deadLettered, int total) {
        if (deadLettered == 0 || pauseRegistry.isPaused(pipelineName)) {
            return;
        }
        DlqProperties.Breaker breaker = dlqProperties.breaker();
        // A non-positive threshold/fraction disables that lever rather than tripping on the first
        // dead-letter (guards against a misconfigured 0). The two levers cross at threshold/fraction rows:
        // at or below that many, the fraction binds first; above it, the count does. The defaults put the
        // crossover at the default page size, so an ordinary page is judged proportionally and the count is
        // the absolute backstop for a pipeline that raises `rows_per_scan` — there is no upper clamp on it,
        // and half of a very large page is a DLQ volume nobody wants written before somebody looks. On a
        // worker chunk the fraction is the only reachable lever, chunks being far smaller than the count.
        boolean overCount = breaker.threshold() > 0 && deadLettered >= breaker.threshold();
        boolean overFraction = breaker.fraction() > 0 && total > 0
                && (double) deadLettered / total >= breaker.fraction();
        if (overCount || overFraction) {
            autoPause(pipelineName, deadLettered + "/" + total + " dead-lettered");
        }
    }

    /**
     * One pipeline's most recent group outcomes: a ring of dead-letter flags with a running failure count, so
     * recording an outcome and reading the rate are both constant-time however long the window is.
     */
    private static final class OutcomeWindow {

        private final boolean[] deadLettered;
        private int next;
        private int size;
        private int failures;

        private OutcomeWindow(int capacity) {
            this.deadLettered = new boolean[capacity];
        }

        /**
         * What one recorded outcome says about the pipeline.
         *
         * @param failures  the failure count that tripped the breaker, or {@code 0} when it did not.
         *                  Unambiguous because a trip needs a positive fraction, which no window of zero
         *                  failures can reach
         * @param recovered whether this outcome completed a full window that did <em>not</em> trip, which is
         *                  the only evidence of recovery this lane has and what resets the pause widening
         */
        private record Verdict(int failures, boolean recovered) {
        }

        /**
         * Records one outcome, evicting the oldest when the window is full.
         */
        private synchronized Verdict record(boolean failed, double fraction) {
            if (size == deadLettered.length && deadLettered[next]) {
                failures--;                     // the outcome about to be overwritten leaves the window
            }
            deadLettered[next] = failed;
            if (failed) {
                failures++;
            }
            next = (next + 1) % deadLettered.length;
            if (size < deadLettered.length) {
                size++;
            }
            boolean full = size == deadLettered.length;
            boolean tripped = full && (double) failures / size >= fraction;
            return new Verdict(tripped ? failures : 0, full && !tripped);
        }
    }
}
