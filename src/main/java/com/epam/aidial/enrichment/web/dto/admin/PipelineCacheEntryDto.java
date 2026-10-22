package com.epam.aidial.enrichment.web.dto.admin;

import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A lean view of one cached pipeline for ops introspection.
 *
 * <p>One identifier, deliberately: the {@code name} is the pipeline's identity in the ADAS registry, in this
 * runner's state tables and in its work-item ids alike, so there is no second id to report.
 *
 * <p><strong>And one declaration revision.</strong> The {@code evaluator_version} this used to report
 * alongside the generation was dropped rather than re-populated from it: the evaluator fold leaves no
 * evaluator version in the feed, and {@code generation} is what now answers "which declaration is this
 * pipeline running?". Serving the same number twice under two names would invite a reader to look for a
 * difference between them.
 */
public record PipelineCacheEntryDto(
        String name,
        @JsonProperty("trigger_kind") TriggerKind triggerKind,
        boolean enabled,
        long generation) {
}
