package com.epam.aidial.enrichment.web.dto.dryrun;

import com.epam.aidial.enrichment.dlq.DlqStage;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/** One evaluated row or group of a dry run. Members that do not apply are absent. */
public record DryRunEvaluationDto(
        @Schema(description = "The evaluated row's grain key (row-grain pipelines)")
        @JsonProperty("grain_key") Object grainKey,

        @Schema(description = "The evaluated group's key (group pipelines)", example = "chat-42")
        @JsonProperty("group_key") String groupKey,

        @Schema(description = "`ok` when the reply validated and mapped; `failed` otherwise", example = "ok")
        String status,

        @Schema(description = "Where a failed evaluation stopped, named as the dead-letter queue names it")
        DlqStage stage,

        @Schema(description = "Each output's mapped value, keyed by target column, as a worker would write it")
        Map<String, Object> outputs,

        @Schema(description = "The rendered request body of the first attempt, capped at 64 KiB")
        String request,

        @Schema(description = "Whether the cap cut `request`; the full request was sent")
        @JsonProperty("request_truncated") Boolean requestTruncated,

        @Schema(description = "The raw reply of the last attempt, or DIAL's error body, capped at 64 KiB")
        String response,

        @Schema(description = "Whether the cap cut `response`")
        @JsonProperty("response_truncated") Boolean responseTruncated,

        @Schema(description = "DIAL's HTTP status when it refused the call", example = "404")
        @JsonProperty("dial_status") Integer dialStatus,

        @Schema(description = "What failed, for people")
        String message,

        @Schema(description = "The response-schema violations of the last reply (`validate` failures)")
        List<String> errors,

        @Schema(description = "The DIAL calls made, the corrective retries included", example = "1")
        int attempts,

        @Schema(description = "The usage the replies reported")
        Usage usage,

        @Schema(description = "The evaluation's wall time in milliseconds", example = "1830")
        @JsonProperty("latency_ms") long latencyMs,

        @Schema(description = "The members the model received (group pipelines)")
        @JsonProperty("members_included") Integer membersIncluded,

        @Schema(description = "The members the input token budget left out (group pipelines)")
        @JsonProperty("members_omitted") Integer membersOmitted,

        @Schema(description = "Whether the model was given a partial group (group pipelines)")
        @JsonProperty("members_truncated") Boolean membersTruncated) {

    /** Usage in its unit: {@code tokens} for a chat completion, {@code calls} for an application route. */
    public record Usage(long amount, String unit) {
    }
}
