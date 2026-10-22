package com.epam.aidial.enrichment.adas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for group assembly ({@code POST /v1/pipelines/{name}/group-rows}). The runner supplies only the
 * group key: ADAS resolves the source, applies the pipeline's membership filter, orders members by the
 * source's total order, and enforces its row and response-byte caps.
 *
 * @param groupKey the group to assemble, as its string form regardless of the column's physical type
 * @param limit    maximum members to return; ADAS applies a default when omitted and <em>rejects</em> a
 *                 value above its maximum with a 400 rather than clamping it
 */
public record GroupFetchRequest(
        @JsonProperty("group_key") String groupKey,
        Integer limit) {
}
