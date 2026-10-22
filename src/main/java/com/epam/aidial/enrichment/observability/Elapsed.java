package com.epam.aidial.enrichment.observability;

/** Elapsed wall time for a log line's duration field, measured on the monotonic clock. */
public final class Elapsed {

    private Elapsed() {
    }

    /** Milliseconds elapsed since a {@link System#nanoTime()} reading. */
    public static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
