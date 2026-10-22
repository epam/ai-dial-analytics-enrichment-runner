package com.epam.aidial.enrichment.web.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One page of a pipeline's group state. {@code next_cursor} is passed back as {@code cursor} for the next page and is
 * absent (null, which the JSON mapper omits) when {@code has_more} is false. {@code total} counts all of the
 * pipeline's groups, whatever the page.
 */
public record GroupStateResponse(List<GroupStateDto> groups,
                                 @JsonProperty("next_cursor") String nextCursor,
                                 @JsonProperty("has_more") boolean hasMore,
                                 long total) {
}
