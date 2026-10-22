package com.epam.aidial.enrichment.web.controller;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.exception.PipelineNotFoundException;
import com.epam.aidial.enrichment.group.GroupListOrder;
import com.epam.aidial.enrichment.group.GroupListingService;
import com.epam.aidial.enrichment.persistence.GroupStateEntity;
import com.epam.aidial.enrichment.persistence.GroupStateRepository;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.sync.PipelineRouting;
import com.epam.aidial.enrichment.web.dto.admin.GroupStateResponse;
import com.epam.aidial.enrichment.web.mapper.GroupStateMapper;
import com.epam.aidial.enrichment.worker.WorkPriority;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.group.GroupPayload;
import com.github.kagkarlsson.scheduler.Scheduler;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Group-state introspection and the one group-level operator action.
 *
 * <p>Before this, the only group-level lever was pausing the whole pipeline — which punishes every other group to
 * re-drive one — and answering "why has this group not been evaluated?" meant querying Postgres directly.
 */
@Slf4j
@RestController
@RequestMapping("/v1/pipelines/{name}/groups")
@RequiredArgsConstructor
@Tag(name = "Group admin", description = "Group-state introspection and per-group requeue")
public class GroupAdminController {

    private final PipelineCache pipelineCache;
    private final GroupStateRepository groupStateRepository;
    private final GroupListingService listingService;
    private final GroupStateMapper mapper;
    private final GroupChunkTask groupChunkTask;
    private final Scheduler scheduler;

    /**
     * Lists a pipeline's groups by last activity, oldest first unless asked otherwise, one keyset page at a time; see
     * {@link GroupListingService#list}.
     */
    @GetMapping
    public GroupStateResponse list(@PathVariable("name") String pipelineName,
                                   @Parameter(description = "Page size, clamped to 1..500")
                                   @RequestParam(name = "limit", defaultValue = "" + GroupListingService.DEFAULT_LIMIT) int limit,
                                   @Parameter(description = "Which end to start from by last activity; defaults to oldest",
                                           schema = @Schema(type = "string", allowableValues = {"oldest", "newest"}))
                                   @RequestParam(name = "order", required = false) GroupListOrder order,
                                   @Parameter(description = "The previous page's next_cursor, under the same order; omit for the first page")
                                   @RequestParam(name = "cursor", required = false) String cursor) {
        requireGroupPipeline(pipelineName);
        return mapper.toResponse(listingService.list(pipelineName, limit, order, cursor));
    }

    /**
     * Re-drives one group now, through the same deterministic identity the sweep uses — so the override cannot
     * duplicate an item already queued for that version.
     *
     * <p>It deliberately bypasses the readiness windows: that is what makes it an override. It does <em>not</em>
     * bypass the cost ceiling in effect, because the evaluation it triggers is charged like any other and the
     * next sweep will still decline the group.
     *
     * <p>A group key containing {@code /} is sent with the slash percent-encoded as {@code %2F}; the path variable
     * binds the decoded key (see {@code EncodedSlashConfiguration}).
     */
    @PostMapping("/{groupKey}/requeue")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requeue(@PathVariable("name") String pipelineName, @PathVariable String groupKey) {
        PipelineDto pipeline = requireGroupPipeline(pipelineName);
        GroupStateEntity group = groupStateRepository.findByPipelineNameAndGroupKey(pipelineName, groupKey)
                .orElseThrow(() -> new PipelineNotFoundException(pipelineName + "/" + groupKey));

        long generation = pipeline.generation();
        String instanceId = DeterministicId.groupInstanceId(
                pipelineName, generation, groupKey, group.getGroupVersion());
        scheduler.scheduleIfNotExists(
                groupChunkTask.task().instanceBuilder(instanceId)
                        .data(new GroupPayload(pipelineName, generation, groupKey, group.getGroupVersion()))
                        .priority(WorkPriority.LIVE)
                        .build(),
                Instant.now());
        log.info("Operator requeued group {} for pipeline {} at version {}",
                groupKey, pipelineName, group.getGroupVersion());
    }

    private PipelineDto requireGroupPipeline(String pipelineName) {
        PipelineDto pipeline = pipelineCache.get(pipelineName);
        if (pipeline == null || !PipelineRouting.isGroup(pipeline)) {
            throw new PipelineNotFoundException(pipelineName);
        }
        return pipeline;
    }
}
