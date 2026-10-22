package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.worker.ChunkPayload;

import java.util.List;

/**
 * The result of turning one scanned page into work: the chunk work items to enqueue, the input-map DLQ items
 * to record, and the page's row accounting — its raw row count (for the DLQ circuit breaker's fraction)
 * together with what became of every row.
 *
 * <p>The accounting travels on this record rather than beside it so {@link ScanPageCommitter} can take one
 * object and be unable to compare one page's counts against another page's rows.
 */
public record ChunkBatch(List<ChunkPayload> chunks, List<DlqItemEntity> dlqItems, PageAccounting accounting) {

    /** Rows in the page before chunking — the circuit breaker's denominator. */
    public int rowCount() {
        return accounting.rowCount();
    }
}
