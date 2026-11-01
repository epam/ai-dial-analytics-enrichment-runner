package com.epam.aidial.enrichment.pause;

import com.epam.aidial.enrichment.support.SettableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A breaker pause is an inference the runner drew about something outside it, so it expires. It is the only pause
 * the registry holds: an operator stops a pipeline through ADAS's {@code enabled} flag instead.
 */
class PipelinePauseRegistryTest {

    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

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
    void listingReportsOriginReasonAndTiming() {
        registry.pauseUntil("auto", PauseOrigin.BREAKER, "40/50 dead-lettered", NOW.plus(Duration.ofMinutes(15)));
        registry.pause("sticky", PauseOrigin.BREAKER, "expiry disabled");

        assertThat(registry.paused()).hasSize(2);
        PipelinePauseRegistry.Pause auto = registry.paused().get("auto");
        assertThat(auto.origin()).isEqualTo(PauseOrigin.BREAKER);
        assertThat(auto.reason()).isEqualTo("40/50 dead-lettered");
        assertThat(auto.since()).isEqualTo(NOW);
        assertThat(auto.resumesAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        // A pause with expiry configured off reports no resume time.
        assertThat(registry.paused().get("sticky").resumesAt()).isNull();
    }

    @Test
    void autoCountIsTheWholeSetNowThatOnlyTheBreakerPauses() {
        registry.pauseUntil("auto", PauseOrigin.BREAKER, "40/50", NOW.plus(Duration.ofMinutes(15)));
        registry.pause("sticky", PauseOrigin.BREAKER, "expiry disabled");

        assertThat(registry.pausedCount()).isEqualTo(2);
        assertThat(registry.autoPausedCount()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(16));
        registry.paused();

        assertThat(registry.autoPausedCount()).isEqualTo(1);
        assertThat(registry.pausedCount()).isEqualTo(1);
    }

    @Test
    void indefiniteBreakerPauseIsHeldUntilLiftedEarly() {
        // What a non-positive dlq.breaker.pause-for buys: a deployment that wants a trip to be sticky.
        registry.pause("p1", PauseOrigin.BREAKER, "expiry disabled");

        clock.advance(Duration.ofDays(1));
        assertThat(registry.isPaused("p1")).isTrue();

        assertThat(registry.resume("p1")).isTrue();
        assertThat(registry.isPaused("p1")).isFalse();
    }

    @Test
    void resumeAnswersWhetherThereWasPauseToLift() {
        assertThat(registry.resume("never-paused")).isFalse();

        registry.pauseUntil("p1", PauseOrigin.BREAKER, "40/50", NOW.plus(Duration.ofMinutes(15)));
        clock.advance(Duration.ofMinutes(16));

        // An elapsed pause already lifted itself, so it is not one to lift.
        assertThat(registry.resume("p1")).isFalse();
    }

    @Test
    void currentAnswersWhetherAndWhoInOneRead() {
        registry.pauseUntil("auto", PauseOrigin.BREAKER, "40/50 dead-lettered", NOW.plus(Duration.ofMinutes(15)));

        assertThat(registry.current("auto")).get().extracting(PipelinePauseRegistry.Pause::origin)
                .isEqualTo(PauseOrigin.BREAKER);
        assertThat(registry.current("other")).isEmpty();

        clock.advance(Duration.ofMinutes(16));

        // Expiry is evaluated for the key asked about, exactly as isPaused evaluates it.
        assertThat(registry.current("auto")).isEmpty();
    }
}
