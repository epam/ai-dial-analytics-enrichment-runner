package com.epam.aidial.enrichment.exception;

/**
 * A dry run found no rate-limit permit free for its pipeline. Refused rather than queued, so an author's click
 * never waits behind, or takes a permit from, the pipeline's own work. Mapped to HTTP 429 {@code dial_rate_limited}.
 */
public class DialRateLimitedException extends RuntimeException {

    public DialRateLimitedException(String pipelineName) {
        super("pipeline '" + pipelineName + "' has no DIAL rate-limit permit free right now; try again shortly");
    }
}
