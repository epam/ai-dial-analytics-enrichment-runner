package com.epam.aidial.enrichment.exception;

/** A backfill run id was not found. */
public class BackfillRunNotFoundException extends RuntimeException {

    public BackfillRunNotFoundException(String runId) {
        super("Backfill run not found: " + runId);
    }
}
