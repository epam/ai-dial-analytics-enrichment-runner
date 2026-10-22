package com.epam.aidial.enrichment.web.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One group's state, for the operator question this endpoint exists to answer: "why has this group not been
 * evaluated?" — which otherwise needs database access.
 *
 * <p>Identifiers, versions, timestamps and counts only. Never member rows and never the label: the group key is
 * an identifier, the members are source content.
 *
 * <p>{@code dirty} is derived rather than stored, because "is this waiting on the sweep?" is the reason to look.
 * {@code signalled} answers the neighbouring question — whether the group fired (or will fire) on its pipeline's
 * {@code ready_when.signal} rather than on the idle window — which is otherwise indistinguishable from the
 * sweep having simply run early.
 */
public record GroupStateDto(
        @JsonProperty("group_key") String groupKey,
        @JsonProperty("group_version") long groupVersion,
        @JsonProperty("last_activity_at") Instant lastActivityAt,
        boolean signalled,
        @JsonProperty("computed_version") Long computedVersion,
        @JsonProperty("computed_at") Instant computedAt,
        @JsonProperty("computed_truncated") boolean computedTruncated,
        @JsonProperty("evaluations_day") LocalDate evaluationsDay,
        int evaluations,
        boolean dirty) {
}
