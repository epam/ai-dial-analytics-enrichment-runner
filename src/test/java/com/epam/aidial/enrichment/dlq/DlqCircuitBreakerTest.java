package com.epam.aidial.enrichment.dlq;

import com.epam.aidial.enrichment.configuration.DlqProperties;
import com.epam.aidial.enrichment.pause.PauseOrigin;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.support.SettableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DlqCircuitBreakerTest {

    private final SettableClock clock = new SettableClock(Instant.parse("2026-09-15T12:00:00Z"));

    private final PipelinePauseRegistry registry = new PipelinePauseRegistry(clock);

    private DlqCircuitBreaker breaker(int threshold, double fraction) {
        return breaker(threshold, fraction, 4);
    }

    private DlqCircuitBreaker breaker(int threshold, double fraction, int groupWindow) {
        return new DlqCircuitBreaker(registry,
                new DlqProperties(30, new DlqProperties.Breaker(threshold, fraction, groupWindow,
                        Duration.ofMinutes(15), Duration.ofHours(4))),
                clock);
    }

    private static void record(DlqCircuitBreaker breaker, String pipelineName, int failures, int successes) {
        for (int i = 0; i < failures; i++) {
            breaker.recordGroupFailure(pipelineName);
        }
        for (int i = 0; i < successes; i++) {
            breaker.recordGroupSuccess(pipelineName);
        }
    }

    @Test
    void pausesWhenOverAbsoluteThreshold() {
        breaker(5, 0.9).record("r1", 5, 100);

        assertThat(registry.isPaused("r1")).isTrue();
    }

    @Test
    void pausesWhenOverFraction() {
        breaker(1000, 0.5).record("r2", 5, 8);

        assertThat(registry.isPaused("r2")).isTrue();
    }

    @Test
    void doesNotPauseBelowBothThresholds() {
        breaker(50, 0.5).record("r3", 2, 100);

        assertThat(registry.isPaused("r3")).isFalse();
    }

    @Test
    void zeroDeadLetteredNeverPauses() {
        breaker(1, 0.01).record("r4", 0, 100);

        assertThat(registry.isPaused("r4")).isFalse();
    }

    @Test
    void oneGroupFailureDoesNotPauseThePipeline() {
        // Routed through the fraction lever as (1, 1) a single group would read as a 100% failure rate.
        breaker(3, 0.5).recordGroupFailure("g1");

        assertThat(registry.isPaused("g1")).isFalse();
    }

    @Test
    void groupFailuresPauseThePipelineOnceTheWindowIsMostlyFailures() {
        DlqCircuitBreaker breaker = breaker(1000, 0.5, 4);

        record(breaker, "g2", 3, 0);
        assertThat(registry.isPaused("g2")).isFalse();      // the window is not full yet
        record(breaker, "g2", 1, 0);

        assertThat(registry.isPaused("g2")).isTrue();
    }

    @Test
    void partlyFullWindowNeverPausesThePipeline() {
        // The window doubles as the minimum sample: a pipeline whose first groups fail is not condemned on them.
        DlqCircuitBreaker breaker = breaker(1000, 0.5, 100);

        record(breaker, "g6", 99, 0);

        assertThat(registry.isPaused("g6")).isFalse();
    }

    @Test
    void residualFailureRateNeverPausesThePipeline() {
        // The regression this window exists for: an undecaying count reached any threshold eventually, so a
        // pipeline with a stable minority of groups the model cannot label auto-paused forever, however healthy.
        DlqCircuitBreaker breaker = breaker(1000, 0.5, 4);

        for (int batch = 0; batch < 500; batch++) {
            record(breaker, "g4", 1, 3);
        }

        assertThat(registry.isPaused("g4")).isFalse();
    }

    @Test
    void successesLeavingTheWindowLetLaterFailuresTrip() {
        // The window forgets forwards as well as backwards: a pipeline that was healthy and then broke is paused
        // on what it is doing now, not spared by what it did earlier.
        DlqCircuitBreaker breaker = breaker(1000, 0.5, 4);

        record(breaker, "g5", 0, 4);
        assertThat(registry.isPaused("g5")).isFalse();
        record(breaker, "g5", 2, 0);
        assertThat(registry.isPaused("g5")).isTrue();
    }

    @Test
    void nonPositiveWindowDisablesTheGroupLane() {
        DlqCircuitBreaker breaker = breaker(1000, 0.5, 0);

        record(breaker, "g7", 100, 0);

        assertThat(registry.isPaused("g7")).isFalse();
    }

    @Test
    void evictingPipelineForgetsItsGroupOutcomes() {
        // A pipeline that leaves the synced set must not hand its accumulated failures back on re-enable — nor
        // keep its entry in a map that would otherwise retain one per pipeline the runner has ever seen.
        DlqCircuitBreaker breaker = breaker(1000, 0.5, 2);
        breaker.recordGroupFailure("g3");

        breaker.evict("g3");
        breaker.recordGroupFailure("g3");

        assertThat(registry.isPaused("g3")).isFalse();
    }

    /**
     * A resume is not free. The breaker drops its outcome window when it trips, so a pipeline auto-resumed
     * into an unfixed upstream must produce a full window of fresh outcomes before it can trip again — at
     * the shipped window of 50, fifty group assemblies and fifty model calls to re-derive a verdict it held
     * minutes earlier. Doubling makes an outage cost a logarithm of probes instead of a multiple.
     */
    @Test
    void consecutiveAutoPausesWiden() {
        DlqCircuitBreaker breaker = breaker(1000, 0.5, 4);

        record(breaker, "p1", 4, 0);
        Instant firstResume = registry.paused().get("p1").resumesAt();
        assertThat(firstResume).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));

        // The pause lifts, the upstream is still broken, and the fresh window fills with failures again.
        clock.advance(Duration.ofMinutes(16));
        assertThat(registry.isPaused("p1")).isFalse();
        record(breaker, "p1", 4, 0);

        assertThat(registry.paused().get("p1").resumesAt())
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));
    }

    @Test
    void recoveryResetsTheWidening() {
        DlqCircuitBreaker breaker = breaker(1000, 0.5, 4);
        record(breaker, "p1", 4, 0);
        clock.advance(Duration.ofMinutes(16));
        assertThat(registry.isPaused("p1")).isFalse();

        // A full window that did not trip is recovery, judged on the same evidence the trip was made from.
        record(breaker, "p1", 0, 4);
        record(breaker, "p1", 4, 0);

        assertThat(registry.paused().get("p1").resumesAt())
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
    }

    @Test
    void autoPauseCarriesItsOriginAndReason() {
        breaker(5, 0.9).record("p2", 5, 100);

        PipelinePauseRegistry.Pause pause = registry.paused().get("p2");
        assertThat(pause.origin()).isEqualTo(PauseOrigin.BREAKER);
        assertThat(pause.reason()).contains("5/100");
        assertThat(pause.resumesAt()).isNotNull();
    }
}
