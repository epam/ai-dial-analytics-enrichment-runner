package com.epam.aidial.enrichment.adas.dto;

/**
 * How a pipeline is triggered, mirroring ADAS. ADAS serializes enum values lowercased
 * ({@code schedule}, {@code group}); the runner's Jackson mapper reads enums
 * case-insensitively, so binding is robust regardless of casing.
 *
 * <p>Every value ADAS can send is modelled, including ones the runner refuses to execute: an unmodelled
 * value fails the whole {@code GET /v1/pipelines} parse, which would stop pipeline sync for <em>every</em> pipeline
 * the moment ADAS registers one pipeline the runner does not know about. Modelling it instead lets the
 * admission gate skip that single pipeline with a reason.
 */
public enum TriggerKind {

    /**
     * Retired: modelled only so the feed parse survives it, and refused at admission. ADAS rewrote every
     * {@code on_ingest} pipeline to {@code schedule} with a cron and refuses the kind on write, so under contract
     * 3 it never arrives; if it does, {@code PipelineRouting.unsupportedReason} refuses that one entry rather
     * than the parse failing the whole feed.
     */
    ON_INGEST,

    /** Row grain, fired on the pipeline's cron expression. */
    SCHEDULE,

    /**
     * Group grain: one evaluation per group key, over the group's assembled members. Fired by the readiness
     * sweep rather than directly by the scan, which only records that the group changed.
     */
    GROUP
}
