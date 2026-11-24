package com.epam.aidial.enrichment.exception;

/**
 * The declaration a dry run was handed is one this runner's pipeline sync would refuse to admit, so no call is made
 * for it. Mapped to HTTP 422 {@code pipeline_not_runnable}, naming the problem as the sync's admission does.
 */
public class PipelineNotRunnableException extends RuntimeException {

    public PipelineNotRunnableException(String pipelineName, String problem) {
        super("pipeline '" + pipelineName + "' cannot be run by this runner: " + problem);
    }
}
