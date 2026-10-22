package com.epam.aidial.enrichment.web.dto.dlq;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One page of the DLQ listing. {@code next_cursor} is passed back as {@code cursor} for the next page and is absent
 * (null, which the JSON mapper omits) when {@code has_more} is false. {@code total} and {@code requeueable_total}
 * count everything the filters match, whatever the page.
 */
public record DlqListResponse(List<DlqItemDto> items,
                              @JsonProperty("next_cursor") String nextCursor,
                              @JsonProperty("has_more") boolean hasMore,
                              long total,
                              @JsonProperty("requeueable_total") long requeueableTotal) {
}
