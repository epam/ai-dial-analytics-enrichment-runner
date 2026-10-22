package com.epam.aidial.enrichment.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Supplies the clock that time-threshold logic reads instead of calling {@code Instant.now()} inline.
 *
 * <p>Group readiness (`ready_when.idle`, `ready_when.max_staleness`) and state eviction are decisions
 * about elapsed time, and the only alternative way to test them is to sleep — slow and flaky. A test
 * replaces this bean with a settable clock and advances it explicitly.
 *
 * <p>UTC, matching every other date boundary the runner reasons about (the spend counter's day rollover).
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
