package com.epam.aidial.enrichment.scan.group;

import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.GroupStateEntity;
import com.epam.aidial.enrichment.scan.PageAccounting;

import java.util.List;

/**
 * What one scanned page of a group pipeline turns into: the per-group marks to upsert, the rows that could
 * not be marked (dead-lettered rather than dropped), and the page's row accounting — its raw row count for
 * the circuit breaker's failure fraction together with what became of every row. The group-grain
 * counterpart of {@link com.epam.aidial.enrichment.scan.ChunkBatch}.
 *
 * <p>The accounting counts <em>rows</em>, not marks: many rows collapse into one group mark, and what the
 * cursor is about to advance past is the rows.
 *
 * @param marks      one entry per distinct group key seen, carrying the maximum version observed for it
 * @param dlqItems   rows whose version could not be interpreted
 * @param accounting what became of every row the page read, and how many there were
 */
public record GroupMarkBatch(
        List<GroupStateEntity> marks,
        List<DlqItemEntity> dlqItems,
        PageAccounting accounting) {

    /** Rows in the page before grouping — the circuit breaker's denominator. */
    public int rowCount() {
        return accounting.rowCount();
    }
}
