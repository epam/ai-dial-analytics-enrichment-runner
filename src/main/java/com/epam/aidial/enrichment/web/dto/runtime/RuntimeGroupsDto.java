package com.epam.aidial.enrichment.web.dto.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** A group pipeline's groups, counted with the sweep's own predicates. */
@Schema(description = "Group readiness; pending = ready + ceiling_blocked + waiting_idle")
public record RuntimeGroupsDto(
        @Schema(description = "Groups never evaluated or changed since their last evaluation")
        long pending,
        @Schema(description = "Pending groups that are ready and within the cost ceiling, including any already queued")
        long ready,
        @Schema(description = "Pending groups not yet ready")
        @JsonProperty("waiting_idle") long waitingIdle,
        @Schema(description = "Pending groups that are ready but at their daily cost ceiling")
        @JsonProperty("ceiling_blocked") long ceilingBlocked) {
}
