package com.epam.aidial.enrichment.adas.dto;

import java.util.List;

/**
 * The envelope {@code GET /v1/pipelines} returns. The listing is an object rather than a bare array, so the
 * feed can gain members later without breaking a consumer that parses it — which is exactly why the runner
 * must not accept a bare array as an equivalent body: a parse that silently yielded zero pipelines would be
 * reconciled as "every pipeline was disabled" and cancel every recurring task.
 */
public record PipelineListDto(List<PipelineDto> pipelines) {
}
