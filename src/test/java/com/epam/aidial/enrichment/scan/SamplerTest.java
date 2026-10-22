package com.epam.aidial.enrichment.scan;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SamplerTest {

    private final Sampler sampler = new Sampler();

    @Test
    void nullSamplingKeepsEverything() {
        assertThat(sampler.keep("event-1", null)).isTrue();
    }

    @Test
    void zeroSamplingDropsEverything() {
        assertThat(IntStream.range(0, 500).anyMatch(i -> sampler.keep("event-" + i, 0.0))).isFalse();
    }

    @Test
    void decisionIsDeterministic() {
        for (int i = 0; i < 500; i++) {
            String key = "event-" + i;
            assertThat(sampler.keep(key, 0.5)).isEqualTo(sampler.keep(key, 0.5));
        }
    }

    @Test
    void raisingSamplingOnlyEverAddsRows() {
        for (int i = 0; i < 2000; i++) {
            String key = "event-" + i;
            if (sampler.keep(key, 0.3)) {
                assertThat(sampler.keep(key, 0.6)).as("kept at 30%% must stay kept at 60%%: %s", key).isTrue();
            }
        }
    }

    @Test
    void hashIsStableAcrossCalls() {
        long first = Sampler.hash64("event-123");
        long second = Sampler.hash64("event-123");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void keepsApproximatelyTheRequestedFraction() {
        long kept = IntStream.range(0, 10000).filter(i -> sampler.keep("event-" + i, 0.5)).count();
        assertThat(kept).isBetween(4500L, 5500L);
    }
}
