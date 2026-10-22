package com.epam.aidial.enrichment.exception;

/**
 * A work item became due before this process had ever synced the pipeline registry, so the runner does not
 * yet know anything about any pipeline — a different fact from "this pipeline is not in the registry".
 *
 * <p>The window is real and routinely hit: the cache starts empty, the job scheduler starts polling with
 * the Spring context, and the first registry poll is a scheduled task behind it. Every work service reads a
 * cache miss as <em>disabled or deleted</em> and acts on it — the row lane drops the chunk, which is
 * irreversible because the scan cursor advanced in the same transaction that enqueued it; the group lane
 * drops a compute that then has to be re-derived; the write lane archives a paid-for result for an operator
 * to requeue by hand. None of the three is the right response to "ask me again in a moment".
 */
public class PipelineCacheColdException extends HeldWorkException {

    public PipelineCacheColdException(String what) {
        super("Holding " + what + ": the pipeline cache has not been populated yet (no registry sync has "
                + "completed in this process)");
    }
}
