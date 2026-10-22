package com.epam.aidial.enrichment.observability;

import com.epam.aidial.enrichment.backfill.BackfillTask;
import com.epam.aidial.enrichment.class1.Class1MaterializeTask;
import com.epam.aidial.enrichment.exception.AdasContractMismatchException;
import com.epam.aidial.enrichment.exception.AdasTransportException;
import com.epam.aidial.enrichment.persistence.RunHistoryEntity;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.scan.PipelineScanTask;
import com.epam.aidial.enrichment.support.EventCapture;
import com.epam.aidial.enrichment.worker.ChunkPayload;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.epam.aidial.enrichment.worker.WorkerMdc;
import com.epam.aidial.enrichment.worker.upsert.UpsertRowsTask;
import com.github.kagkarlsson.scheduler.CurrentlyExecuting;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionMdcListenerTest {

    private final RunHistoryRepository runHistoryRepository = mock(RunHistoryRepository.class);
    private final ExecutionMdcListener listener = new ExecutionMdcListener(runHistoryRepository);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void scanTickIsNamedByItsInstanceId() {
        start(PipelineScanTask.TASK_NAME, "r1", null);

        assertThat(MDC.get(WorkerMdc.TASK)).isEqualTo(PipelineScanTask.TASK_NAME);
        assertThat(MDC.get(WorkerMdc.TASK_INSTANCE)).isEqualTo("r1");
        assertThat(MDC.get(WorkerMdc.PIPELINE_NAME)).isEqualTo("r1");
        assertThat(MDC.get(WorkerMdc.RUN_ID)).isNull();
    }

    @Test
    void class1TickAndPrefixedIdsAreNamedByThePipelinePrefix() {
        start(Class1MaterializeTask.TASK_NAME, "r-sql", null);
        assertThat(MDC.get(WorkerMdc.PIPELINE_NAME)).isEqualTo("r-sql");

        start(UpsertRowsTask.TASK_NAME, "r2#requeue#17", null);
        assertThat(MDC.get(WorkerMdc.PIPELINE_NAME)).isEqualTo("r2");
    }

    @Test
    void backfillChunkIsNamedByItsPayloadAndCarriesItsRun() {
        String id = DeterministicId.backfillChunkInstanceId("run-9", 3, "c1", 0);

        start(EnrichChunkTask.TASK_NAME, id, new ChunkPayload("r3", 3, List.of()));

        assertThat(MDC.get(WorkerMdc.PIPELINE_NAME)).isEqualTo("r3");
        assertThat(MDC.get(WorkerMdc.RUN_ID)).isEqualTo("run-9");
    }

    @Test
    void backfillBatchIsNamedByItsRun() {
        RunHistoryEntity run = new RunHistoryEntity();
        run.setId("run-4");
        run.setPipelineName("r4");
        when(runHistoryRepository.findById("run-4")).thenReturn(Optional.of(run));

        start(BackfillTask.TASK_NAME, BackfillTask.instanceId("run-4", null), "run-4");

        assertThat(MDC.get(WorkerMdc.PIPELINE_NAME)).isEqualTo("r4");
        assertThat(MDC.get(WorkerMdc.RUN_ID)).isEqualTo("run-4");
    }

    @Test
    void anUnresolvableRunStillStartsTheExecution() {
        when(runHistoryRepository.findById("run-5")).thenThrow(new IllegalStateException("db down"));

        start(BackfillTask.TASK_NAME, BackfillTask.instanceId("run-5", null), "run-5");

        assertThat(MDC.get(WorkerMdc.TASK)).isEqualTo(BackfillTask.TASK_NAME);
        assertThat(MDC.get(WorkerMdc.PIPELINE_NAME)).isNull();
    }

    @Test
    void completionRemovesExactlyTheKeysItPut() {
        MDC.put("trace_id", "t-1");
        Execution execution = start(PipelineScanTask.TASK_NAME, "r1", null);
        // Code inside the execution restores rather than removes, so the listener's key survives it.
        try (WorkerMdc.Scope ignored = WorkerMdc.scope().put(WorkerMdc.PIPELINE_NAME, "r1").put(WorkerMdc.CHUNK_ID, "c")) {
            assertThat(MDC.get(WorkerMdc.CHUNK_ID)).isEqualTo("c");
        }
        assertThat(MDC.get(WorkerMdc.PIPELINE_NAME)).isEqualTo("r1");

        listener.onExecutionComplete(ExecutionComplete.success(execution, Instant.now(), Instant.now()));

        assertThat(MDC.getCopyOfContextMap()).containsOnlyKeys("trace_id");
    }

    @Test
    void onlyFailureWithoutLoggingHandlerGetsWarnLineAndStack() {
        EventCapture capture = EventCapture.attach();
        try {
            Execution scan = start(PipelineScanTask.TASK_NAME, "r1", null);
            listener.onExecutionComplete(ExecutionComplete.failure(scan, Instant.now(), Instant.now(),
                    new IllegalStateException("boom")));
            Execution chunk = start(EnrichChunkTask.TASK_NAME, "r1#h#c0", new ChunkPayload("r1", 3, List.of()));
            listener.onExecutionComplete(ExecutionComplete.failure(chunk, Instant.now(), Instant.now(),
                    new IllegalStateException("boom")));

            List<LogEvent> lines = capture.events().stream()
                    .filter(e -> e.getMessage().getFormattedMessage().startsWith("execution failed:"))
                    .filter(e -> e.getLevel() == org.apache.logging.log4j.Level.WARN).toList();
            // The chunk's failure handler logs its retry or archive line, so the listener adds no WARN of its own.
            assertThat(lines).singleElement().satisfies(line -> {
                assertThat(line.getMessage().getFormattedMessage())
                        .contains("task=pipeline-scan", "instance=r1", "ms=", "cause=IllegalStateException");
                assertThat(line.getContextData().<String>getValue(WorkerMdc.PIPELINE_NAME)).isEqualTo("r1");
                assertThat(line.getThrown()).isNotNull();
            });
        } finally {
            capture.detach();
        }
    }

    /** A hold is rescheduled untouched and summarised by WorkHold; a WARN per held item would be the flood it prevents. */
    @Test
    void heldExecutionIsNoFailureLine() {
        EventCapture capture = EventCapture.attach();
        try {
            Execution chunk = start(EnrichChunkTask.TASK_NAME, "r1#h#c0", new ChunkPayload("r1", 3, List.of()));
            listener.onExecutionComplete(ExecutionComplete.failure(chunk, Instant.now(), Instant.now(),
                    AdasContractMismatchException.noEcho("/v1/pipelines/r1/rows")));

            assertThat(capture.events()).noneMatch(e -> e.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.WARN)
                    || e.getMessage().getFormattedMessage().startsWith("execution failed:"));
        } finally {
            capture.detach();
        }
    }

    /** A transport failure's classified message names the call and its timeout, so the tick's line needs no stack. */
    @Test
    void selfDescribingTickFailureCarriesNoStack() {
        EventCapture capture = EventCapture.attach();
        try {
            Execution tick = start(Class1MaterializeTask.TASK_NAME, "c1", null);
            listener.onExecutionComplete(ExecutionComplete.failure(tick, Instant.now(), Instant.now(),
                    AdasTransportException.of(HttpMethod.POST, "/v1/pipelines/c1/materialize", 200, 100,
                            new SocketTimeoutException("Read timed out"))));

            assertThat(capture.events()).filteredOn(e -> e.getMessage().getFormattedMessage().startsWith("execution failed:"))
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.getMessage().getFormattedMessage()).contains("adas.read-timeout-ms=100");
                        assertThat(e.getThrown()).isNull();
                    });
        } finally {
            capture.detach();
        }
    }

    @Test
    void deadExecutionIsWarning() {
        EventCapture capture = EventCapture.attach();
        try {
            listener.onExecutionDead(new Execution(Instant.now(), new TaskInstance<>(PipelineScanTask.TASK_NAME, "r1")));

            assertThat(capture.events()).anyMatch(e -> e.getLevel() == org.apache.logging.log4j.Level.WARN
                    && e.getMessage().getFormattedMessage().contains("execution dead: task=pipeline-scan instance=r1"));
        } finally {
            capture.detach();
        }
    }

    private <T> Execution start(String task, String id, T data) {
        TaskInstance<T> instance = new TaskInstance<>(task, id, data);
        CurrentlyExecuting executing = mock(CurrentlyExecuting.class);
        when(executing.getTaskInstance()).thenAnswer(invocation -> instance);
        listener.onExecutionStart(executing);
        return new Execution(Instant.now(), instance);
    }
}
