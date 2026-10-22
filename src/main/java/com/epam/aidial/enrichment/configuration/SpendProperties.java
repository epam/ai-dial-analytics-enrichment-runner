package com.epam.aidial.enrichment.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for best-effort spend tracking and the spend-pause gate ({@code spend.*}).
 *
 * @param pause  pause-gate settings
 * @param budget per-pipeline daily budget (0 = unlimited)
 */
@ConfigurationProperties(prefix = "spend")
public record SpendProperties(Pause pause, Budget budget) {

    /**
     * @param enabled when true, a pipeline whose daily spend exceeds the budget is skipped at enqueue
     */
    public record Pause(boolean enabled) {
    }

    /**
     * Per-pipeline daily budget; {@code 0} means unlimited.
     *
     * @param dailyCalls  max DIAL calls per pipeline per day
     * @param dailyTokens max token usage per pipeline per day
     */
    public record Budget(long dailyCalls, long dailyTokens) {
    }
}
