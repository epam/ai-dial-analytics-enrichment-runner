package com.epam.aidial.enrichment.exception;

/**
 * An early resume named a pipeline the DLQ circuit breaker has not paused. Mapped to {@code 409
 * pipeline_not_paused} rather than answered with success: the endpoint lifts a breaker pause and nothing else,
 * and a silent 204 would hide a caller still using it as half of a stop/start pair — a stop is ADAS's
 * {@code enabled} flag.
 */
public class PipelineNotPausedException extends RuntimeException {

    public PipelineNotPausedException(String pipelineName) {
        super("pipeline '" + pipelineName + "' is not paused by the DLQ circuit breaker; to stop or start a "
                + "pipeline, set its enabled flag in ADAS");
    }
}
