package com.epam.aidial.enrichment.exception;

/**
 * A DLQ item cannot be requeued. Either it carries no re-runnable work-item payload (a per-row poison row,
 * whose source row is not reconstructible), or the payload it carries was serialized before ADAS's evaluator
 * fold and describes a work item this build does not read.
 */
public class DlqNotRequeueableException extends RuntimeException {

    public DlqNotRequeueableException(long id) {
        super("DLQ item is not requeueable (no payload): " + id);
    }

    public DlqNotRequeueableException(long id, String reason) {
        super("DLQ item " + id + " is not requeueable: " + reason);
    }
}
