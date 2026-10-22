package com.epam.aidial.enrichment.persistence;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * The encoding shared by the listings' keyset cursors: unpadded base64url around a {@code :}-separated payload, and a
 * timestamp written as {@code <epochSecond>.<nanos>} so it round-trips at PostgreSQL's precision and beyond.
 *
 * <p>Every decoding failure is an {@link IllegalArgumentException}, which each cursor turns into its own
 * {@code invalid_cursor} refusal.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class KeysetCursors {

    private static final int MAX_NANOS = 999_999_999;
    /** Years 0001 through 9999: far wider than any stored timestamp, well inside PostgreSQL's range. */
    private static final long MIN_EPOCH_SECOND = Instant.parse("0001-01-01T00:00:00Z").getEpochSecond();
    private static final long MAX_EPOCH_SECOND = Instant.parse("9999-12-31T23:59:59Z").getEpochSecond();

    public static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String cursor) {
        return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    }

    public static String formatInstant(Instant instant) {
        return instant.getEpochSecond() + "." + instant.getNano();
    }

    /**
     * Parses only what {@link #formatInstant} can emit: anything else would be normalised by {@link Instant}, or reach
     * PostgreSQL out of its {@code timestamptz} range and fail there as a 5xx instead of a 400.
     */
    public static Instant parseInstant(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected <epochSecond>.<nanos>");
        }
        long epochSecond = Long.parseLong(parts[0]);
        int nanos = Integer.parseInt(parts[1]);
        if (nanos < 0 || nanos > MAX_NANOS || epochSecond < MIN_EPOCH_SECOND || epochSecond > MAX_EPOCH_SECOND) {
            throw new IllegalArgumentException("Timestamp out of range");
        }
        return Instant.ofEpochSecond(epochSecond, nanos);
    }
}
