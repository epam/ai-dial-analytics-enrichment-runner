package com.epam.aidial.enrichment.runtime;

import com.epam.aidial.enrichment.runtime.PipelineRunOutcome.Outcome;
import com.epam.aidial.enrichment.support.SettableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineRunOutcomeTest {

    private static final Instant START = Instant.parse("2026-10-02T12:00:00Z");
    private static final String PIPELINE = "labels";

    private final SettableClock clock = new SettableClock(START);
    private final PipelineRunOutcome outcomes = new PipelineRunOutcome(clock);

    @Test
    void nothingIsKnownBeforeTheFirstFire() {
        assertThat(outcomes.get(PIPELINE)).isEmpty();
    }

    @Test
    void caughtUpFireRecordsWhenItCaughtUp() {
        outcomes.recordSuccess(PIPELINE, false);

        assertThat(outcomes.get(PIPELINE)).contains(new Outcome(false, START, null, null));
    }

    @Test
    void fireWithMoreToReadKeepsThePreviousCaughtUpInstant() {
        outcomes.recordSuccess(PIPELINE, false);
        clock.advance(Duration.ofMinutes(1));

        outcomes.recordSuccess(PIPELINE, true);

        assertThat(outcomes.get(PIPELINE)).contains(new Outcome(true, START, null, null));
    }

    @Test
    void failureKeepsWhatTheLastCompletedFireEstablished() {
        outcomes.recordSuccess(PIPELINE, false);
        clock.advance(Duration.ofMinutes(1));

        outcomes.recordFailure(PIPELINE, "IllegalStateException: boom");

        assertThat(outcomes.get(PIPELINE)).contains(
                new Outcome(false, START, "IllegalStateException: boom", START.plus(Duration.ofMinutes(1))));
    }

    @Test
    void successClearsTheError() {
        outcomes.recordFailure(PIPELINE, "boom");
        clock.advance(Duration.ofMinutes(1));

        outcomes.recordSuccess(PIPELINE, true);

        Outcome outcome = outcomes.get(PIPELINE).orElseThrow();
        assertThat(outcome.lastError()).isNull();
        assertThat(outcome.lastErrorAt()).isNull();
        assertThat(outcome.caughtUpAt()).isNull();
    }

    @Test
    void pipelineNoLongerSyncedIsForgotten() {
        outcomes.recordSuccess(PIPELINE, false);
        outcomes.recordSuccess("kept", false);

        outcomes.retain(Set.of("kept"));

        assertThat(outcomes.get(PIPELINE)).isEmpty();
        assertThat(outcomes.get("kept")).isPresent();
    }
}
