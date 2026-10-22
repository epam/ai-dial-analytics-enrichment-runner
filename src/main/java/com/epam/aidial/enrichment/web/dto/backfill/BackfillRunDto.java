package com.epam.aidial.enrichment.web.dto.backfill;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A backfill run as returned by the ops API — by the single-run read and the listing alike. The opaque cursor and
 * the pipeline generation the run is pinned to are internal and not exposed.
 *
 * <p>{@code windowFrom}/{@code windowTo} report the version window the run covers — absent for a full replay.
 * They are exposed because a run is inspected after the fact, often to answer "did we already re-derive that
 * range?", which the request that started it cannot answer once the shell history is gone.
 *
 * <p>{@code pausedReason}/{@code pausedAt} are present only while the run is paused, and say what paused it.
 * {@code lastError}/{@code lastErrorAt} are present while the last batch failed and no page has committed since
 * — and on a run cancelled in that condition. Every optional property is absent rather than null.
 *
 * <p>There is deliberately no total: the run reports the rows it has <em>processed</em>, and no total is
 * obtainable. ADAS exposes no cardinality, the cursor is opaque, the window's upper bound moves, and a source
 * count would not be comparable with the processed count anyway.
 */
public record BackfillRunDto(
        String id,
        @JsonProperty("pipeline_name") String pipelineName,
        String kind,
        @Schema(allowableValues = {"running", "paused", "done", "cancelled"})
        String state,
        @Schema(description = "Why the run is paused; present only while it is paused",
                allowableValues = {"operator", "pipeline_paused_operator", "pipeline_paused_breaker",
                    "spend_budget_exhausted", "cursor_stalled", "pipeline_absent", "pipeline_changed"})
        @JsonProperty("paused_reason") String pausedReason,
        @JsonProperty("paused_at") Instant pausedAt,
        @Schema(description = "Rows processed so far; for a Class-1 run, rows ADAS reported writing")
        @JsonProperty("rows_done") long rowsDone,
        @Schema(description = "Classified reason the last batch failed; cleared by the next successful page")
        @JsonProperty("last_error") String lastError,
        @JsonProperty("last_error_at") Instant lastErrorAt,
        @JsonProperty("window_from") Instant windowFrom,
        @JsonProperty("window_to") Instant windowTo,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("finished_at") Instant finishedAt) {
}
