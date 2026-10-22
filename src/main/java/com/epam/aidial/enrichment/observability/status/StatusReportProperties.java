package com.epam.aidial.enrichment.observability.status;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed binding for the status heartbeat ({@code observability.status-report.*}).
 *
 * <p>{@code interval} is also read as a placeholder by {@link PipelineStatusReporter}'s {@code @Scheduled}. It is
 * bound here so that a non-positive value fails startup instead of reaching the scheduler.
 *
 * @param interval how often each pipeline's status line and the runner line are logged
 */
@ConfigurationProperties(prefix = "observability.status-report")
public record StatusReportProperties(Duration interval) {

    public StatusReportProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("observability.status-report.interval must be positive, was " + interval);
        }
    }
}
