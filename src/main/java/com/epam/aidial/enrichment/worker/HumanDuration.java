package com.epam.aidial.enrichment.worker;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Renders a {@link Duration} the way an operator reads it, for log lines.
 *
 * <p>{@code Duration.toString()} emits ISO-8601 — {@code PT12.517S}, {@code PT2M42.531S} — which is precise
 * and unreadable at a glance: the retry delays this exists for come out of {@link RetryBackoff}'s jittered
 * doubling, so they are arbitrary sub-second-precision values that a reader has to decode before they can
 * answer the only question they had, "how long until this runs again?".
 *
 * <p>Rounded to a tenth of a second below a minute and to whole seconds above it. A retry delay is a
 * scheduling hint with jitter already baked in, so trailing milliseconds carry no information worth the
 * width; anyone who needs the exact instant has the rescheduled execution time in the database.
 */
public final class HumanDuration {

    private HumanDuration() {
    }

    /** Formats as {@code 850ms}, {@code 12.5s}, {@code 2m 42s} or {@code 1h 5m 3s}. */
    public static String format(Duration duration) {
        if (duration == null) {
            return "unknown";
        }
        if (duration.isNegative()) {
            return "-" + format(duration.negated());
        }
        long millis = duration.toMillis();
        if (millis < 1_000) {
            return millis + "ms";
        }
        if (millis < TimeUnit.MINUTES.toMillis(1)) {
            // One decimal, so 12_517ms reads as 12.5s rather than 12s or 12.517s.
            return String.valueOf(millis / 1_000) + '.' + millis % 1_000 / 100 + 's';
        }

        long totalSeconds = Math.round(millis / 1_000.0);
        long hours = totalSeconds / 3_600;
        long minutes = totalSeconds % 3_600 / 60;
        long seconds = totalSeconds % 60;

        // A zero component is noise once a larger one carries the magnitude: "2m", not "2m 0s"; "1h", not
        // "1h 0m 0s". The minutes stay when there are no hours, so the smallest form here is always minutes.
        StringBuilder text = new StringBuilder(12);
        if (hours > 0) {
            text.append(hours).append('h');
        }
        if (minutes > 0 || hours == 0) {
            append(text, minutes, 'm');
        }
        if (seconds > 0) {
            append(text, seconds, 's');
        }
        return text.toString();
    }

    private static void append(StringBuilder text, long value, char unit) {
        if (!text.isEmpty()) {
            text.append(' ');
        }
        text.append(value).append(unit);
    }
}
