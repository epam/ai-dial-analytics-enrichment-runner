package com.epam.aidial.enrichment.dlq;

import com.epam.aidial.enrichment.exception.DlqFilterException;
import com.epam.aidial.enrichment.exception.DlqItemNotFoundException;
import com.epam.aidial.enrichment.exception.DlqNotRequeueableException;
import com.epam.aidial.enrichment.observability.OperatorActionLog;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemRepository;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.worker.ChunkPayload;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.epam.aidial.enrichment.worker.PreFoldWorkItem;
import com.epam.aidial.enrichment.worker.WorkPriority;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.group.GroupPayload;
import com.epam.aidial.enrichment.worker.upsert.UpsertPayload;
import com.epam.aidial.enrichment.worker.upsert.UpsertRowsTask;
import com.github.kagkarlsson.scheduler.Scheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

/**
 * Requeues dead-lettered work items: re-enqueues the archived payload onto the task it came from and
 * deletes the DLQ item,
 * in one transaction (the re-enqueue joins the tx via the tx-aware datasource). Per-row poison items
 * (no payload) are not requeueable. Raw responses are dropped on requeue-success (the row is deleted).
 *
 * <p><strong>A payload archived before ADAS's evaluator fold is refused rather than re-run</strong>, and
 * this is the one consequence of that fold that can bite after its deploy window has closed. The three work
 * items are deserialized from their stored JSON directly, and the fold replaced their {@code int}
 * {@code evaluatorVersion} with a {@code long} {@code pipelineGeneration}. A pre-fold payload therefore
 * deserializes with a generation of 0, which is not a declaration revision ADAS will ever serve, so the
 * re-enqueued item would name a pipeline revision that does not exist. Refusing at the boundary says so
 * once, in the response, and leaves the row intact for inspection. See {@link #foldEra}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlqRequeueService {

    /** What a refused pre-fold requeue says, spelled once for the single and the bulk path. */
    private static final String PRE_FOLD_REASON =
            "its payload was archived before the evaluator fold and carries no usable pipeline_generation, "
                    + "so it describes a work item this build cannot re-run";

    private final DlqItemRepository dlqItemRepository;
    private final Scheduler scheduler;
    private final EnrichChunkTask enrichChunkTask;
    private final GroupChunkTask groupChunkTask;
    private final UpsertRowsTask upsertRowsTask;
    private final JsonMapper jsonMapper;

    @Transactional
    public int requeue(long id) {
        DlqItemEntity item = dlqItemRepository.findById(id).orElseThrow(() -> new DlqItemNotFoundException(id));
        if (item.getPayload() == null) {
            throw new DlqNotRequeueableException(id);
        }
        if (!foldEra(item.getPayload())) {
            throw new DlqNotRequeueableException(id, PRE_FOLD_REASON);
        }
        reenqueue(item);
        dlqItemRepository.delete(item);
        OperatorActionLog.record("dlq_requeue", item.getPipelineName(), "item=" + id
                + (item.getRunId() == null ? "" : " run=" + item.getRunId()));
        return 1;
    }

    /** Requeues every requeueable item of a pipeline. */
    @Transactional
    public int requeueByPipeline(String pipelineName) {
        return requeueBy(pipelineName, null);
    }

    /**
     * Requeues in bulk, selecting by pipeline, by backfill run, or by both — their intersection. At least one is
     * required; an intersection that matches nothing requeues nothing, since a bulk operation that matched nothing
     * has not failed.
     *
     * <p>A run's items are requeued exactly as any other: onto the task each came from, at that task's own
     * priority, and counted toward the pipeline's live in-flight backpressure. A requeue is an operator decision
     * taken now rather than a continuation of the replay, so a run-scoped requeue of a large replay's failures
     * throttles that pipeline's live scan while it drains.
     */
    @Transactional
    public int requeueBy(String pipelineName, String runId) {
        String pipeline = StringUtils.trimToNull(pipelineName);
        String run = StringUtils.trimToNull(runId);
        List<DlqItemEntity> items;
        if (pipeline != null && run != null) {
            items = dlqItemRepository.findByPipelineNameAndRunId(pipeline, run);
        } else if (pipeline != null) {
            items = dlqItemRepository.findByPipelineName(pipeline);
        } else if (run != null) {
            items = dlqItemRepository.findByRunId(run);
        } else {
            throw new DlqFilterException("A bulk requeue needs pipeline_name, run_id, or both");
        }
        int requeued = 0;
        for (DlqItemEntity item : items) {
            if (item.getPayload() == null) {
                continue; // skip poison rows in a bulk requeue
            }
            if (!foldEra(item.getPayload())) {
                // Skipped rather than failing the batch, exactly as a poison row is: a bulk requeue is
                // "re-run what can be re-run", and one un-readable archive must not deny the operator the
                // others. Logged per item, because the count alone would not say which rows were left.
                log.warn("Skipping DLQ item {} in a bulk requeue: {}", item.getId(), PRE_FOLD_REASON);
                continue;
            }
            reenqueue(item);
            dlqItemRepository.delete(item);
            requeued++;
        }
        OperatorActionLog.record("dlq_requeue_bulk", pipeline, "run=" + (run == null ? "-" : run) + " requeued=" + requeued
                + " matched=" + items.size());
        return requeued;
    }

    /**
     * Whether an archived payload is one this build can re-run: a post-fold payload names a
     * {@code pipelineGeneration} the feed actually served, a pre-fold one names {@code evaluatorVersion}
     * instead and deserializes to {@code 0}.
     *
     * <p><strong>This is still reachable, and it is worth saying which rows reach it</strong>, because the
     * lanes no longer archive a pre-fold work item at all — chunk and upsert adopt the live generation and
     * run it, and the group lane discards it to the readiness sweep (see
     * {@link com.epam.aidial.enrichment.worker.PreFoldWorkItem}). Two producers remain:
     *
     * <ul>
     *   <li><strong>Rows the 0.2 build archived before the cut-over.</strong> {@code dlq_item} is a table,
     *       not a queue: a deploy does not empty it, and a row it wrote holds a payload naming
     *       {@code evaluatorVersion} and no {@code pipelineGeneration} at all. This is the common case and
     *       the one that outlives the deploy window, since such a row survives until the 30-day sweep.</li>
     *   <li><strong>A pre-fold write whose pipeline was absent from the cache.</strong>
     *       {@code UpsertService} archives that item with its rows rather than destroying a paid-for
     *       result — and it takes that arm <em>before</em> there is a live generation to adopt, so the
     *       stored payload carries the member, at 0.</li>
     * </ul>
     *
     * <p><strong>Both the absent member and the value 0 are therefore refused</strong>, and the second half
     * is the second case above: testing presence alone would requeue it into a lane that would archive it
     * again for the same reason. 0 is unambiguous: ADAS bumps {@code generation} on every accepted mutation
     * and a registered pipeline's first value is 1, so the feed never serves it.
     *
     * <p>All three payload types carry the member, so one test covers every task name and needs no branch
     * on it. A payload that is not a JSON object at all answers false and lands in the same refusal: an
     * unreadable archive is not requeueable whatever made it unreadable.
     */
    private boolean foldEra(String payload) {
        try {
            JsonNode node = jsonMapper.readTree(payload);
            if (node == null || !node.isObject()) {
                return false;
            }
            JsonNode generation = node.get("pipelineGeneration");
            return generation != null && generation.asLong(PreFoldWorkItem.PRE_FOLD_GENERATION)
                    != PreFoldWorkItem.PRE_FOLD_GENERATION;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Re-enqueues an archived payload onto the task it came from. The payload JSON alone cannot say which
     * that is — a {@code GroupPayload} deserialized as a {@code ChunkPayload} would yield a work item with no
     * rows — so the archived {@code task_name} decides.
     *
     * <p>Scheduled with its lane's priority rather than the scheduler's default of {@code 0}, which is below
     * even {@link WorkPriority#BACKFILL}. A requeue is work an operator asked for by hand; leaving it
     * unranked put it under every automatically enqueued item, which is the opposite of what the request
     * meant — and for an archived write-back, the thing left unranked was a result already paid for.
     */
    private void reenqueue(DlqItemEntity item) {
        // Prefix the instance id with the pipeline so requeued work counts toward the pipeline's in-flight
        // backpressure (which matches on the "pipelineName#" prefix), while staying unique per DLQ item. A run's
        // item carries the run too, so if it fails again it is archived under the same run.
        String instanceId = DeterministicId.requeueInstanceId(item.getPipelineName(), item.getId(), item.getRunId());
        if (UpsertRowsTask.TASK_NAME.equals(item.getTaskName())) {
            // Requeuing a write re-attempts only the write, from the rows it already holds — no recompute.
            UpsertPayload payload = jsonMapper.readValue(item.getPayload(), UpsertPayload.class);
            scheduler.scheduleIfNotExists(
                    upsertRowsTask.task().instanceBuilder(instanceId)
                            .data(payload)
                            .priority(WorkPriority.WRITE_BACK)
                            .build(),
                    Instant.now());
            return;
        }
        if (GroupChunkTask.TASK_NAME.equals(item.getTaskName())) {
            GroupPayload payload = jsonMapper.readValue(item.getPayload(), GroupPayload.class);
            scheduler.scheduleIfNotExists(
                    groupChunkTask.task().instanceBuilder(instanceId)
                            .data(payload)
                            .priority(WorkPriority.LIVE)
                            .build(),
                    Instant.now());
            return;
        }
        ChunkPayload payload = jsonMapper.readValue(item.getPayload(), ChunkPayload.class);
        scheduler.scheduleIfNotExists(
                enrichChunkTask.task().instanceBuilder(instanceId)
                        .data(payload)
                        .priority(WorkPriority.LIVE)
                        .build(),
                Instant.now());
    }
}
