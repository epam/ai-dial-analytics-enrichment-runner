package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.observability.RunnerMetrics;

import java.util.EnumMap;
import java.util.Map;

/**
 * Tallies one page's row outcomes as its builder walks it, then seals them into a {@link PageAccounting}.
 *
 * <p>Shared by the row and group builders so both lanes account the same way and the committers can apply
 * one rule to either. Single-threaded by construction — a page is built on the tick's own thread — so it is
 * a plain counter rather than anything concurrent.
 */
public final class PageAccountant {

    private final Map<RunnerMetrics.SkipReason, Integer> skips = new EnumMap<>(RunnerMetrics.SkipReason.class);
    private int workRows;
    private int deadLetteredRows;

    /** A row that contributed to a chunk or to a group mark. */
    public void countWork() {
        workRows++;
    }

    /** A row recorded as a dead-letter item. */
    public void countDeadLetter() {
        deadLetteredRows++;
    }

    /** A row deliberately excluded — sampled out, or carrying a null in a required column. */
    public void countSkip(RunnerMetrics.SkipReason reason) {
        skips.merge(reason, 1, Integer::sum);
    }

    /**
     * Seals the tally against the page's row count. The count is supplied rather than accumulated so the
     * accounting is checked against what the page actually <em>read</em>, not against the rows the loop
     * happened to reach — which is the bug class the equality test exists to catch.
     */
    public PageAccounting accountFor(int rowCount) {
        return new PageAccounting(rowCount, workRows, deadLetteredRows, Map.copyOf(skips));
    }
}
