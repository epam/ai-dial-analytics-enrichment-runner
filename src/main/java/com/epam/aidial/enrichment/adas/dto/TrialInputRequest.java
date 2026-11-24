package com.epam.aidial.enrichment.adas.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Body of ADAS's {@code POST /v1/pipelines/{name}/trial-input}: the preview's narrowing and the number of row-grain
 * rows to try. Absent members are left out, so ADAS applies its own defaults.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrialInputRequest(
        @JsonProperty("group_key") String groupKey,
        Instant from,
        Instant to,
        Integer limit) {
}
