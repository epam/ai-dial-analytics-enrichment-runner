package com.epam.aidial.enrichment.exception;

import com.epam.aidial.enrichment.dlq.DlqStage;
import lombok.Getter;

/**
 * A transient chunk-level failure (DIAL 429/5xx, rate-limit exhaustion, ADAS upsert failure) that
 * should retry the whole chunk. Carries the pipeline {@link DlqStage} so the failure handler can
 * tag the archived item correctly if retries are exhausted.
 */
@Getter
public class TransientChunkException extends RuntimeException {

    private final transient DlqStage stage;

    public TransientChunkException(DlqStage stage, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
    }

    public TransientChunkException(DlqStage stage, Throwable cause) {
        super(cause.getMessage(), cause);
        this.stage = stage;
    }
}
