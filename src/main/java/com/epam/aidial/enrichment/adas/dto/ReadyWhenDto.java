package com.epam.aidial.enrichment.adas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A group pipeline's readiness declaration, mirroring ADAS. It answers "when is this group worth
 * evaluating?", which is deliberately a separate question from "has this group changed?" — the latter
 * is the dirty-mark's job.
 *
 * <p>ADAS requires at least one of {@code signal}, {@code idle} or {@code maxStaleness}, and validates
 * {@code signal} under the same bounded grammar as {@code filter_sql}. A group is ready when <strong>any</strong>
 * of the conditions its pipeline declares holds, so the runner refuses only a pipeline declaring none of them.
 *
 * <p>{@code signal} is honoured without the runner understanding the predicate: ADAS evaluates it inside the
 * scan and projects the verdict per row as {@code __signal}, which the runner records on the group and reads
 * back as a readiness branch. Reimplementing the grammar here would not fail loudly on divergence — it would
 * silently mark the wrong groups ready — so the verdict is taken, never recomputed.
 *
 * @param signal       boolean predicate marking a row that terminates its group; a group stays signalled once
 *                     any member satisfies it, and the signalled branch of readiness carries no time bound
 * @param idle         how long a group must be quiet before it counts as ready (e.g. {@code 10m} or
 *                     {@code PT10M})
 * @param maxStaleness maximum age of a computed result before the group is re-evaluated regardless of
 *                     activity
 * @param costCeiling  per-group evaluations permitted per UTC day; null is unlimited. Enforced by the
 *                     readiness sweep, which stops selecting a group that has reached it, and charged
 *                     with the group's computed stamp
 */
public record ReadyWhenDto(
        String signal,
        String idle,
        @JsonProperty("max_staleness") String maxStaleness,
        @JsonProperty("cost_ceiling") Integer costCeiling) {
}
