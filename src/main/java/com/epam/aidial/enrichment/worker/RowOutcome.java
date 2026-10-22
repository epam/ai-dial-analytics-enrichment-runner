package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.persistence.DlqItemEntity;

import java.util.Map;

/**
 * The result of processing one chunk row: either a row to upsert or a per-row DLQ item. Exactly one
 * of the two is non-null. Transient/infrastructure failures are not represented here — they are
 * thrown so the whole chunk retries.
 */
record RowOutcome(Map<String, Object> writeRow, DlqItemEntity dlqItem) {

    static RowOutcome write(Map<String, Object> writeRow) {
        return new RowOutcome(writeRow, null);
    }

    static RowOutcome dead(DlqItemEntity dlqItem) {
        return new RowOutcome(null, dlqItem);
    }
}
