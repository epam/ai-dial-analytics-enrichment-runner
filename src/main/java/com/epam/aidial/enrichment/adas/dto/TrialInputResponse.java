package com.epam.aidial.enrichment.adas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * ADAS's trial-input snapshot: the preview it shows an author, the compiled declaration the dry run evaluates, and
 * the uncapped rows that declaration is evaluated on.
 *
 * @param preview   ADAS's preview for the same body, handed back to the caller unread
 * @param pipeline  the compiled declaration the sample was read through; for a disabled pipeline, as ADAS's gate
 *                  derived it rather than as stored
 * @param inputRows the newest sampled rows of a row-grain pipeline, keyed as a scan row, or a group's whole fetch
 * @param truncated whether the group fetch's caps cut the group; null for a row-grain pipeline
 */
public record TrialInputResponse(
        JsonNode preview,
        PipelineDto pipeline,
        @JsonProperty("input_rows") List<Map<String, Object>> inputRows,
        Boolean truncated) {
}
