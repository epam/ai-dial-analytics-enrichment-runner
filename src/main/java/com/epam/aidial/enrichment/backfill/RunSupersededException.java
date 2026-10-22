package com.epam.aidial.enrichment.backfill;

/**
 * A backfill page's commit found the run no longer running, or its cursor moved since the page was read: an
 * operator cancelled or paused the run, or another execution of the same run advanced it first.
 *
 * <p>Thrown inside the page transaction so the page's enqueued work, marks and dead letters roll back with it.
 * Not a failure: the executor catches it and stops without chaining a successor, and the batch failure handler
 * never sees it. The later decision — the cancel, or the execution that committed first — stands.
 */
public class RunSupersededException extends RuntimeException {

    public RunSupersededException(String runId) {
        super("Backfill " + runId + " was cancelled, paused or advanced by another execution; discarding this page");
    }
}
