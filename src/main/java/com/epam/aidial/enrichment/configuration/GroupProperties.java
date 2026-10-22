package com.epam.aidial.enrichment.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed binding for group-grain enrichment ({@code group.*}).
 *
 * <p>{@code group.sweep-interval}, {@code group.sweep-initial-delay} and {@code group.eviction-cron} are
 * deliberately absent: {@code @Scheduled} resolves them as placeholders, so binding them here too would be
 * dead config (as with {@code dlq.sweep-cron}).
 *
 * @param sweepBatch      maximum groups one sweep pass may enqueue per pipeline, so a large dirty backlog
 *                        cannot monopolise a single pass
 * @param maxInflight     ceiling on how much work one group pipeline may have pending at once; non-positive
 *                        derives it from the scheduler's thread count
 * @param evictionMargin  grace added on top of a pipeline's readiness window before its quiet, clean groups
 *                        may be evicted
 * @param defaultIdle      idle window for a group pipeline that declares no {@code ready_when.idle}
 * @param fetchLimit       members to request per group. ADAS <em>rejects</em> a value above its own maximum
 *                         with a 400 rather than clamping, so keep this at or below it
 * @param inputTokenBudget approximate token ceiling on a group's assembled input, applied on top of ADAS's
 *                         row and byte caps; 0 disables the runner's own trimming
 * @param scanCadence      fixed delay of every group pipeline's mark scan. Runner configuration rather than a
 *                         per-pipeline knob, because its only safe direction is short: a slow mark scan lets a
 *                         group's later rows sit unmarked, so the sweep can judge it idle while it is still
 *                         active and evaluate it early
 */
@ConfigurationProperties(prefix = "group")
public record GroupProperties(
        int sweepBatch,
        int maxInflight,
        Duration evictionMargin,
        Duration defaultIdle,
        int fetchLimit,
        int inputTokenBudget,
        Duration scanCadence) {

    /** The shortest mark-scan cadence accepted; a bare {@code 1} binds as one millisecond. */
    private static final Duration MIN_SCAN_CADENCE = Duration.ofSeconds(1);

    /** A sub-second fixed delay would fire the mark scan on every scheduler poll, and a null one fails each reconcile. */
    public GroupProperties {
        if (scanCadence == null || scanCadence.compareTo(MIN_SCAN_CADENCE) < 0) {
            throw new IllegalArgumentException("group.scan-cadence must be at least " + MIN_SCAN_CADENCE + ", was " + scanCadence);
        }
    }

    /** How many items per worker thread the derived depth ceiling allows; see {@link #resolveInflightCeiling}. */
    private static final int ITEMS_PER_THREAD = 10;

    /** Clamps the per-pass enqueue budget so a misconfigured non-positive value cannot stall the sweep. */
    public int resolveSweepBatch() {
        return Math.max(1, sweepBatch);
    }

    /**
     * The depth ceiling for one group pipeline's pending work: the configured value, or — when it is
     * non-positive, which is the default — {@code threads * 10}.
     *
     * <p>Derived by default because the coupling this exists to fix is exactly the one a constant hides. The
     * queue depth that is safe is a function of how many threads drain it, and {@code DB_SCHEDULER_THREADS}
     * is the knob an operator reaches for; a fixed batch of 500 against a pool of 5 is a thirteen-minute
     * queue, and nothing in the old configuration said so.
     *
     * <p>Ten items per thread is roughly two poll intervals' worth of work at any plausible item duration:
     * enough that a thread never idles waiting for the next sweep, small enough that a restart exposes tens
     * of queued items rather than hundreds.
     */
    public int resolveInflightCeiling(int threads) {
        return maxInflight > 0 ? maxInflight : Math.max(1, threads) * ITEMS_PER_THREAD;
    }
}
