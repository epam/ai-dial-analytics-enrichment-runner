package com.epam.aidial.enrichment.class1;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Class1PropertiesTest {

    private final Class1Properties properties =
            new Class1Properties(10_000, 100_000);

    @Test
    void unsetLimitFallsBackToTheDefault() {
        assertThat(properties.resolveLimit(null)).isEqualTo(10_000);
    }

    @Test
    void declaredLimitIsHonoured() {
        assertThat(properties.resolveLimit(250)).isEqualTo(250);
    }

    @Test
    void nonPositiveLimitIsFlooredAtOne() {
        assertThat(properties.resolveLimit(0)).isEqualTo(1);
        assertThat(properties.resolveLimit(-5)).isEqualTo(1);
    }

    @Test
    void overLargeLimitIsCappedRatherThanSentAndRejected() {
        // ADAS answers an over-limit request with a 400 instead of clamping, so a pipeline declaring a huge
        // batch would otherwise never materialize at all.
        assertThat(properties.resolveLimit(5_000_000)).isEqualTo(100_000);
    }
}
