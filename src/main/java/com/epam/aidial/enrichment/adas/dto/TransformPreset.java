package com.epam.aidial.enrichment.adas.dto;

/**
 * The request-shaping preset a transform uses, mirroring ADAS's {@code transform.preset}. The runner supports
 * only chat completion. Renamed with {@link TransformType} by the evaluator fold: the vocabulary did not
 * change, only the block that carries it.
 */
public enum TransformPreset {
    CHAT_COMPLETION
}
