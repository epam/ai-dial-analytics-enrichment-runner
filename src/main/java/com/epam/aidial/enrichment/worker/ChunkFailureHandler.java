package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.configuration.WorkerProperties;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.exception.LogSafe;
import com.epam.aidial.enrichment.exception.TransientChunkException;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.scan.DeterministicId;
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
 * Failure handling for enrich-chunk executions per the taxonomy: a transient failure reschedules the
 * whole chunk with exponential backoff + jitter up to {@code worker.max-attempts}; on exhaustion the
 * chunk is archived to the DLQ (with its serialized payload so it can be requeued) and removed, rather
 * than db-scheduler's default silent deletion.
 *
 * <p>Ahead of both sits the <em>hold</em> arm (see {@link WorkHold}): a failure that only means "not yet"
 * takes neither the ladder nor the archive, and is never dead-lettered however long the condition lasts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@LogExecution
public class ChunkFailureHandler implements FailureHandler<ChunkPayload> {

    private final DlqItemWriter dlqItemWriter;
    private final JsonMapper jsonMapper;
    private final WorkerProperties workerProperties;

    @Override
    public void onFailure(ExecutionComplete executionComplete, ExecutionOperations<ChunkPayload> operations) {
        Execution execution = executionComplete.getExecution();
        int failures = execution.consecutiveFailures;
        String instanceId = execution.taskInstance.getId();
        Throwable cause = executionComplete.getCause().orElse(null);

        // Before the ladder and before the archive: a cache this process has not synced yet, or an ADAS
        // contract skew, is a hold, not a failure. Taking the ladder would consume the budget, and reaching
        // the archive would destroy the chunk over a startup or a deploy that is minutes from being
        // finished — irreversibly, because the scan cursor advanced in the same transaction that enqueued it.
        if (WorkHold.holdIfHeld("Chunk " + instanceId, executionComplete, operations)) {
            return;
        }

        if (failures < workerProperties.maxAttempts()) {
            Duration delay = RetryBackoff.withJitter(workerProperties.backoffBase(), failures);
            log.warn("Chunk {} failed: {} stage={} pipeline={} (consecutive failures={}); retrying in {}",
                    instanceId, ErrorMessages.classify(cause), stageOf(cause).dbValue(), pipelineNameOf(execution, instanceId),
                    failures, HumanDuration.format(delay));
            operations.reschedule(executionComplete, Instant.now().plus(delay));
        } else {
            log.error("Chunk {} exhausted after {} attempts: {} stage={} pipeline={}; archiving to DLQ", instanceId, failures,
                    ErrorMessages.classify(cause), stageOf(cause).dbValue(), pipelineNameOf(execution, instanceId), LogSafe.loggable(cause));
            try {
                archive(instanceId, payload(execution), cause);
            } catch (RuntimeException e) {
                // Never let an archival failure block removal: an un-removed execution is re-picked and
                // re-runs the still-failing side effect in a tight loop until the DB recovers.
                log.error("Failed to archive exhausted chunk {} to DLQ: {}; removing it anyway", instanceId, ErrorMessages.classify(e),
                        LogSafe.loggable(e));
            } finally {
                operations.remove();
            }
        }
    }

    private void archive(String instanceId, ChunkPayload payload, Throwable cause) {
        DlqItemEntity.DlqItemEntityBuilder item = DlqItemEntity.builder()
                .stage(stageOf(cause).dbValue())
                .error(cause != null ? cause.getMessage() : "chunk exhausted")
                .runId(DeterministicId.backfillRunIdOf(instanceId));
        if (payload != null) {
            item.pipelineName(payload.getPipelineName())
                    .pipelineGeneration(payload.getPipelineGeneration())
                    .payload(jsonMapper.writeValueAsString(payload));
        } else {
            // Defensive: an enrich-chunk execution always carries a ChunkPayload, but if one is somehow
            // absent, still supply a pipeline name so the dlq_item.pipeline_name NOT NULL constraint cannot be hit.
            item.pipelineName(pipelineNameFromInstanceId(instanceId));
        }
        dlqItemWriter.upsert(item.build());
    }

    private static String pipelineNameOf(Execution execution, String instanceId) {
        ChunkPayload payload = payload(execution);
        return payload != null ? payload.getPipelineName() : pipelineNameFromInstanceId(instanceId);
    }

    private static String pipelineNameFromInstanceId(String instanceId) {
        int hash = instanceId == null ? -1 : instanceId.indexOf('#');
        return hash > 0 ? instanceId.substring(0, hash) : "unknown";
    }

    private static ChunkPayload payload(Execution execution) {
        Object data = execution.taskInstance.getData();
        return data instanceof ChunkPayload chunk ? chunk : null;
    }

    private static DlqStage stageOf(Throwable cause) {
        TransientChunkException transient0 = unwrap(cause);
        return transient0 != null ? transient0.getStage() : DlqStage.DIAL_CALL;
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
