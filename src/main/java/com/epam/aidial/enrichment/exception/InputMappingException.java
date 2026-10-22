package com.epam.aidial.enrichment.exception;

/**
 * A per-row input-mapping failure: a {@code bound} JSONata expression could not be evaluated over a
 * scanned row. Routed to the DLQ at stage {@code input_map} (never a silent drop); the offending row
 * is isolated while the rest of the page proceeds and the cursor still advances.
 */
public class InputMappingException extends RuntimeException {

    public InputMappingException(String var, Throwable cause) {
        super("input mapping failed for variable '" + var + "'", cause);
    }
}
