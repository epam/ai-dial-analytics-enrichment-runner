package com.epam.aidial.enrichment.exception;

/** A dry run of this pipeline is already running. Mapped to HTTP 409 {@code dry_run_in_progress}. */
public class DryRunInProgressException extends RuntimeException {

    public DryRunInProgressException(String pipelineName) {
        super("a dry run of pipeline '" + pipelineName + "' is already running; wait for it to answer");
    }
}
