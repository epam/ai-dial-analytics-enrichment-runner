package com.epam.aidial.enrichment.web.dto.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** When the pipeline last scanned and next fires, and how its last fires ended. */
@Schema(description = "The pipeline's recurring fire")
public record RuntimeScheduleDto(
        @Schema(description = "When a live fire last committed a page or window, an empty one included")
        @JsonProperty("last_scan_at") Instant lastScanAt,
        @Schema(description = "When the next fire is due; absent while a fire is running")
        @JsonProperty("next_run_at") Instant nextRunAt,
        @JsonProperty("running_now") Boolean runningNow,
        @Schema(description = "Consecutive fires that failed; a fire skipped by a gate resets it to zero")
        @JsonProperty("consecutive_failures") Integer consecutiveFailures,
        @Schema(description = "Classified reason the last fire failed; absent once a fire succeeds, and unknown after a restart")
        @JsonProperty("last_error") String lastError,
        @JsonProperty("last_error_at") Instant lastErrorAt) {
}
