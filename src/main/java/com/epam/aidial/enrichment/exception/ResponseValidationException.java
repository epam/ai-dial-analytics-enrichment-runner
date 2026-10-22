package com.epam.aidial.enrichment.exception;

import lombok.Getter;

/**
 * A per-row model-output validation failure that survived the repair retry. Carries the raw model
 * response so it can be archived with the DLQ item at stage {@code validate}.
 */
@Getter
public class ResponseValidationException extends RuntimeException {

    private final transient String rawResponse;

    public ResponseValidationException(String message, String rawResponse) {
        super(message);
        this.rawResponse = rawResponse;
    }
}
