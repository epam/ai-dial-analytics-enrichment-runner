package com.epam.aidial.enrichment.web.controller;

import com.epam.aidial.enrichment.runtime.PipelineRuntimeService;
import com.epam.aidial.enrichment.web.dto.runtime.PipelineRuntimeDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The per-pipeline runtime view: what one cached pipeline is doing right now, for every lane.
 *
 * <p>Answers 503 {@code pipeline_cache_cold} before the first pipeline sync, and 404 {@code not_found} for a pipeline a
 * synced cache does not hold.
 */
@RestController
@RequestMapping("/v1/pipelines/{name}/runtime")
@RequiredArgsConstructor
@Tag(name = "Pipeline runtime", description = "What one pipeline is doing right now, in one shape for every lane")
public class PipelineRuntimeController {

    private final PipelineRuntimeService runtimeService;

    @GetMapping
    public PipelineRuntimeDto runtime(@PathVariable("name") String pipelineName) {
        return runtimeService.runtime(pipelineName);
    }
}
