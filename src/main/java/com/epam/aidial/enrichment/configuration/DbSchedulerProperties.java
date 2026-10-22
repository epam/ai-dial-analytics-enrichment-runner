package com.epam.aidial.enrichment.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed binding for the db-scheduler runtime ({@code db-scheduler.*}). The runner wires the
 * {@code Scheduler} bean itself rather than using the Spring Boot starter.
 *
 * @param heartbeat       how often a running execution updates its heartbeat (tuned well below the
 *                        default so a crashed replica's in-flight work is revived promptly)
 * @param pollingInterval how often the scheduler polls for due executions
 * @param threads         worker thread count
 * @param autoStart       whether the scheduler starts polling on application start (disabled in some
 *                        tests to keep reconciliation assertions deterministic)
 */
@ConfigurationProperties(prefix = "db-scheduler")
public record DbSchedulerProperties(
        Duration heartbeat,
        Duration pollingInterval,
        int threads,
        boolean autoStart) {
}
