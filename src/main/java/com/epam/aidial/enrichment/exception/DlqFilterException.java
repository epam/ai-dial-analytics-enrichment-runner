package com.epam.aidial.enrichment.exception;

/**
 * A dead-letter listing or requeue whose filters cannot select anything meaningful: a live lane together with a
 * run, or a bulk requeue with neither a pipeline nor a run. Mapped to {@code 400 bad_request}.
 *
 * <p>Refused rather than answered with an empty result, because an empty failure listing reads as "nothing
 * failed". A dedicated type rather than a {@code ResponseStatusException}, which the catch-all handler would turn
 * into a {@code 500}.
 */
public class DlqFilterException extends RuntimeException {

    public DlqFilterException(String message) {
        super(message);
    }
}
