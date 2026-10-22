package com.epam.aidial.enrichment.dial;

import com.epam.aidial.enrichment.configuration.DialProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per-pipeline token-bucket rate limiter for DIAL calls. Sized to the pipeline's {@code rate_rpm} (or the
 * configured default). A courtesy limiter — per JVM, effective with the single active replica of
 * phase-1; DIAL Core's own per-key limit is the real backstop.
 */
@Component
@RequiredArgsConstructor
public class DialRateLimiter {

    private final RateLimiterRegistry registry;
    private final DialProperties dialProperties;

    /** Tries to acquire a permit for the pipeline, waiting up to the configured timeout. */
    public boolean acquire(String pipelineName, Integer pipelineRpm) {
        int rpm = pipelineRpm != null && pipelineRpm > 0 ? pipelineRpm : dialProperties.defaultRpm();
        RateLimiter limiter = registry.rateLimiter(pipelineName, () -> config(rpm));
        if (limiter.getRateLimiterConfig().getLimitForPeriod() != rpm) {
            // The pipeline's rate budget changed since this limiter was created (the Resilience4j config
            // supplier only runs on first creation): rebuild it so budget edits take effect at runtime.
            registry.remove(pipelineName);
            limiter = registry.rateLimiter(pipelineName, () -> config(rpm));
        }
        return limiter.acquirePermission();
    }

    /** Drops a pipeline's limiter when the pipeline leaves the synced set, bounding registry growth over churn. */
    public void evict(String pipelineName) {
        registry.remove(pipelineName);
    }

    private RateLimiterConfig config(int rpm) {
        return RateLimiterConfig.custom()
                .limitForPeriod(rpm)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(dialProperties.rateLimiterTimeout())
                .build();
    }
}
