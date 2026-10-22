package com.epam.aidial.enrichment.web.dto.dlq;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * A dead-lettered item as returned by the ops API. Omits the raw response and the stored payload;
 * {@code requeueable} signals whether the item carries a re-runnable work-item payload. Conversion
 * from the entity lives in {@code web/mapper/DlqItemMapper}.
 *
 * <p>{@code runId} names the backfill run that produced the item; absent means the live path.
 */
public record DlqItemDto(
        long id,
        @JsonProperty("pipeline_name") String pipelineName,
        @JsonProperty("grain_key") String grainKey,
        @JsonProperty("pipeline_generation") Long pipelineGeneration,
        String stage,
        String error,
        @JsonProperty("run_id") String runId,
        boolean requeueable,
        @JsonProperty("created_at") Instant createdAt) {
}
