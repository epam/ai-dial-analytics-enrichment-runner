package com.epam.aidial.enrichment.pause;

/**
 * Who paused a pipeline — and therefore whether the pause may expire.
 *
 * <p>The two are different in kind, not in degree. {@link #BREAKER} is an <em>inference</em> the runner
 * drew from a failure rate about something outside it, and most of what produces that inference — an
 * upstream outage, a refusing model endpoint — clears without anyone touching the runner; an inference that
 * cannot expire records a temporary condition as a permanent one and turns a bounded outage into an
 * unbounded silent stop. {@link #OPERATOR} is a decision taken with knowledge the runner does not have, and
 * must survive anything the runner concludes.
 *
 * <p>Storing both in one undifferentiated set is what made expiring either of them unsafe, which is why
 * neither expired.
 */
public enum PauseOrigin {

    /** An operator paused the pipeline deliberately. Never expires. */
    OPERATOR,

    /** The DLQ circuit breaker auto-paused the pipeline. Expires; see {@link PipelinePauseRegistry}. */
    BREAKER
}
