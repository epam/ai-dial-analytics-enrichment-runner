package com.epam.aidial.enrichment.exception;

/**
 * A per-row output-mapping failure: an output variable's JSONata could not be evaluated over the
 * model response. Routed to the DLQ at stage {@code output_map}; the offending row is isolated while
 * the rest of the chunk proceeds.
 */
public class OutputMappingException extends RuntimeException {

    public OutputMappingException(String var, Throwable cause) {
        super("output mapping failed for variable '" + var + "'", cause);
    }
}
