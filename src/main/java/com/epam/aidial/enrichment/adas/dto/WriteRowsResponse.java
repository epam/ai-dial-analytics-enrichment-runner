package com.epam.aidial.enrichment.adas.dto;

/**
 * Response of the pipeline-scoped write-back {@code POST /v1/pipelines/{name}/rows}.
 *
 * @param inserted number of rows inserted
 */
public record WriteRowsResponse(int inserted) {
}
