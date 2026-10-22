package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.configuration.JsonMapperConfiguration;
import com.epam.aidial.enrichment.configuration.WorkerProperties;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.TransientChunkException;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChunkFailureHandlerTest {

    private final DlqItemWriter dlqItemWriter = mock(DlqItemWriter.class);
    private final WorkerProperties workerProperties = new WorkerProperties(1, 1, 3, Duration.ofSeconds(10));
    private final ChunkFailureHandler handler =
            new ChunkFailureHandler(dlqItemWriter, new JsonMapperConfiguration().jsonMapper(), workerProperties);

    @SuppressWarnings("unchecked")
    private final ExecutionOperations<ChunkPayload> operations = mock(ExecutionOperations.class);

    @Test
    void reschedulesBeforeMaxAttempts() {
        ExecutionComplete complete = failure(2);

        handler.onFailure(complete, operations);

        verify(operations).reschedule(eq(complete), any(Instant.class));
        verify(dlqItemWriter, never()).upsert(any());
    }

    @Test
    void archivesAndRemovesOnExhaustion() {
        ExecutionComplete complete = failure(3);

        handler.onFailure(complete, operations);

        verify(dlqItemWriter).upsert(any(DlqItemEntity.class));
        verify(operations).remove();
    }

    @Test
    void removesExecutionEvenWhenArchiveFails() {
        doThrow(new RuntimeException("db down")).when(dlqItemWriter).upsert(any());
        ExecutionComplete complete = failure(3);

        // Archival failure must not propagate — otherwise the execution is never removed and re-loops.
        assertThatCode(() -> handler.onFailure(complete, operations)).doesNotThrowAnyException();
        verify(operations).remove();
    }

    @Test
    void archivesWithFallbackPipelineNameWhenPayloadAbsent() {
        TaskInstance<ChunkPayload> instance =
                new TaskInstance<>(EnrichChunkTask.TASK_NAME, "r1#h#c0", null);
        Execution execution = new Execution(Instant.now(), instance);
        execution.consecutiveFailures = 3;
        ExecutionComplete complete = ExecutionComplete.failure(execution, Instant.now(), Instant.now(),
                new TransientChunkException(DlqStage.DIAL_CALL, "boom", null));

        handler.onFailure(complete, operations);

        ArgumentCaptor<DlqItemEntity> saved = ArgumentCaptor.forClass(DlqItemEntity.class);
        verify(dlqItemWriter).upsert(saved.capture());
        // pipeline_name is NOT NULL in the schema — a null-payload archive must still supply one.
        assertThat(saved.getValue().getPipelineName()).isEqualTo("r1");
        verify(operations).remove();
    }

    @Test
    void anExhaustedBackfillChunkIsArchivedUnderItsRun() {
        ChunkPayload payload = new ChunkPayload("r1", 3, List.of(new ChunkRow("e0", Map.of("prompt", "x"))));
        TaskInstance<ChunkPayload> instance = new TaskInstance<>(EnrichChunkTask.TASK_NAME,
                DeterministicId.backfillChunkInstanceId("run-7", 3, null, 0), payload);
        Execution execution = new Execution(Instant.now(), instance);
        execution.consecutiveFailures = 3;

        handler.onFailure(ExecutionComplete.failure(execution, Instant.now(), Instant.now(),
                new TransientChunkException(DlqStage.DIAL_CALL, "boom", null)), operations);

        ArgumentCaptor<DlqItemEntity> saved = ArgumentCaptor.forClass(DlqItemEntity.class);
        verify(dlqItemWriter).upsert(saved.capture());
        assertThat(saved.getValue().getRunId()).isEqualTo("run-7");
    }

    @Test
    void anExhaustedLiveChunkNamesNoRun() {
        handler.onFailure(failure(3), operations);

        ArgumentCaptor<DlqItemEntity> saved = ArgumentCaptor.forClass(DlqItemEntity.class);
        verify(dlqItemWriter).upsert(saved.capture());
        assertThat(saved.getValue().getRunId()).isNull();
    }

    @Test
    void backoffGrowsWithFailuresAndStaysPositive() {
        Duration first = RetryBackoff.withJitter(Duration.ofSeconds(10), 1);
        Duration third = RetryBackoff.withJitter(Duration.ofSeconds(10), 3);

        assertThat(first).isPositive();
        assertThat(third).isGreaterThan(first);
    }

    private ExecutionComplete failure(int consecutiveFailures) {
        ChunkPayload payload = new ChunkPayload("r1", 3, List.of(new ChunkRow("e0", Map.of("prompt", "x"))));
        TaskInstance<ChunkPayload> instance = new TaskInstance<>(EnrichChunkTask.TASK_NAME, "r1#h#c0", payload);
        Execution execution = new Execution(Instant.now(), instance);
        execution.consecutiveFailures = consecutiveFailures;
        return ExecutionComplete.failure(execution, Instant.now(), Instant.now(),
                new TransientChunkException(DlqStage.DIAL_CALL, "boom", null));
    }
}
