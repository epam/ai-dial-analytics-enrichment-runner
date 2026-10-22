package com.epam.aidial.enrichment.backfill;

import com.epam.aidial.enrichment.configuration.WorkerProperties;
import com.epam.aidial.enrichment.exception.PipelineCacheColdException;
import com.epam.aidial.enrichment.exception.ScanPageColumnException;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.persistence.RunHistoryEntity;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A backfill batch that fails is recorded, counted and retried on the jittered backoff — never archived, never
 * budgeted, and never left to retry silently at a flat interval while its run reads {@code running}.
 */
class BackfillBatchFailureHandlerTest {

    private static final String RUN_ID = "run-1";

    private final RunHistoryRepository repository = mock(RunHistoryRepository.class);
    private final RunnerMetrics metrics = mock(RunnerMetrics.class);
    private final BackfillBatchFailureHandler handler = new BackfillBatchFailureHandler(repository, metrics,
            new WorkerProperties(1, 1, 5, Duration.ofSeconds(10)));

    @SuppressWarnings("unchecked")
    private final ExecutionOperations<String> operations = mock(ExecutionOperations.class);

    @Test
    void failureIsRecordedClassifiedCountedAndRetriedOnTheBackoff() {
        runningRun();
        when(repository.recordBatchFailureIfRunning(eq(RUN_ID), anyString(), any())).thenReturn(1);
        ExecutionComplete complete = failure(new ScanPageColumnException("p1", "event_id", "grain_key"), 0);
        Instant before = Instant.now();

        handler.onFailure(complete, operations);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(repository).recordBatchFailureIfRunning(eq(RUN_ID), message.capture(), any());
        assertThat(message.getValue()).contains("event_id");
        verify(metrics).incrementBackfillBatchFailures("p1");
        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(operations).reschedule(eq(complete), next.capture());
        // The jittered ladder starts at the base, not db-scheduler's flat five minutes.
        assertThat(next.getValue()).isBetween(before.plusSeconds(9), before.plusSeconds(25));
        verify(operations, never()).remove();
    }

    @Test
    void heldBatchRecordsNothingAndCountsNothing() {
        runningRun();

        handler.onFailure(failure(new PipelineCacheColdException("backfill batch " + RUN_ID), 0), operations);

        verify(repository, never()).recordBatchFailureIfRunning(anyString(), anyString(), any());
        verify(metrics, never()).incrementBackfillBatchFailures(anyString());
        verify(operations).reschedule(any(ExecutionComplete.class), any(Instant.class));
    }

    @Test
    void failureOfRunNoLongerRunningRemovesTheExecution() {
        runningRun();
        when(repository.recordBatchFailureIfRunning(eq(RUN_ID), anyString(), any())).thenReturn(0);

        handler.onFailure(failure(new IllegalStateException("boom"), 2), operations);

        verify(operations).remove();
        verify(operations, never()).reschedule(any(ExecutionComplete.class), any(Instant.class));
        verify(metrics, never()).incrementBackfillBatchFailures(anyString());
    }

    @Test
    void recordingFailureStillReschedules() {
        when(repository.findById(RUN_ID)).thenThrow(new IllegalStateException("postgres down"));
        ExecutionComplete complete = failure(new IllegalStateException("boom"), 1);

        handler.onFailure(complete, operations);

        verify(operations).reschedule(eq(complete), any(Instant.class));
    }

    private void runningRun() {
        RunHistoryEntity run = new RunHistoryEntity();
        run.setId(RUN_ID);
        run.setPipelineName("p1");
        run.setState(RunState.RUNNING.dbValue());
        when(repository.findById(RUN_ID)).thenReturn(Optional.of(run));
    }

    private static ExecutionComplete failure(Throwable cause, int consecutiveFailures) {
        TaskInstance<String> instance = new TaskInstance<>(BackfillTask.TASK_NAME,
                BackfillTask.instanceId(RUN_ID, null), RUN_ID);
        Execution execution = new Execution(Instant.now(), instance);
        execution.consecutiveFailures = consecutiveFailures;
        return ExecutionComplete.failure(execution, Instant.now(), Instant.now(), cause);
    }
}
