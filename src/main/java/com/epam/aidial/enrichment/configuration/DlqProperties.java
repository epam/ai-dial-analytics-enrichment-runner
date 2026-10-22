package com.epam.aidial.enrichment.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed binding for the dead-letter queue ({@code dlq.*}), including the DLQ circuit breaker.
 *
 * <p>{@code dlq.sweep-cron} is deliberately absent: {@code @Scheduled} resolves it as a placeholder, so binding it
 * here too would be dead config.
 *
 * @param retentionDays how long dead-lettered items (and their raw responses) are kept before the TTL
 *                      sweep deletes them
 * @param breaker       the DLQ circuit breaker thresholds
 */
@ConfigurationProperties(prefix = "dlq")
public record DlqProperties(int retentionDays, Breaker breaker) {

    /**
     * DLQ circuit breaker thresholds. When a pipeline's dead-letter rate in a single scan page or worker
     * chunk crosses either the absolute count or the fraction, the pipeline is auto-paused.
     *
     * <p>The group lane reads {@code fraction} and {@code groupWindow} only. A group work item is one unit
     * with no page to be a fraction of, so its denominator is the last {@code groupWindow} outcomes of that
     * pipeline instead — see {@link com.epam.aidial.enrichment.dlq.DlqCircuitBreaker}. {@code threshold} is a
     * page/chunk lever and does not apply there.
     *
     * @param threshold   absolute dead-letter count within one scan page that trips the breaker whatever the
     *                    proportion; a backstop for a page far larger than the default, since it and
     *                    {@code fraction} cross at {@code threshold / fraction} rows
     * @param fraction    fraction that must be dead-lettered to trip the breaker — of a page/chunk on the row
     *                    lane, of the recent-outcome window on the group lane
     * @param groupWindow how many of a pipeline's most recent group outcomes the group lane's fraction is
     *                    measured over. The window must be full before it can trip, so this doubles as the
     *                    minimum sample; non-positive disables the group lane
     * @param pauseFor    how long the first auto-pause of a pipeline lasts before it resumes itself; each
     *                    consecutive auto-pause doubles it, to {@code pauseMax}. Non-positive disables expiry
     *                    and makes a trip sticky until an operator resumes it
     * @param pauseMax    ceiling on that doubling — long enough to ride out a real outage, short enough that
     *                    nobody mistakes an auto-pause for a stuck one
     */
    public record Breaker(int threshold, double fraction, int groupWindow, Duration pauseFor, Duration pauseMax) {

        /**
         * How long the {@code attempts}-th consecutive auto-pause of a pipeline lasts, or {@code null} when
         * expiry is disabled.
         *
         * <p>It widens because a resume is not free. The breaker drops its outcome window when it trips, so a
         * pipeline auto-resumed into an unfixed upstream must produce a <em>full</em> window of fresh
         * outcomes before it can trip again — at the shipped defaults, fifty group assemblies and fifty model
         * calls, spent to re-derive a verdict held fifteen minutes earlier. Doubling makes the cost of
         * probing an outage grow with its logarithm instead of in proportion to its length.
         */
        public Duration pauseWindow(int attempts) {
            if (pauseFor == null || pauseFor.isZero() || pauseFor.isNegative()) {
                return null;
            }
            Duration window = pauseFor;
            for (int i = 1; i < Math.max(1, attempts); i++) {
                window = window.multipliedBy(2);
                if (pauseMax != null && window.compareTo(pauseMax) >= 0) {
                    return pauseMax;
                }
            }
            return pauseMax != null && window.compareTo(pauseMax) > 0 ? pauseMax : window;
        }
    }
}
