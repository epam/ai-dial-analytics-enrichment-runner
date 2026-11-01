package com.epam.aidial.enrichment.exception;

/**
 * A work item belongs to a pipeline that is <b>stopped</b> — disabled in ADAS, whose {@code enabled} flag is the
 * pipeline's single run control. Held rather than dropped or archived: the scan cursor advanced when a chunk was
 * enqueued, so a dropped chunk is a set of rows nothing revisits, and an archived write is a paid result left for
 * a manual requeue. The condition clears when the pipeline is re-enabled, and the item then runs as it would have.
 *
 * <p>Raised in two places: when the cached pipeline is stopped, and when ADAS refuses a write with HTTP 409
 * {@code pipeline_conflict} — which on the pipeline-scoped write paths means only that the pipeline is disabled,
 * a stop that reached ADAS before this runner's next sync did.
 */
public class PipelineStoppedHoldException extends HeldWorkException {

    public PipelineStoppedHoldException(String what) {
        super("Holding " + what + ": the pipeline is stopped (disabled in ADAS) until it is re-enabled");
    }
}
