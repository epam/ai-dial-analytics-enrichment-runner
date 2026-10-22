package com.epam.aidial.enrichment.worker;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The retry backoff both worker failure handlers use: exponential in the attempt count, with jitter so a
 * batch of items that failed together does not retry in lockstep and re-create the burst that failed them.
 *
 * <p>Shared rather than per-handler so a row chunk and a group back off identically — they are retrying the
 * same downstream services for the same reasons.
 */
public final class RetryBackoff {

    /**
     * Ceiling on the computed delay. Doubling is unbounded in the attempt count, and a shift cap alone is not a
     * bound on the <em>delay</em>: at the default 10s base, shifting even 13 times puts the next attempt a day
     * out, which is indistinguishable from the item having been dropped.
     */
    private static final Duration MAX_DELAY = Duration.ofHours(1);

    private RetryBackoff() {
    }

    public static Duration withJitter(Duration base, int failures) {
        long baseMillis = base.toMillis();
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, baseMillis));
        // Computed in double to sidestep the shift's overflow entirely, then clamped — a delay that overflowed
        // to a negative would reschedule the item into the past and spin it.
        double scaled = baseMillis * Math.pow(2, Math.max(0, failures));
        double capped = Math.min(scaled + jitter, MAX_DELAY.toMillis());
        return Duration.ofMillis((long) capped);
    }
}
