package com.epam.aidial.enrichment.exception;

/**
 * A scanned page's rows do not <em>carry</em> a column the lane dereferences — the pipeline's
 * {@code grain_key}, a group pipeline's {@code trigger.group_by}, or the {@code version_column} the group
 * lane reads alongside it.
 *
 * <p>Structural absence, deliberately distinguished from a null value. {@code row.get(column)} answering
 * null conflates two facts: the column is not in the page, and the column is in the page holding null. The
 * first means <em>this page is not the page this lane asked for</em> — a projection skew, which fails the
 * tick and holds the cursor so the next cadence retries. The second is data, and keeps whatever outcome
 * that column already has: a counted skip for a grain or group key, a dead letter for a version.
 *
 * <p>The page's rows are explicitly <b>not</b> dead-lettered. At 1000 rows a page and 50 pages a fire, a
 * skewed projection would flood the DLQ with tens of thousands of items that are not row defects and whose
 * recovery is a bulk requeue — which is exactly what the group lane's version column did before this
 * existed.
 */
public class ScanPageColumnException extends RuntimeException {

    public ScanPageColumnException(String pipelineName, String column, String role) {
        super("Scan page for pipeline " + pipelineName + " carries no " + role + " column '" + column
                + "'; refusing the page and holding the cursor rather than skipping its rows");
    }
}
