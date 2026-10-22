package com.epam.aidial.enrichment.configuration;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the Resilience4j {@link RateLimiterRegistry} the runner uses for its per-pipeline DIAL rate
 * limiters. The registry is built directly (no Spring Boot starter) to avoid Boot 4 autoconfig risk.
 */
@Configuration
public class RateLimiterConfiguration {

    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        return RateLimiterRegistry.ofDefaults();
    }
}
