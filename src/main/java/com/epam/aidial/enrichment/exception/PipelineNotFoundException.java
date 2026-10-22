package com.epam.aidial.enrichment.exception;

/** A pipeline name is not present in the runner's synced cache (unknown, disabled, or not yet synced).
 * Mapped to {@code 404}. */
public class PipelineNotFoundException extends RuntimeException {

    public PipelineNotFoundException(String pipelineName) {
        super("Pipeline not found or not enabled: " + pipelineName);
    }
}
