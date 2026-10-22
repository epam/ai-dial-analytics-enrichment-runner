package com.epam.aidial.enrichment.web.dto.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What one cached pipeline is doing right now, in one shape for every lane.
 *
 * <p>A section that does not apply to the pipeline's lane is absent. Inside a present section, a field the runner
 * does not currently know (for example after a restart) is absent too, because the runner omits every null property.
 */
@Schema(description = "A pipeline's runtime view; sections that do not apply to its lane are absent")
public record PipelineRuntimeDto(
        @JsonProperty("pipeline_name") String pipelineName,
        @Schema(allowableValues = {"row", "group", "class1"})
        String lane,
        long generation,
        RuntimeStatusDto status,
        RuntimeScheduleDto schedule,
        RuntimeProgressDto progress,
        @Schema(description = "Row and group lanes only")
        RuntimeQueueDto queue,
        @Schema(description = "Group lane only")
        RuntimeGroupsDto groups,
        @Schema(description = "Row and group lanes only")
        @JsonProperty("spend_today") RuntimeSpendDto spendToday,
        @Schema(description = "Row and group lanes only: the live lane's dead letters")
        RuntimeFailuresDto failures) {
}
