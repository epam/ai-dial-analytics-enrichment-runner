package com.epam.aidial.enrichment.web.dto.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** The pipeline's live dead letters, counted as the DLQ listing counts them. */
@Schema(description = "Live dead letters; total and requeueable_total equal GET /v1/dlq for this pipeline and lane live")
public record RuntimeFailuresDto(
        long total,
        @JsonProperty("requeueable_total") long requeueableTotal) {
}
