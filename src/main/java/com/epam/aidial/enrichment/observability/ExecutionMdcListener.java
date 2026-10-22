package com.epam.aidial.enrichment.observability;

import com.epam.aidial.enrichment.backfill.BackfillTask;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.exception.HeldWorkException;
import com.epam.aidial.enrichment.exception.LogSafe;
import com.epam.aidial.enrichment.persistence.RunHistoryEntity;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.worker.ChunkPayload;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.epam.aidial.enrichment.worker.WorkerMdc;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.group.GroupPayload;
import com.epam.aidial.enrichment.worker.upsert.UpsertPayload;
import com.epam.aidial.enrichment.worker.upsert.UpsertRowsTask;
import com.github.kagkarlsson.scheduler.CurrentlyExecuting;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Carries a db-scheduler execution's identity — {@code task}, {@code task_instance}, {@code pipeline_name} and, for
 * backfill work, {@code run_id} — in the MDC for the <em>whole</em> execution, and gives each failed execution
 * exactly one runner failure line.
 *
 * <p>A listener rather than an {@code ExecutionInterceptor}: an interceptor wraps only the handler, while the
 * library's failure line and the task's {@code FailureHandler} run after it returns. {@code onExecutionStart} and
 * {@code onExecutionComplete} bracket all of it, on the one worker thread — {@code onExecutionComplete} from a
 * {@code finally} — so every record of a failure, the scheduler's own included, names its pipeline.
 *
 * <p>One stack per failure: a task whose failure handler decides retry or dead-letter logs the stack itself, so this
 * line carries only the classified cause for those; for the others (the recurring scan and Class-1 ticks) this line
 * is the only place the failure is described, and it carries the stack — unless the classified cause is a curated
 * message that already says what failed, such as a transport failure naming the call and its timeout. Never the
 * stack of a failure whose cause chain holds an HTTP rejection, which would print the response body
 * ({@link LogSafe}). A held execution is logged at DEBUG only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionMdcListener extends AbstractSchedulerListener {

    /** Tasks whose {@code FailureHandler} logs the failure itself — cause, stage, pipeline and the one stack. */
    private static final Set<String> HANDLER_LOGS_FAILURE = Set.of(
            EnrichChunkTask.TASK_NAME, GroupChunkTask.TASK_NAME, UpsertRowsTask.TASK_NAME, BackfillTask.TASK_NAME);

    /** The scope opened at an execution's start, closed at its completion on the same worker thread. */
    private static final ThreadLocal<WorkerMdc.Scope> SCOPE = new ThreadLocal<>();

    private final RunHistoryRepository runHistoryRepository;

    @Override
    public void onExecutionStart(CurrentlyExecuting currentlyExecuting) {
        closeScope();
        TaskInstance<?> instance = currentlyExecuting.getTaskInstance();
        WorkerMdc.Scope scope = WorkerMdc.scope()
                .put(WorkerMdc.TASK, instance.getTaskName())
                .put(WorkerMdc.TASK_INSTANCE, instance.getId());
        SCOPE.set(scope);
        try {
            String runId = runIdOf(instance);
            if (runId != null) {
                scope.put(WorkerMdc.RUN_ID, runId);
            }
            String pipelineName = pipelineNameOf(instance, runId);
            if (pipelineName != null) {
                scope.put(WorkerMdc.PIPELINE_NAME, pipelineName);
            }
        } catch (RuntimeException e) {
            // Identity is best effort; it must never stop the execution it describes.
            log.debug("Could not resolve the identity of execution {}: {}", instance.getId(), ErrorMessages.classify(e));
        }
    }

    @Override
    public void onExecutionComplete(ExecutionComplete executionComplete) {
        try {
            if (executionComplete.getResult() == ExecutionComplete.Result.FAILED) {
                logFailure(executionComplete);
            }
        } finally {
            closeScope();
        }
    }

    /**
     * A held execution is not a failure: it is rescheduled untouched, and {@code WorkHold} summarises the holds, so a
     * line per held item here would be the flood that summary exists to prevent.
     */
    private static void logFailure(ExecutionComplete executionComplete) {
        Execution execution = executionComplete.getExecution();
        Throwable cause = executionComplete.getCause().orElse(null);
        String task = execution.getTaskName();
        long ms = executionComplete.getDuration().toMillis();
        if (HeldWorkException.isHeld(cause)) {
            log.debug("execution held: task={} instance={} ms={} cause={}", task, execution.getId(), ms, ErrorMessages.classify(cause));
            return;
        }
        if (HANDLER_LOGS_FAILURE.contains(task)) {
            // The handler's retry or archive line is this failure's one line; a second WARN would say it twice.
            log.debug("execution failed: task={} instance={} ms={} cause={}", task, execution.getId(), ms, ErrorMessages.classify(cause));
            return;
        }
        log.warn("execution failed: task={} instance={} ms={} cause={}", task, execution.getId(), ms, ErrorMessages.classify(cause),
                ErrorMessages.isSelfDescribing(cause) ? null : LogSafe.loggable(cause));
    }

    @Override
    public void onExecutionDead(Execution execution) {
        log.warn("execution dead: task={} instance={} picked_by={} last_heartbeat={}",
                execution.getTaskName(), execution.getId(), execution.pickedBy, execution.lastHeartbeat);
    }

    private static void closeScope() {
        WorkerMdc.Scope previous = SCOPE.get();
        if (previous != null) {
            SCOPE.remove();
            previous.close();
        }
    }

    private static String runIdOf(TaskInstance<?> instance) {
        if (BackfillTask.TASK_NAME.equals(instance.getTaskName())) {
            Object data = instance.getData();
            return data == null ? null : data.toString();
        }
        return DeterministicId.backfillRunIdOf(instance.getId());
    }

    /**
     * The pipeline an execution works for: its payload's, where it carries one; the run's, for a backfill batch;
     * otherwise the instance id's pipeline prefix — the whole id for a scan or Class-1 tick. A backfill chunk's id
     * starts with the run namespace rather than a pipeline, but that chunk always carries its payload.
     */
    private String pipelineNameOf(TaskInstance<?> instance, String runId) {
        Object data = instance.getData();
        if (data instanceof ChunkPayload chunk) {
            return chunk.getPipelineName();
        }
        if (data instanceof GroupPayload group) {
            return group.getPipelineName();
        }
        if (data instanceof UpsertPayload upsert) {
            return upsert.getPipelineName();
        }
        if (BackfillTask.TASK_NAME.equals(instance.getTaskName())) {
            return runId == null ? null : runHistoryRepository.findById(runId).map(RunHistoryEntity::getPipelineName).orElse(null);
        }
        String id = instance.getId();
        int hash = id.indexOf('#');
        if (hash == 0) {
            return null;
        }
        return hash > 0 ? id.substring(0, hash) : id;
    }
}
