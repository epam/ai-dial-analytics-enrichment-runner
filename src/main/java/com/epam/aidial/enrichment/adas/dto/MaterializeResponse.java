package com.epam.aidial.enrichment.adas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response of the Class-1 materialize call. ADAS probes the window before it writes it and then runs a
 * single server-side {@code INSERT … SELECT} over exactly that range, stamping provenance itself — so
 * counts and cursor bookkeeping are all that cross the wire.
 *
 * <p>On an empty window ADAS returns {@code rowsWritten=0}, {@code hasMore=false} and echoes the
 * caller's cursor unchanged — omitting {@code next_cursor} entirely when the caller sent none. The
 * runner must therefore treat a null {@code nextCursor} as "keep the stored cursor", never as a reset.
 *
 * @param rowsWritten rows ClickHouse reported written for this window
 * @param nextCursor  opaque cursor to echo on the next request; null/unchanged for an empty window
 * @param hasMore     true when the window filled to the limit (more of the backlog remains)
 * @param lagSeconds  server_now − the window's end version; null for an empty window
 */
public record MaterializeResponse(
        @JsonProperty("rows_written") long rowsWritten,
        @JsonProperty("next_cursor") String nextCursor,
        @JsonProperty("has_more") boolean hasMore,
        @JsonProperty("lag_seconds") Long lagSeconds) {
}
