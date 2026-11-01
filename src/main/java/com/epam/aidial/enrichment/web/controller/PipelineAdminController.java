package com.epam.aidial.enrichment.web.controller;

import com.epam.aidial.enrichment.exception.PipelineNotPausedException;
import com.epam.aidial.enrichment.observability.OperatorActionLog;
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

/**
 * Ops API over this runner's view of the pipelines: the synced cache, the DLQ circuit breaker's pauses, and an early
 * lift of one. There is no pause: a pipeline is stopped and started through ADAS's {@code enabled} flag, its single
 * run control, which the runner follows. The resume only lifts a breaker pause, so it cannot stop anything and is not
 * a second switch.
 */
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

    /**
     * Lifts the breaker's pause before its window elapses — after an upstream was fixed, or where expiry is
     * configured off. A pipeline the breaker has not paused is a {@code 409}, not a silent success.
     */
    @PostMapping("/{name}/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resume(@PathVariable String name) {
        if (!pauseRegistry.resume(name)) {
            throw new PipelineNotPausedException(name);
        }
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
