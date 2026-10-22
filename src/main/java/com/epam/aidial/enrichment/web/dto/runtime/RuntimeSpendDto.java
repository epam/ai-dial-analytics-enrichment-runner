package com.epam.aidial.enrichment.web.dto.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** The pipeline's model calls for the current UTC day. */
@Schema(description = "Today's spend (UTC day)")
public record RuntimeSpendDto(
        @Schema(description = "Successful calls, which are what the call budget counts")
        long calls,
        long tokens,
        @Schema(description = "Failed calls, never charged to the budget")
        @JsonProperty("failed_calls") long failedCalls) {
}
