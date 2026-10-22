package com.epam.aidial.enrichment.worker.upsert;

import com.epam.aidial.enrichment.configuration.JsonMapperConfiguration;
import com.epam.aidial.enrichment.configuration.WorkerProperties;
import com.epam.aidial.enrichment.dlq.DlqStage;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** An exhausted write is archived under the backfill run its instance id names, or under none for the live path. */
class UpsertFailureHandlerTest {

    private final DlqItemWriter dlqItemWriter = mock(DlqItemWriter.class);
    private final WorkerProperties workerProperties = new WorkerProperties(1, 1, 3, Duration.ofSeconds(10));
    private final UpsertFailureHandler handler =
            new UpsertFailureHandler(dlqItemWriter, new JsonMapperConfiguration().jsonMapper(), workerProperties);

    @SuppressWarnings("unchecked")
    private final ExecutionOperations<UpsertPayload> operations = mock(ExecutionOperations.class);

    @Test
    void anExhaustedBackfillWriteIsArchivedUnderItsRun() {
        String instanceId = DeterministicId.upsertInstanceId(DeterministicId.backfillChunkInstanceId("run-7", 3, null, 0));

        DlqItemEntity archived = exhaust(instanceId);

        assertThat(archived.getRunId()).isEqualTo("run-7");
        assertThat(archived.getTaskName()).isEqualTo(UpsertRowsTask.TASK_NAME);
        assertThat(archived.getStage()).isEqualTo(DlqStage.UPSERT.dbValue());
        assertThat(archived.getPayload()).isNotNull().contains("e0");
        verify(operations).remove();
    }

    @Test
    void anExhaustedLiveWriteNamesNoRun() {
        String instanceId = DeterministicId.upsertInstanceId(DeterministicId.chunkInstanceId("r1", 3, null, 0));

        DlqItemEntity archived = exhaust(instanceId);

        assertThat(archived.getRunId()).isNull();
        assertThat(archived.getTaskName()).isEqualTo(UpsertRowsTask.TASK_NAME);
        verify(operations).remove();
    }

    private DlqItemEntity exhaust(String instanceId) {
        UpsertPayload payload = new UpsertPayload("r1", 3, List.of(Map.of("event_id", "e0", "label", "x")));
        Execution execution = new Execution(Instant.now(),
                new TaskInstance<>(UpsertRowsTask.TASK_NAME, instanceId, payload));
        execution.consecutiveFailures = workerProperties.maxAttempts();

        handler.onFailure(ExecutionComplete.failure(execution, Instant.now(), Instant.now(),
                new IllegalStateException("write failed")), operations);

        ArgumentCaptor<DlqItemEntity> archived = ArgumentCaptor.forClass(DlqItemEntity.class);
        verify(dlqItemWriter).upsert(archived.capture());
        return archived.getValue();
    }
}
