package com.epam.aidial.enrichment.worker.group;

import com.epam.aidial.enrichment.configuration.WorkerProperties;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.exception.LogSafe;
import com.epam.aidial.enrichment.exception.TransientChunkException;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.worker.HumanDuration;
import com.epam.aidial.enrichment.worker.RetryBackoff;
import com.epam.aidial.enrichment.worker.WorkHold;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

/**
 * Failure handling for {@code enrich-group} executions, mirroring
 * {@link com.epam.aidial.enrichment.worker.ChunkFailureHandler}: a transient failure reschedules the whole
 * group with exponential backoff and jitter up to {@code worker.max-attempts}; on exhaustion the item is
 * archived to the DLQ and removed rather than silently deleted.
 *
 * <p>Only <em>transient</em> failures reach here. A poison group is dead-lettered inside
 * {@link GroupWorkerService} and its execution completes normally, because replaying a deterministic failure
 * would spend a group assembly and a model call per attempt to reach the same outcome.
 *
 * <p>A group's payload is four scalars, so an archived group item is always requeueable — unlike a per-row
 * poison item, which carries no payload at all.
 *
 * <p>Ahead of both arms sits the <em>hold</em> (see {@link WorkHold}), for a failure that only means "not
 * yet" — distinct from a {@code PoisonGroupException}, which must stay permanent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@LogExecution
public class GroupFailureHandler implements FailureHandler<GroupPayload> {

    private final DlqItemWriter dlqItemWriter;
    private final JsonMapper jsonMapper;
    private final WorkerProperties workerProperties;

    @Override
    public void onFailure(ExecutionComplete executionComplete, ExecutionOperations<GroupPayload> operations) {
        Execution execution = executionComplete.getExecution();
        int failures = execution.consecutiveFailures;
        String instanceId = execution.taskInstance.getId();
        Throwable cause = executionComplete.getCause().orElse(null);

        // An unsynced pipeline cache or an ADAS contract skew is a hold, and stays distinct from a
        // PoisonGroupException — which never reaches here at all (GroupWorkerService dead-letters it and
        // completes the execution normally) and must keep being permanent if it ever did.
        if (WorkHold.holdIfHeld("Group item " + instanceId, executionComplete, operations)) {
            return;
        }

        if (failures < workerProperties.maxAttempts()) {
            Duration delay = RetryBackoff.withJitter(workerProperties.backoffBase(), failures);
            log.warn("Group item {} failed: {} stage={} pipeline={} (consecutive failures={}); retrying in {}",
                    instanceId, ErrorMessages.classify(cause), stageOf(cause).dbValue(), pipelineNameOf(execution),
                    failures, HumanDuration.format(delay));
            operations.reschedule(executionComplete, Instant.now().plus(delay));
        } else {
            log.error("Group item {} exhausted after {} attempts: {} stage={} pipeline={}; archiving to DLQ", instanceId, failures,
                    ErrorMessages.classify(cause), stageOf(cause).dbValue(), pipelineNameOf(execution), LogSafe.loggable(cause));
            try {
                archive(payload(execution), cause);
            } catch (RuntimeException e) {
                // Never let an archival failure block removal: an un-removed execution is re-picked and
                // re-runs the still-failing side effect in a tight loop until the DB recovers.
                log.error("Failed to archive exhausted group item {} to DLQ: {}; removing it anyway", instanceId,
                        ErrorMessages.classify(e), LogSafe.loggable(e));
            } finally {
                operations.remove();
            }
        }
    }

    private void archive(GroupPayload payload, Throwable cause) {
        if (payload == null) {
            // Defensive: an enrich-group execution always carries a GroupPayload.
            return;
        }
        // Upserted, not inserted: this group may already have a dead-letter row at this pipeline generation
        // (from an earlier poison outcome, or an earlier exhaustion), and the partial unique key would turn a
        // plain insert into a violation — swallowed below, discarding the payload this exists to preserve.
        dlqItemWriter.upsert(DlqItemEntity.builder()
                .pipelineName(payload.getPipelineName())
                .grainKey(payload.getGroupKey())
                .pipelineGeneration(payload.getPipelineGeneration())
                .stage(stageOf(cause).dbValue())
                .error(cause != null ? cause.getMessage() : "group item exhausted")
                .payload(jsonMapper.writeValueAsString(payload))
                .taskName(GroupChunkTask.TASK_NAME)
                .build());
    }

    private static String pipelineNameOf(Execution execution) {
        GroupPayload payload = payload(execution);
        return payload != null ? payload.getPipelineName() : "-";
    }

    private static GroupPayload payload(Execution execution) {
        Object data = execution.taskInstance.getData();
        return data instanceof GroupPayload group ? group : null;
    }

    private static DlqStage stageOf(Throwable cause) {
        TransientChunkException transient0 = unwrap(cause);
        return transient0 != null ? transient0.getStage() : DlqStage.GROUP_FETCH;
    }

    private static TransientChunkException unwrap(Throwable cause) {
        if (cause instanceof TransientChunkException t) {
            return t;
        }
        if (cause != null && cause.getCause() instanceof TransientChunkException t) {
            return t;
        }
        return null;
    }
}
