package com.epam.aidial.enrichment.web.dto.admin;

import java.util.List;

/** Envelope for the pipeline-cache introspection listing. */
public record PipelineCacheResponse(List<PipelineCacheEntryDto> pipelines) {
}
