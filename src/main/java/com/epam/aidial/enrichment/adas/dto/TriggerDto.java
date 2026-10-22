package com.epam.aidial.enrichment.adas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How and at which grain a pipeline fires, nested under {@code trigger} on the ADAS wire.
 *
 * <p>The grouping is ADAS's, and it is forced rather than cosmetic: a pipeline's top-level {@code group_by}
 * is the aggregate rollup grain (a list of keys), while an enrichment's group key is a single column that
 * qualifies the trigger. The two cannot share a name, and the group key is a property of the trigger rather
 * than of the transform.
 *
 * <p>{@code member_select} is deliberately unmodelled: ADAS applies the member-selection policy inside
 * {@code group-rows}, so the runner never reads it.
 */
public record TriggerDto(
        TriggerKind kind,
        String cron,
        @JsonProperty("group_by") String groupBy,
        @JsonProperty("ready_when") ReadyWhenDto readyWhen) {
}
