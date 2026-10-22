package com.epam.aidial.enrichment.worker.upsert;

import com.epam.aidial.enrichment.configuration.WorkerProperties;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.exception.LogSafe;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.scan.DeterministicId;
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
 * Failure handling for {@code upsert-rows} executions: retry the write with exponential backoff and jitter up to
 * {@code worker.max-attempts}, then archive it.
 *
 * <p>Each retry re-posts the rows the item already carries, so a long retry chain costs HTTP calls rather than
 * model calls — which is the milestone's point. An archived item is always requeueable, because its payload *is*
 * the result; requeuing it re-attempts the write without recomputing.
 *
 * <p>Ahead of both arms sits the <em>hold</em> (see {@link WorkHold}). Archiving a pending write for a
 * condition that clears by itself is the one outcome to avoid on this lane — the payload <em>is</em> the
 * paid-for result.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@LogExecution
public class UpsertFailureHandler implements FailureHandler<UpsertPayload> {

    private final DlqItemWriter dlqItemWriter;
    private final JsonMapper jsonMapper;
    private final WorkerProperties workerProperties;

    @Override
    public void onFailure(ExecutionComplete executionComplete, ExecutionOperations<UpsertPayload> operations) {
        Execution execution = executionComplete.getExecution();
        int failures = execution.consecutiveFailures;
        String instanceId = execution.taskInstance.getId();
        Throwable cause = executionComplete.getCause().orElse(null);

        // The lane where holding matters most: this item's payload IS the paid-for result, so archiving it
        // during the seconds before a restarted process has synced the registry, or during a contract skew,
        // turns a deploy into work an operator has to requeue by hand.
        if (WorkHold.holdIfHeld("Upsert item " + instanceId, executionComplete, operations)) {
            return;
        }

        if (failures < workerProperties.maxAttempts()) {
            Duration delay = RetryBackoff.withJitter(workerProperties.backoffBase(), failures);
            log.warn("Upsert item {} failed: {} stage={} pipeline={} (consecutive failures={}); retrying in {}",
                    instanceId, ErrorMessages.classify(cause), DlqStage.UPSERT.dbValue(), pipelineNameOf(execution),
                    failures, HumanDuration.format(delay));
            operations.reschedule(executionComplete, Instant.now().plus(delay));
        } else {
            log.error("Upsert item {} exhausted after {} attempts: {} stage={} pipeline={}; archiving to DLQ", instanceId, failures,
                    ErrorMessages.classify(cause), DlqStage.UPSERT.dbValue(), pipelineNameOf(execution), LogSafe.loggable(cause));
            try {
                archive(instanceId, payload(execution), cause);
            } catch (RuntimeException e) {
                log.error("Failed to archive exhausted upsert item {} to DLQ: {}; removing it anyway", instanceId,
                        ErrorMessages.classify(e), LogSafe.loggable(e));
            } finally {
                operations.remove();
            }
        }
    }

    private void archive(String instanceId, UpsertPayload payload, Throwable cause) {
        if (payload == null) {
            return;
        }
        // Upserted: a group write carries the group key, so a second exhaustion at the same pipeline generation
        // would collide with the partial unique key and lose the payload this archive exists to keep.
        dlqItemWriter.upsert(DlqItemEntity.builder()
                .pipelineName(payload.getPipelineName())
                .grainKey(payload.getGroupKey())
                .pipelineGeneration(payload.getPipelineGeneration())
                .stage(DlqStage.UPSERT.dbValue())
                .error(cause != null ? cause.getMessage() : "upsert exhausted")
                .payload(jsonMapper.writeValueAsString(payload))
                .taskName(UpsertRowsTask.TASK_NAME)
                .runId(DeterministicId.backfillRunIdOf(instanceId))
                .build());
    }

    private static String pipelineNameOf(Execution execution) {
        UpsertPayload payload = payload(execution);
        return payload != null ? payload.getPipelineName() : "-";
    }

    private static UpsertPayload payload(Execution execution) {
        Object data = execution.taskInstance.getData();
        return data instanceof UpsertPayload upsert ? upsert : null;
    }
}
