package com.epam.aidial.enrichment.dlq;

import com.epam.aidial.enrichment.exception.UnknownEnumValueException;

import java.util.Arrays;
import java.util.Locale;

/**
 * Which path produced a dead letter: the live path, or a backfill run. Derived from {@code dlq_item.run_id} —
 * null is live — and used only to narrow the listing, so the live failures an operator must act on now can be read
 * without a replay's historical noise, by someone who holds no run ids.
 */
public enum DlqLane {
    LIVE,
    BACKFILL;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses the wire spelling, case-insensitively. */
    public static DlqLane fromDbValue(String value) {
        for (DlqLane lane : values()) {
            if (lane.dbValue().equalsIgnoreCase(value)) {
                return lane;
            }
        }
        throw new UnknownEnumValueException(Arrays.stream(values()).map(DlqLane::dbValue).toList());
    }
}
