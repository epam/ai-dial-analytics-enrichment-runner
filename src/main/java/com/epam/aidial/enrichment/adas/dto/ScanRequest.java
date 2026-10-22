package com.epam.aidial.enrichment.adas.dto;

import java.time.Instant;

/**
 * Request body for the pipeline-scoped scan {@code POST /v1/pipelines/{name}/scan}. Every field is optional: a null
 * {@code cursor} scans from the source start (full replay); a null {@code limit} lets ADAS apply its configured
 * default.
 *
 * <p>{@code from}/{@code to} bound the scan on the source's version column, half-open {@code (from, to]} and
 * always clamped by ADAS's safety-lag horizon. They <em>bound the keyset</em> rather than replace the cursor, so
 * a windowed scan still advances and resumes normally. Identical in shape and meaning to
 * {@link MaterializeRequest}'s window — one vocabulary for both reads, because ADAS gives them one. The live
 * tick leaves them null; a bounded backfill supplies them.
 *
 * @param cursor opaque cursor echoed back to ADAS (stored verbatim by the runner)
 * @param limit  page size
 * @param from   exclusive lower bound on the source's version column
 * @param to     inclusive upper bound on the source's version column
 */
public record ScanRequest(String cursor, Integer limit, Instant from, Instant to) {

    /** The unbounded live-tick form: a cursor and a limit, no window. */
    public ScanRequest(String cursor, Integer limit) {
        this(cursor, limit, null, null);
    }
}
