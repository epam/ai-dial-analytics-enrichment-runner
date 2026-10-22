package com.epam.aidial.enrichment.web.dto.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** The pipeline's state, decided by the same rules as the status heartbeat. */
@Schema(description = "The pipeline's state; the pause fields are present only while it is paused")
public record RuntimeStatusDto(
        @Schema(allowableValues = {"active", "paused", "held", "over_budget", "backpressured"})
        String state,
        @Schema(allowableValues = {"operator", "breaker"})
        String origin,
        String reason,
        Instant since,
        @Schema(description = "When an expiring (breaker) pause lifts itself")
        @JsonProperty("resumes_at") Instant resumesAt) {
}
