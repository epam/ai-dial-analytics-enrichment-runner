package com.epam.aidial.enrichment.adas.dto;

import java.time.Instant;

/**
 * Request body for the Class-1 materialize call {@code POST /v1/pipelines/{name}/materialize}. Every field is
 * optional: a null {@code cursor} materializes from the source start, and a null {@code limit} lets ADAS
 * apply its configured default.
 *
 * <p>{@code from}/{@code to} bound the window on the source's version column, half-open
 * {@code (from, to]} and always clamped by ADAS's safety-lag horizon. They share their shape and
 * meaning with the scan's window, so one vocabulary covers both lanes. The live tick leaves them null;
 * bounded-window backfill drives them.
 *
 * @param cursor opaque cursor echoed from a prior window (stored verbatim by the runner)
 * @param limit  maximum source rows in this window
 * @param from   exclusive lower bound on the source's version column
 * @param to     inclusive upper bound on the source's version column
 */
public record MaterializeRequest(String cursor, Integer limit, Instant from, Instant to) {

    /** The unbounded live-tick form: a cursor and a limit, no window. */
    public MaterializeRequest(String cursor, Integer limit) {
        this(cursor, limit, null, null);
    }
}
