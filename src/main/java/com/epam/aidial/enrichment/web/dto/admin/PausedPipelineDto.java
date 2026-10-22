package com.epam.aidial.enrichment.web.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One paused pipeline, as the ops listing reports it.
 *
 * <p>A listing of bare names cannot answer the question it is opened to answer. A list of one name tells
 * an operator only what they already suspected, and nothing about whether the pause was a decision somebody
 * had taken or a circuit breaker that had failed to let go. Those two call for opposite responses, and
 * {@code origin} with {@code since} is what separates them.
 *
 * <p>Snake_case like every other {@code /v1} response, and like the runtime view's {@code status}, which reports the
 * same pause.
 *
 * @param pipelineName the paused pipeline
 * @param origin       {@code operator} or {@code breaker}
 * @param reason       what was recorded when the pause was taken
 * @param since        when the pause began
 * @param resumesAt    when it lifts itself, or null for a pause that never expires
 */
@Schema(description = "A paused pipeline and why it is paused")
public record PausedPipelineDto(
        @JsonProperty("pipeline_name") String pipelineName,
        @Schema(allowableValues = {"operator", "breaker"})
        String origin,
        String reason,
        Instant since,
        @JsonProperty("resumes_at") Instant resumesAt) {
}
