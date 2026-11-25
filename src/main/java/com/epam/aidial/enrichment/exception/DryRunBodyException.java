package com.epam.aidial.enrichment.exception;

/**
 * A dry-run body that is not a JSON object of the members it may carry. Mapped to HTTP 400 {@code bad_request} with a
 * fixed message that names the members allowed and echoes nothing the caller sent.
 */
public class DryRunBodyException extends RuntimeException {

    public DryRunBodyException() {
        super("a dry-run body is a JSON object carrying only `group_key` (string), `from` and `to` (instants) and "
                + "`limit` (integer)");
    }
}
