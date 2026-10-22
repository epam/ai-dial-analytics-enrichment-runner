package com.epam.aidial.enrichment.web.dto.backfill;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Optional body for starting a backfill. Omitting it — or both fields — starts a full replay, which is what every
 * backfill did before windows existed and still the default.
 *
 * <p>Bounding a run is how a declaration change is re-applied to history: patch the pipeline's transform in
 * ADAS — which bumps its {@code generation} — then
 * backfill the affected range, rather than replaying everything ever ingested to correct a week.
 *
 * @param from exclusive lower bound on the source's version column
 * @param to   inclusive upper bound; ADAS clamps it by its safety-lag horizon
 */
public record BackfillRequest(
        @Schema(description = "Exclusive lower bound on the source's version column; omit for a full replay",
                example = "2026-07-01T00:00:00Z")
        Instant from,
        @Schema(description = "Inclusive upper bound; always clamped by ADAS's safety-lag horizon",
                example = "2026-08-01T00:00:00Z")
        Instant to) {
}
