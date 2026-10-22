package com.epam.aidial.enrichment.exception;

/** A backfill request conflicts with the run's current state (e.g. resuming a non-paused run, or
 * starting a second backfill for a pipeline that already has one running). Mapped to {@code 409}. */
public class BackfillConflictException extends RuntimeException {

    public BackfillConflictException(String message) {
        super(message);
    }
}
