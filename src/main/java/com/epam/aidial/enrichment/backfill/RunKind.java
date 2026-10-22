package com.epam.aidial.enrichment.backfill;

import java.util.Locale;

/** The kind of run tracked in {@code run_history}. The database stores the lowercase form. */
public enum RunKind {
    LIVE,
    BACKFILL,
    SCHEDULE;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
