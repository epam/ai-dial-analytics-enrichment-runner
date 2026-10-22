package com.epam.aidial.enrichment.backfill;

import com.epam.aidial.enrichment.configuration.WorkerProperties;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.exception.LogSafe;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import com.epam.aidial.enrichment.persistence.RunHistoryEntity;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
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

import java.time.Duration;
import java.time.Instant;

/**
 * Failure handling for {@code backfill-run} batches, in place of db-scheduler's flat, silent
 * {@code onFailureRetryLater()} — under which a batch that could never succeed retried every five minutes forever
 * while the run read {@code running}, {@code rows_done} froze and no counter moved.
 *
 * <p>In order:
 * <ol>
 *   <li>a <em>held</em> condition (a cache not yet synced, an incompatible ADAS) is held exactly as the work-item
 *       lanes hold one — see {@link WorkHold} — recording nothing and counting nothing;</li>
 *   <li>otherwise the run is loaded, and a classified description of the failure is recorded on it if it is
 *       still running ({@link ErrorMessages#classify}; never a raw message). A run that is no longer running has
 *       its execution removed, and nothing is recorded or retried;</li>
 *   <li>the per-pipeline batch-failure counter is incremented;</li>
 *   <li>the batch is rescheduled on the worker's jittered backoff, so a transient upstream failure recovers in
 *       seconds while a sustained one is probed less and less often.</li>
 * </ol>
 * Recording and counting are best-effort: a failure there still reschedules, as {@code ChunkFailureHandler}'s
 * archive does. A failure to reschedule falls back to db-scheduler's dead-execution revival.
 *
 * <p><strong>Deliberately omitted</strong>, each for a reason:
 * <ul>
 *   <li><b>No attempt budget.</b> Stopping the chain without moving the run's state would leave it
 *       {@code running} with no chain — un-resumable by its own state guard, and blocking every future backfill
 *       for the pipeline. Auto-pause after N failures waits until the counter says what N is.</li>
 *   <li><b>No archive.</b> The payload is a run id; there is nothing to dead-letter and nothing to requeue.</li>
 *   <li><b>No terminal {@code failed} state.</b> Most batch failures are transient; failing after a run of
 *       errors destroys the operator's options — a good cursor and window — exactly when they want to fix the
 *       cause and continue. What the run owes an operator is visibility, not a verdict; cancel stays available.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@LogExecution
public class BackfillBatchFailureHandler implements FailureHandler<String> {

    private final RunHistoryRepository runHistoryRepository;
    private final RunnerMetrics runnerMetrics;
    private final WorkerProperties workerProperties;

    @Override
    public void onFailure(ExecutionComplete executionComplete, ExecutionOperations<String> operations) {
        Execution execution = executionComplete.getExecution();
        String runId = String.valueOf(execution.taskInstance.getData());
        int failures = execution.consecutiveFailures;
        Throwable cause = executionComplete.getCause().orElse(null);

        if (WorkHold.holdIfHeld("Backfill batch " + runId, executionComplete, operations)) {
            return;
        }

        String message = ErrorMessages.classify(cause);
        String pipelineName = "-";
        try {
            RunHistoryEntity run = runHistoryRepository.findById(runId).orElse(null);
            if (run == null || runHistoryRepository.recordBatchFailureIfRunning(runId, message, Instant.now()) == 0) {
                log.info("Backfill batch {} failed after its run stopped running: {}; not retrying it", runId, message);
                operations.remove();
                return;
            }
            pipelineName = run.getPipelineName();
            runnerMetrics.incrementBackfillBatchFailures(pipelineName);
        } catch (RuntimeException e) {
            // The classified cause without a stack: the batch's own failure is the one that gets the stack.
            log.error("Failed to record the failure of backfill batch {}: {}; retrying it anyway", runId, ErrorMessages.classify(e));
        }

        Duration delay = RetryBackoff.withJitter(workerProperties.backoffBase(), failures);
        log.warn("Backfill batch {} failed: {} stage=backfill pipeline={} (consecutive failures={}); retrying in {}",
                runId, message, pipelineName, failures, HumanDuration.format(delay), LogSafe.loggable(cause));
        operations.reschedule(executionComplete, Instant.now().plus(delay));
    }
}
