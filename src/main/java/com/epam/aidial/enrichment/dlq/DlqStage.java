package com.epam.aidial.enrichment.dlq;

import java.util.Locale;

/**
 * The pipeline stage a dead-lettered item failed at. The database stores the lowercase form (matching
 * the {@code dlq_item.stage} CHECK constraint), produced by {@link #dbValue()}.
 */
public enum DlqStage {

    /** Row grain: the row's input bindings could not be evaluated. */
    INPUT_MAP,

    /** The model call failed non-retryably. */
    DIAL_CALL,

    /** The response still failed its schema after the repair budget. */
    VALIDATE,

    /** Row grain: the validated response could not be mapped to columns. */
    OUTPUT_MAP,

    /** The write-back to ADAS failed. */
    UPSERT,

    /** Group grain: ADAS rejected the group assembly in a way replay cannot fix. */
    GROUP_FETCH,

    /** Group grain: the group's members could not be assembled, or its response could not be mapped. */
    GROUP_MAP;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
