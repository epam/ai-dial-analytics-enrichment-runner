package com.epam.aidial.enrichment.web.dto.dryrun;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

import java.util.List;

/** Body of {@code POST /v1/pipelines/{name}/try}: ADAS's preview of the sample, and what the model made of it. */
public record DryRunResponse(
        @Schema(description = "ADAS's `POST /v1/pipelines/{name}/preview` response for the same body, unchanged")
        JsonNode preview,

        @Schema(description = "One entry per evaluated row, or one for the group; empty when the sample was empty")
        List<DryRunEvaluationDto> evaluations) {
}
