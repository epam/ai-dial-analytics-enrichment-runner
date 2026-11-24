package com.epam.aidial.enrichment.web.controller;

import com.epam.aidial.enrichment.adas.InboundCredential;
import com.epam.aidial.enrichment.dryrun.DryRunService;
import com.epam.aidial.enrichment.exception.DryRunBodyException;
import com.epam.aidial.enrichment.exception.DryRunLimitException;
import com.epam.aidial.enrichment.web.dto.dryrun.DryRunRequest;
import com.epam.aidial.enrichment.web.dto.dryrun.DryRunResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dry run of an {@code llm} pipeline: one sampled row (up to five) or one group, evaluated through the model or
 * application exactly as a worker would, with nothing written. Its input is read through ADAS as the caller, so the
 * caller's own column and row restrictions apply.
 */
@RestController
@RequestMapping("/v1/pipelines/{name}/try")
@RequiredArgsConstructor
@Tag(name = "Pipeline dry run", description = "Call a pipeline's model or application on a sampled row or group")
public class DryRunController {

    private final DryRunService dryRunService;
    private final JsonMapper jsonMapper;

    @PostMapping
    @Operation(summary = "Dry-run a pipeline against its model",
            description = "Reads a sample through ADAS's trial input as the caller, then renders, calls, validates and "
                    + "maps each sampled row (`limit`, default 1, at most 5) or the sampled group as a worker would. "
                    + "Writes nothing, dead-letters nothing and moves no cursor, but each call counts towards the "
                    + "pipeline's daily spend. A model or application failure is answered 200 with a `failed` "
                    + "evaluation naming its stage; ADAS's refusal is answered with ADAS's status and code; 409 "
                    + "`dry_run_in_progress`, 429 `spend_budget_exhausted` or `dial_rate_limited`, 422 "
                    + "`pipeline_not_runnable` or `pipeline_validation_failed` (a `limit` outside 1-5), 503 `adas_unavailable` or `adas_contract_mismatch` otherwise.")
    public DryRunResponse dryRun(@PathVariable("name") String pipelineName,
                                 @RequestBody(required = false) JsonNode body,
                                 @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                 @RequestHeader(value = InboundCredential.API_KEY_HEADER, required = false) String apiKey) {
        return dryRunService.dryRun(pipelineName, request(body), InboundCredential.of(authorization, apiKey));
    }

    /**
     * The body as a request, refusing a member it does not know with 400: the runner's mapper ignores unknown members
     * everywhere else, and a dry run silently dropping one would evaluate something its caller did not ask for. A
     * {@code limit} out of range is refused here too, before ADAS is read, with the 422 ADAS would answer; that it is
     * refused on a {@code group} pipeline is ADAS's to say, since only the read tells which trigger the pipeline has.
     */
    private DryRunRequest request(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return DryRunRequest.EMPTY;
        }
        if (!body.isObject() || body.propertyNames().stream().anyMatch(member -> !DryRunRequest.MEMBERS.contains(member))) {
            throw new DryRunBodyException();
        }
        DryRunRequest request;
        try {
            request = jsonMapper.treeToValue(body, DryRunRequest.class);
        } catch (RuntimeException e) {
            throw new DryRunBodyException();
        }
        if (request.limit() != null && (request.limit() < 1 || request.limit() > DryRunRequest.MAX_LIMIT)) {
            throw new DryRunLimitException(request.limit());
        }
        return request;
    }
}
