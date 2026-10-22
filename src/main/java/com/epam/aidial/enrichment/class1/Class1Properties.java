package com.epam.aidial.enrichment.class1;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the Class-1 materialize tick ({@code class1.*}).
 *
 * @param defaultLimit   window size (source rows) for a pipeline that declares no
 *                       {@code advanced.rows_per_scan}
 * @param maxLimit       hard ceiling on a window; ADAS rejects an over-limit request with a 400 rather
 *                       than clamping it, so the runner clamps before asking
 */
@ConfigurationProperties(prefix = "class1")
public record Class1Properties(
        int defaultLimit,
        int maxLimit) {

    /**
     * Resolves and clamps a pipeline's window size: falls back to {@link #defaultLimit()} when unset, floors
     * at 1 so a misconfigured {@code advanced.rows_per_scan <= 0} can never request a degenerate window, and
     * caps at {@link #maxLimit()} because ADAS answers an over-limit request with a 400 instead of
     * clamping — a pipeline with a large declared batch would otherwise never materialize at all.
     */
    public int resolveLimit(Integer pipelineScanLimit) {
        int limit = pipelineScanLimit != null ? pipelineScanLimit : defaultLimit;
        return Math.clamp(limit, 1, maxLimit);
    }
}
