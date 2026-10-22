package com.epam.aidial.enrichment.web.controller;

import com.epam.aidial.enrichment.backfill.BackfillPauseReason;
import com.epam.aidial.enrichment.backfill.BackfillService;
import com.epam.aidial.enrichment.backfill.BackfillWindow;
import com.epam.aidial.enrichment.backfill.RunState;
import com.epam.aidial.enrichment.web.dto.backfill.BackfillRequest;
import com.epam.aidial.enrichment.web.dto.backfill.BackfillRunDto;
import com.epam.aidial.enrichment.web.dto.backfill.BackfillRunListResponse;
import com.epam.aidial.enrichment.web.mapper.BackfillRunMapper;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops API for backfills: start one for a pipeline, and inspect / pause / resume / cancel a run.
 *
 * <p>A backfill re-drives whichever read the pipeline's lane requires — a scan for a row or group pipeline, a
 * materialize window for a Class-1 one — at backfill priority, so it never starves live work. It covers the
 * whole source by default, or a bounded version window, which is how a declaration change is re-applied
 * to a range of history instead of replaying everything ever ingested.
 *
 * <p>Runs are listable, so one is never reachable only through the id handed out once in the {@code 202} of the
 * request that created it. The lifecycle verbs are guarded by the run's state: pause admits a running run, resume
 * a paused one, cancel either; anything else is {@code 409}.
 */
@RestController
@RequiredArgsConstructor
public class BackfillController {

    private final BackfillService backfillService;
    private final BackfillRunMapper mapper;

    @PostMapping("/v1/pipelines/{name}/backfill")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BackfillRunDto start(@PathVariable("name") String pipelineName,
                                @RequestBody(required = false) BackfillRequest request) {
        BackfillWindow window = request == null
                ? BackfillWindow.UNBOUNDED : BackfillWindow.of(request.from(), request.to());
        return mapper.toDto(backfillService.start(pipelineName, window));
    }

    /**
     * Lists runs newest first. An unrecognised state or reason is {@code 400} rather than an empty list — an empty
     * list reads as "nothing is running", the wrong answer to a typo and the dangerous one.
     */
    @GetMapping("/v1/backfills")
    public BackfillRunListResponse list(
            @RequestParam(name = "pipeline_name", required = false) String pipelineName,
            @Parameter(schema = @Schema(type = "string", allowableValues = {"running", "paused", "done", "cancelled"}))
            @RequestParam(name = "state", required = false) RunState state,
            @Parameter(schema = @Schema(type = "string", allowableValues = {"operator", "pipeline_paused_operator",
                "pipeline_paused_breaker", "spend_budget_exhausted", "cursor_stalled", "pipeline_absent",
                "pipeline_changed"}))
            @RequestParam(name = "paused_reason", required = false) BackfillPauseReason pausedReason,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return mapper.toListResponse(backfillService.list(pipelineName, state, pausedReason, limit));
    }

    @GetMapping("/v1/backfills/{runId}")
    public BackfillRunDto get(@PathVariable String runId) {
        return mapper.toDto(backfillService.get(runId));
    }

    @PostMapping("/v1/backfills/{runId}/pause")
    public BackfillRunDto pause(@PathVariable String runId) {
        return mapper.toDto(backfillService.pause(runId));
    }

    @PostMapping("/v1/backfills/{runId}/resume")
    public BackfillRunDto resume(@PathVariable String runId) {
        return mapper.toDto(backfillService.resume(runId));
    }

    @PostMapping("/v1/backfills/{runId}/cancel")
    public BackfillRunDto cancel(@PathVariable String runId) {
        return mapper.toDto(backfillService.cancel(runId));
    }
}
