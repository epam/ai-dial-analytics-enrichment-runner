package com.epam.aidial.enrichment.web.dto.dryrun;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Set;

/**
 * Optional body of {@code POST /v1/pipelines/{name}/try}: ADAS's preview narrowing, plus how many row-grain rows to
 * try. Each member is checked by ADAS where its preview checks it.
 */
public record DryRunRequest(
        @Schema(description = "The group to try, for a `group`-triggered pipeline; without it the newest group",
                example = "chat-42")
        @JsonProperty("group_key") String groupKey,

        @Schema(description = "Exclusive lower bound on the input's version column (row-grain pipelines only)")
        Instant from,

        @Schema(description = "Inclusive upper bound on the input's version column (row-grain pipelines only)")
        Instant to,

        @Schema(description = "How many of the newest sampled rows to evaluate, 1 to 5; 1 when omitted. Each is one "
                + "model call. Refused on a `group` pipeline", example = "1")
        Integer limit) {

    /** The members a body may carry; any other is refused with 400 rather than ignored. */
    public static final Set<String> MEMBERS = Set.of("group_key", "from", "to", "limit");

    public static final DryRunRequest EMPTY = new DryRunRequest(null, null, null, null);
}
