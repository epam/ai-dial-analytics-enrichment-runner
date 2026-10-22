package com.epam.aidial.enrichment.dial;

import com.epam.aidial.enrichment.configuration.DialProperties;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DialRateLimiterTest {

    @Test
    void permitsUpToRpmThenRefusesWithinTheWindow() {
        DialProperties properties =
                new DialProperties("http://dial", "", "2025-01-01-preview", 5000, 60000, 2, Duration.ZERO);
        DialRateLimiter limiter = new DialRateLimiter(RateLimiterRegistry.ofDefaults(), properties);

        assertThat(limiter.acquire("r1", 2)).isTrue();
        assertThat(limiter.acquire("r1", 2)).isTrue();
        assertThat(limiter.acquire("r1", 2)).isFalse();
    }

    @Test
    void differentPipelinesHaveIndependentBudgets() {
        DialProperties properties =
                new DialProperties("http://dial", "", "2025-01-01-preview", 5000, 60000, 1, Duration.ZERO);
        DialRateLimiter limiter = new DialRateLimiter(RateLimiterRegistry.ofDefaults(), properties);

        assertThat(limiter.acquire("r1", 1)).isTrue();
        assertThat(limiter.acquire("r2", 1)).isTrue();
        assertThat(limiter.acquire("r1", 1)).isFalse();
    }
}
