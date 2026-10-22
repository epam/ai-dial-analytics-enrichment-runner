package com.epam.aidial.enrichment.exception;

/** A backfill window the runner rejects before creating a run — currently one whose bounds are inverted. */
public class BackfillWindowException extends RuntimeException {

    public BackfillWindowException(String message) {
        super(message);
    }
}
