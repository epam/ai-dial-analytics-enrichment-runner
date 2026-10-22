package com.epam.aidial.enrichment.adas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Response of the pipeline-scoped scan. ADAS applies the pipeline's stored filter, projection, {@code final}
 * and safety-lag; the runner treats {@code nextCursor} as authoritative and stores it verbatim.
 *
 * @param rows       scanned source rows (projected columns only), each a column-name -> value map
 * @param nextCursor opaque cursor to echo on the next request
 * @param hasMore    true when the page filled to the limit (more rows may follow)
 * @param lagSeconds server_now − max(version) among returned rows; null on an empty page
 */
public record ScanResponse(
        List<Map<String, Object>> rows,
        @JsonProperty("next_cursor") String nextCursor,
        @JsonProperty("has_more") boolean hasMore,
        @JsonProperty("lag_seconds") Long lagSeconds) {

    /**
     * The computed column a group pipeline's page carries when its pipeline declares {@code ready_when.signal}:
     * ADAS's verdict of that predicate for that row. It is a verdict rather than the raw columns the
     * predicate reads precisely so the runner never re-implements ADAS's bounded predicate grammar — a
     * second implementation would be free to diverge, and would then mark the wrong groups ready.
     */
    public static final String SIGNAL_COLUMN = "__signal";
}
