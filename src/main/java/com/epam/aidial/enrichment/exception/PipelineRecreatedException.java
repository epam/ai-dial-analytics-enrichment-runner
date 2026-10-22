package com.epam.aidial.enrichment.exception;

import java.time.Instant;

/**
 * A cursor commit refused because the pipeline it scanned for was deleted and recreated under the same name while
 * the tick ran: the cursor row now belongs to the new pipeline, which the sync has just reset. Thrown before the
 * commit writes anything, inside its transaction; the executor ends the fire quietly, and the next one starts from the
 * reset state.
 */
public class PipelineRecreatedException extends RuntimeException {

    public PipelineRecreatedException(String pipelineName, Instant scannedFor, Instant owner) {
        super("pipeline '" + pipelineName + "' was recreated while the tick ran (scanned for created_at " + scannedFor
                + ", cursor now belongs to created_at " + owner + ")");
    }
}
