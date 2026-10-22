package com.epam.aidial.enrichment.web.dto.admin;

import java.util.List;

/** The currently-paused pipelines, each with its origin, reason and timing. */
public record PausedPipelinesResponse(List<PausedPipelineDto> paused) {
}
