package com.epam.aidial.enrichment.web.dto.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** The pipeline's queued work items: the ones backpressure counts. */
@Schema(description = "Queued work items; computing plus awaiting_write is the depth backpressure compares")
public record RuntimeQueueDto(
        @Schema(description = "Items waiting for, or in, the model call")
        long computing,
        @Schema(description = "Computed items waiting to be written")
        @JsonProperty("awaiting_write") long awaitingWrite) {
}
