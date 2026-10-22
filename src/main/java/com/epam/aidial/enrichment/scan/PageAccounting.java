package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.observability.RunnerMetrics;

import java.util.Map;

/**
 * What one scanned page did with every row it read: how many became work, how many were dead-lettered, and
 * how many were skipped for each declared reason — alongside the page's own row count, so the counts and
 * the number they must add up to come from the same object and cannot be handed to a committer from
 * different pages.
 *
 * <p>Counted in <em>rows</em> throughout, including on the group lane where many rows collapse into one
 * mark: the invariant is about the rows the cursor is about to advance past, not about the units of work
 * they produced.
 *
 * @param rowCount        rows the page read
 * @param workRows        rows that contributed to a chunk (row lane) or a group mark (group lane)
 * @param deadLetteredRows rows recorded as dead-letter items
 * @param skippedRows     rows deliberately excluded, by reason
 */
public record PageAccounting(
        int rowCount,
        int workRows,
        int deadLetteredRows,
        Map<RunnerMetrics.SkipReason, Integer> skippedRows) {

    /** Rows this page routed to exactly one outcome. */
    public int accountedRows() {
        int skipped = 0;
        for (int count : skippedRows.values()) {
            skipped += count;
        }
        return workRows + deadLetteredRows + skipped;
    }

    /**
     * Whether every row the page read reached an outcome. An empty page satisfies this trivially, which is
     * the intent: a scan that returned no rows commits exactly as it always has.
     */
    public boolean coversEveryRow() {
        return accountedRows() == rowCount;
    }
}
