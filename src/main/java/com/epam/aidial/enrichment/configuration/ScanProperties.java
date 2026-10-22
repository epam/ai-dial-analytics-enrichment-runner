package com.epam.aidial.enrichment.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the incremental scan ({@code scan.*}).
 *
 * <p>There is no cadence here: a row-grain pipeline fires on its own {@code trigger.cron}, so nothing falls back
 * to a lane default (see {@code ScheduleFactory}).
 *
 * @param defaultScanLimit page size for a pipeline that declares no {@code advanced.rows_per_scan}
 * @param maxScanLimit     hard ceiling on a page; ADAS rejects an over-limit scan with a 400 rather than
 *                         clamping it, so the runner clamps before asking. Keep it at or below ADAS's
 *                         own {@code ENRICHMENT_SCAN_MAX_LIMIT}
 * @param defaultChunk     work-item chunk size for a pipeline that declares no
 *                         {@code advanced.rows_per_call}
 * @param maxInflight      backpressure threshold: a scan fire is skipped when a pipeline's pending +
 *                         delayed chunk depth exceeds this
 * @param maxPagesPerFire  per-fire drain budget: at most this many scan pages per fire
 */
@ConfigurationProperties(prefix = "scan")
public record ScanProperties(
        int defaultScanLimit,
        int maxScanLimit,
        int defaultChunk,
        int maxInflight,
        int maxPagesPerFire) {

    /**
     * Resolves and clamps a pipeline's page size: falls back to {@link #defaultScanLimit()} when unset,
     * floors at 1 so a misconfigured {@code advanced.rows_per_scan <= 0} can never request a degenerate page,
     * and
     * caps at {@link #maxScanLimit()} because ADAS answers an over-limit scan with a 400 instead of
     * clamping — the same reason {@code Class1Properties.resolveLimit} caps the materialize window. Without
     * the cap a pipeline declaring a {@code rows_per_scan} above ADAS's ceiling is accepted by the
     * registry and then fails on <em>every</em> fire, so its lane never advances at all.
     */
    public int resolveScanLimit(Integer pipelineScanLimit) {
        int limit = pipelineScanLimit != null ? pipelineScanLimit : defaultScanLimit;
        return Math.clamp(limit, 1, maxScanLimit);
    }

    /**
     * Resolves and clamps a pipeline's chunk size: falls back to {@link #defaultChunk()} when unset and
     * floors at 1 so a misconfigured {@code advanced.rows_per_call <= 0} can never spin the chunking loop
     * forever.
     */
    public int resolveChunk(Integer pipelineChunk) {
        int chunk = pipelineChunk != null ? pipelineChunk : defaultChunk;
        return Math.max(1, chunk);
    }
}
