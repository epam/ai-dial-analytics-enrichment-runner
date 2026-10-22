package com.epam.aidial.enrichment.dial.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token usage reported by DIAL. {@code totalTokens} is what the per-pipeline daily spend counter records; the two
 * halves are carried because DIAL sends them and dropping them would make the counter unexplainable.
 */
public record Usage(
        @JsonProperty("prompt_tokens") Integer promptTokens,
        @JsonProperty("completion_tokens") Integer completionTokens,
        @JsonProperty("total_tokens") Integer totalTokens) {
}
