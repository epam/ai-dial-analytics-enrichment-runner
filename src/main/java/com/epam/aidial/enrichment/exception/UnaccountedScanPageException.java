package com.epam.aidial.enrichment.exception;

/**
 * A page's accounted outcomes did not cover every row it read, so its cursor advance was refused.
 *
 * <p>The structural backstop, and deliberately blind to cause. Every silent-skip defect this service has
 * had reaches the same place: a page of real rows that produced no work item and no dead letter, committed
 * together with an advance that cannot be undone — "already enriched" is not a predicate anywhere, so those
 * rows are permanently unenriched. Refusing the commit converts the whole class from a permanent loss into a
 * retry, without the committer needing to know which upstream invariant broke.
 *
 * <p>The test is <b>equality</b>, not "at least one accounted". The page builders route every row they read
 * to exactly one outcome — chunked or group-marked, dead-lettered, or skipped for a declared reason — so
 * equality is what the code already satisfies, while the weaker form would leave a <em>partially</em>
 * unaccounted page (a loop that exits early, a branch that forgets to count) as invisible as the wholly
 * unaccounted one this exists to catch.
 */
public class UnaccountedScanPageException extends RuntimeException {

    public UnaccountedScanPageException(String pipelineName, int accounted, int rowCount) {
        super("Refusing to advance the cursor for pipeline " + pipelineName + ": the page read " + rowCount
                + " row(s) but only " + accounted + " were accounted for (chunked or marked, dead-lettered, "
                + "or skipped for a declared reason)");
    }
}
