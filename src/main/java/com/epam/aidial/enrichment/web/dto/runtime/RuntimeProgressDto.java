package com.epam.aidial.enrichment.web.dto.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * How far the pipeline's scan is behind its input. For the group lane this is the dirty-mark scan, not evaluation.
 * The scan advances when work is enqueued, so a lag of zero does not mean the rows are written: read it with
 * {@code queue}.
 */
@Schema(description = "The scan's position relative to its input")
public record RuntimeProgressDto(
        @JsonProperty("lag_seconds") Long lagSeconds,
        @Schema(description = "Whether the last fire's last page or window reported more input")
        @JsonProperty("has_more") Boolean hasMore,
        @Schema(description = "When a fire last ended with nothing more to read")
        @JsonProperty("caught_up_at") Instant caughtUpAt,
        @Schema(description = "When the pipeline last wrote rows, a backfill run's writes included")
        @JsonProperty("last_write_at") Instant lastWriteAt) {
}
