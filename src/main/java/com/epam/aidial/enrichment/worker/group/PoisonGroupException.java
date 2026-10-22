package com.epam.aidial.enrichment.worker.group;

import com.epam.aidial.enrichment.dlq.DlqStage;

import java.io.Serial;

/**
 * A group failure that replay cannot fix: an un-mappable member, an output the pipeline's mapping cannot
 * read, a response that still fails its schema after repair, or a rejection ADAS will keep rejecting.
 *
 * <p>Distinct from {@link com.epam.aidial.enrichment.exception.TransientChunkException} because the two get
 * opposite treatment — a transient failure is retried with backoff, a poison group is dead-lettered and the
 * execution completes. It carries the stage and raw response so the dead-letter row is attributable.
 */
public class PoisonGroupException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient DlqStage stage;
    private final String rawResponse;

    public PoisonGroupException(DlqStage stage, String message, String rawResponse) {
        super(message);
        this.stage = stage;
        this.rawResponse = rawResponse;
    }

    public DlqStage stage() {
        return stage;
    }

    public String rawResponse() {
        return rawResponse;
    }
}
