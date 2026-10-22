package com.epam.aidial.enrichment.scan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lag is the signal an operator alerts on, and the two ways to get it wrong fail in opposite directions: read an
 * empty page's absent {@code lag_seconds} as "no value" and a caught-up pipeline reports the last backlog's lag
 * forever, while fabricating a zero for a page that genuinely established nothing reports a pipeline as caught up
 * when nobody knows whether it is.
 */
@SuppressWarnings({"ConstantValue", "ObviousNullCheck"}) // pins a pure function's contract the IDE can infer
class LagSignalTest {

    @Test
    void reportedLagAlwaysWins() {
        assertThat(LagSignal.resolve(false, 42L)).isEqualTo(42L);
        // Even for a window that wrote nothing: if ADAS computed a lag, it knows better than the empty-page pipeline.
        assertThat(LagSignal.resolve(true, 42L)).isEqualTo(42L);
    }

    @Test
    void anEmptyPageMeansCaughtUpNotUnknown() {
        // The bug this exists to prevent: skipping this value latches the gauge at the last non-empty page's lag.
        assertThat(LagSignal.resolve(true, null)).isEqualTo(LagSignal.CAUGHT_UP);
        assertThat(LagSignal.CAUGHT_UP).isZero();
    }

    @Test
    void nonEmptyPageWithoutLagIsUnknown() {
        // Rows but no lag_seconds: nothing was established, so nothing may be claimed — not even zero.
        assertThat(LagSignal.resolve(false, null)).isNull();
    }

    @Test
    void unknownKeepsTheStoredLagAndKnownReplacesIt() {
        assertThat(LagSignal.orKeep(30L, null)).isEqualTo(30L);
        assertThat(LagSignal.orKeep(30L, 5L)).isEqualTo(5L);
        // Caught up is a value like any other, so it replaces a stale one rather than being kept out.
        assertThat(LagSignal.orKeep(30L, LagSignal.CAUGHT_UP)).isZero();
        assertThat(LagSignal.orKeep(null, null)).isNull();
        assertThat(LagSignal.orKeep(null, 7L)).isEqualTo(7L);
    }
}
