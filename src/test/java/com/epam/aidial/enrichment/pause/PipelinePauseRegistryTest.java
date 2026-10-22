package com.epam.aidial.enrichment.pause;

import com.epam.aidial.enrichment.support.SettableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An auto-pause is an inference the runner drew about something outside it; an operator pause is a
 * decision. Only the first may expire — and on 2026-09-14 neither did, so a bounded ClickHouse outage held
 * a pipeline paused for fourteen hours after it ended.
 */
class PipelinePauseRegistryTest {

    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

    private final SettableClock clock = new SettableClock(NOW);
    private final PipelinePauseRegistry registry = new PipelinePauseRegistry(clock);

    @Test
    void breakerPauseLiftsItselfWhenItsWindowElapses() {
        registry.pauseUntil("p1", PauseOrigin.BREAKER, "40 of its last 50 dead-lettered",
                NOW.plus(Duration.ofMinutes(15)));

        assertThat(registry.isPaused("p1")).isTrue();
        clock.advance(Duration.ofMinutes(16));

        assertThat(registry.isPaused("p1")).isFalse();
        assertThat(registry.paused()).isEmpty();
    }

    @Test
    void operatorPauseNeverExpires() {
        registry.pause("p1", PauseOrigin.OPERATOR, "paused by an operator");

        clock.advance(Duration.ofDays(30));

        assertThat(registry.isPaused("p1")).isTrue();
    }

    @Test
    void listingReportsOriginReasonAndTiming() {
        registry.pauseUntil("auto", PauseOrigin.BREAKER, "40/50 dead-lettered", NOW.plus(Duration.ofMinutes(15)));
        registry.pause("manual", PauseOrigin.OPERATOR, "paused by an operator");

        assertThat(registry.paused()).hasSize(2);
        PipelinePauseRegistry.Pause auto = registry.paused().get("auto");
        assertThat(auto.origin()).isEqualTo(PauseOrigin.BREAKER);
        assertThat(auto.reason()).isEqualTo("40/50 dead-lettered");
        assertThat(auto.since()).isEqualTo(NOW);
        assertThat(auto.resumesAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        // The field that separates a decision from a breaker that failed to let go.
        assertThat(registry.paused().get("manual").resumesAt()).isNull();
    }

    @Test
    void autoCountExcludesOperatorPauses() {
        // The total cannot carry an alert: an operator pause is a legitimate steady state, so a rule against
        // it is either permanently lit or permanently ignored.
        registry.pauseUntil("auto", PauseOrigin.BREAKER, "40/50", NOW.plus(Duration.ofMinutes(15)));
        registry.pause("manual", PauseOrigin.OPERATOR, "paused by an operator");

        assertThat(registry.pausedCount()).isEqualTo(2);
        assertThat(registry.autoPausedCount()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(16));
        registry.paused();

        assertThat(registry.autoPausedCount()).isZero();
        assertThat(registry.pausedCount()).isEqualTo(1);
    }

    @Test
    void indefiniteBreakerPauseIsHeldUntilAnOperatorResumes() {
        // What a non-positive dlq.breaker.pause-for buys: a deployment that wants a trip to be sticky.
        registry.pause("p1", PauseOrigin.BREAKER, "expiry disabled");

        clock.advance(Duration.ofDays(1));
        assertThat(registry.isPaused("p1")).isTrue();

        registry.resume("p1");
        assertThat(registry.isPaused("p1")).isFalse();
    }

    @Test
    void currentAnswersWhetherAndWhoInOneRead() {
        registry.pause("manual", PauseOrigin.OPERATOR, "paused by an operator");
        registry.pauseUntil("auto", PauseOrigin.BREAKER, "40/50 dead-lettered", NOW.plus(Duration.ofMinutes(15)));

        assertThat(registry.current("manual")).get().extracting(PipelinePauseRegistry.Pause::origin)
                .isEqualTo(PauseOrigin.OPERATOR);
        assertThat(registry.current("auto")).get().extracting(PipelinePauseRegistry.Pause::origin)
                .isEqualTo(PauseOrigin.BREAKER);
        assertThat(registry.current("other")).isEmpty();

        clock.advance(Duration.ofMinutes(16));

        // Expiry is evaluated for the key asked about, exactly as isPaused evaluates it.
        assertThat(registry.current("auto")).isEmpty();
        assertThat(registry.current("manual")).isPresent();
    }
}
