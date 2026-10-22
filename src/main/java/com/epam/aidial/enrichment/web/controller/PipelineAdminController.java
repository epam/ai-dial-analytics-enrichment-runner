package com.epam.aidial.enrichment.web.controller;

import com.epam.aidial.enrichment.observability.OperatorActionLog;
import com.epam.aidial.enrichment.pause.PauseOrigin;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.web.dto.admin.PausedPipelineDto;
import com.epam.aidial.enrichment.web.dto.admin.PausedPipelinesResponse;
import com.epam.aidial.enrichment.web.dto.admin.PipelineCacheResponse;
import com.epam.aidial.enrichment.web.mapper.PipelineCacheMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.Locale;

/** Ops API to pause/resume a pipeline's enqueue (recovery after the DLQ circuit breaker auto-pauses). */
@RestController
@RequestMapping("/v1/pipelines")
@RequiredArgsConstructor
public class PipelineAdminController {

    private final PipelinePauseRegistry pauseRegistry;
    private final PipelineCache pipelineCache;
    private final PipelineCacheMapper pipelineCacheMapper;

    @GetMapping("/cache")
    public PipelineCacheResponse cache() {
        return pipelineCacheMapper.toResponse(pipelineCache.getAll());
    }

    @PostMapping("/{name}/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pause(@PathVariable String name) {
        // An operator pause never expires: it is a decision taken with knowledge the runner does not have,
        // and must survive anything the runner concludes. Only a breaker pause lifts itself.
        pauseRegistry.pause(name, PauseOrigin.OPERATOR, "paused by an operator");
        OperatorActionLog.record("pipeline_pause", name, null);
    }

    @PostMapping("/{name}/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resume(@PathVariable String name) {
        pauseRegistry.resume(name);
        OperatorActionLog.record("pipeline_resume", name, null);
    }

    @GetMapping("/paused")
    public PausedPipelinesResponse paused() {
        return new PausedPipelinesResponse(pauseRegistry.paused().entrySet().stream()
                .map(entry -> new PausedPipelineDto(entry.getKey(),
                        entry.getValue().origin().name().toLowerCase(Locale.ROOT),
                        entry.getValue().reason(), entry.getValue().since(), entry.getValue().resumesAt()))
                .sorted(Comparator.comparing(PausedPipelineDto::pipelineName))
                .toList());
    }
}
