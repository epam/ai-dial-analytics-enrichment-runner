package com.epam.aidial.enrichment.web.controller;

import com.epam.aidial.enrichment.dlq.DlqLane;
import com.epam.aidial.enrichment.dlq.DlqListingService;
import com.epam.aidial.enrichment.dlq.DlqRequeueService;
import com.epam.aidial.enrichment.web.dto.dlq.DlqListResponse;
import com.epam.aidial.enrichment.web.dto.dlq.RequeueResponse;
import com.epam.aidial.enrichment.web.mapper.DlqItemMapper;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ops API over the dead-letter queue: list and requeue (single, or bulk by pipeline and/or backfill run). */
@RestController
@RequestMapping("/v1/dlq")
@RequiredArgsConstructor
public class DlqController {

    private final DlqListingService listingService;
    private final DlqItemMapper dlqItemMapper;
    private final DlqRequeueService requeueService;

    /**
     * Lists dead letters newest first, narrowed by pipeline, by the backfill run that produced them, or by lane, one
     * keyset page at a time; see {@link DlqListingService#list}.
     */
    @GetMapping
    public DlqListResponse list(@RequestParam(name = "pipeline_name", required = false) String pipelineName,
                                @RequestParam(name = "run_id", required = false) String runId,
                                @Parameter(schema = @Schema(type = "string", allowableValues = {"live", "backfill"}))
                                @RequestParam(name = "lane", required = false) DlqLane lane,
                                @Parameter(description = "Page size, clamped to 1..1000")
                                @RequestParam(name = "limit", defaultValue = "" + DlqListingService.DEFAULT_LIMIT) int limit,
                                @Parameter(description = "The previous page's next_cursor; omit for the first page")
                                @RequestParam(name = "cursor", required = false) String cursor) {
        return dlqItemMapper.toListResponse(listingService.list(pipelineName, runId, lane, limit, cursor));
    }

    @PostMapping("/{id}/requeue")
    public RequeueResponse requeue(@PathVariable long id) {
        return new RequeueResponse(requeueService.requeue(id));
    }

    /**
     * Requeues in bulk by pipeline, by run, or by both (their intersection). At least one is required; an
     * intersection that matches nothing requeues nothing rather than erroring.
     */
    @PostMapping("/requeue")
    public RequeueResponse requeueBulk(@RequestParam(name = "pipeline_name", required = false) String pipelineName,
                                       @RequestParam(name = "run_id", required = false) String runId) {
        return new RequeueResponse(requeueService.requeueBy(pipelineName, runId));
    }
}
