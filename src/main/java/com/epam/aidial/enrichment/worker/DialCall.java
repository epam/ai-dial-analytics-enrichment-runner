package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.TransformDto;
import com.epam.aidial.enrichment.adas.dto.TransformPreset;

import java.util.List;
import java.util.Map;

/**
 * How one {@code transform.preset} calls DIAL: the body it sends, the call it makes, and how it reads the output
 * out of the reply. {@link TransformInvoker} owns everything the presets share (rendering, rate limiting, the span,
 * the timer, validation and output mapping) and asks this for the three steps that differ, so neither preset can
 * drift from the other on the shared ones.
 */
interface DialCall {

    /** The preset this call serves. */
    TransformPreset preset();

    /** The request body: the rendered template with whatever this preset adds to it. */
    String body(String renderedTemplate, TransformDto transform, Map<String, Object> responseSchema);

    /** Makes the call and returns the raw reply body; a non-2xx status surfaces as a {@code RestClientResponseException}. */
    String call(TransformDto transform, String body);

    /**
     * Reads the output out of a raw reply.
     *
     * @throws com.epam.aidial.enrichment.exception.ResponseValidationException when the reply cannot yield an output
     *     at all, carrying the raw reply, so the unit is dead-lettered at {@code validate} without a second call
     */
    Reply read(PipelineDto pipeline, TransformDto transform, String raw);

    /** Whether a schema mismatch may be repaired by re-asking with a corrective message. */
    boolean repairs();

    /** Appends a corrective message to a body; called only when {@link #repairs()}. */
    String withCorrection(String body, List<String> errors);

    /**
     * What one call produced.
     *
     * @param output     the output as JSON text, validated against the response schema; null when the reply had none
     * @param evidence   what a dead-letter item carries when the output fails validation
     * @param usage      the usage amount recorded in the spend counter
     * @param usageUnit  the unit of {@code usage}
     */
    record Reply(String output, String evidence, long usage, String usageUnit) {
    }
}
