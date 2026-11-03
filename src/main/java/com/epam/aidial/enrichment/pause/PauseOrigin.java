package com.epam.aidial.enrichment.pause;

/**
 * Who paused a pipeline. Only the DLQ circuit breaker does: an operator stops a pipeline through ADAS's
 * {@code enabled} flag, its single run control, which the runner follows through the sync feed rather than
 * holding a second switch of its own.
 *
 * <p>Kept as a type, and reported as the paused listing's {@code origin}, because the breaker's pause is an
 * <em>inference</em> the runner drew from a failure rate about something outside it — which is why it expires —
 * and a reader of the listing must be able to tell that apart from a stop somebody chose.
 */
public enum PauseOrigin {

    /** The DLQ circuit breaker auto-paused the pipeline. Expires; see {@link PipelinePauseRegistry}. */
    BREAKER
}
