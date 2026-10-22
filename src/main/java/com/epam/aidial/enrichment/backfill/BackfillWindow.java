package com.epam.aidial.enrichment.backfill;

import com.epam.aidial.enrichment.exception.BackfillWindowException;

import java.time.Instant;

/**
 * The version range a backfill covers, or {@link #UNBOUNDED} for a full replay.
 *
 * <p>One value rather than two nullable fields threaded through every layer, so "is this bounded?" is a question
 * with one answer and the validation lives in one place. The semantics are ADAS's — half-open
 * {@code (from, to]} on the source's version column, clamped by ADAS's safety-lag horizon — because both the
 * scan and materialize endpoints already take exactly this shape, and inventing a different one would only need
 * translating.
 *
 * @param from exclusive lower bound, or null for "from the source start"
 * @param to   inclusive upper bound, or null for "up to the safety-lag horizon"
 */
public record BackfillWindow(Instant from, Instant to) {

    /** A full replay: the behaviour of every backfill before windows existed, and still the default. */
    public static final BackfillWindow UNBOUNDED = new BackfillWindow(null, null);

    public BackfillWindow {
        if (from != null && to != null && !from.isBefore(to)) {
            // Rejected here rather than at ADAS: waiting for ADAS's 400 would create a run_history row for a
            // run that could only ever fail on its first batch, putting the error far from the mistake.
            throw new BackfillWindowException(
                    "backfill window 'from' (" + from + ") must be strictly before 'to' (" + to + ")");
        }
    }

    /** Null-safe: an absent window is {@link #UNBOUNDED}. */
    public static BackfillWindow of(Instant from, Instant to) {
        return from == null && to == null ? UNBOUNDED : new BackfillWindow(from, to);
    }
}
