package com.epam.aidial.enrichment.observability;

import com.epam.aidial.enrichment.adas.AdasCompatibilityState;
import com.epam.aidial.enrichment.adas.AdasContract;
import com.epam.aidial.enrichment.adas.AdasMaterializeClient;
import com.epam.aidial.enrichment.adas.AdasUpsertClient;
import com.epam.aidial.enrichment.adas.AdasWorkGate;
import com.epam.aidial.enrichment.backfill.BackfillBatchFailureHandler;
import com.epam.aidial.enrichment.backfill.BackfillTask;
import com.epam.aidial.enrichment.backfill.RunState;
import com.epam.aidial.enrichment.class1.Class1MaterializeExecutor;
import com.epam.aidial.enrichment.class1.Class1Properties;
import com.epam.aidial.enrichment.configuration.AdasProperties;
import com.epam.aidial.enrichment.configuration.JsonMapperConfiguration;
import com.epam.aidial.enrichment.configuration.ScanProperties;
import com.epam.aidial.enrichment.configuration.WorkerProperties;
import com.epam.aidial.enrichment.dlq.DlqCircuitBreaker;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.TransientChunkException;
import com.epam.aidial.enrichment.observability.status.PipelineActivity;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.persistence.GroupStateWriter;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.persistence.PipelineCursorWriter;
import com.epam.aidial.enrichment.persistence.RunHistoryEntity;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.epam.aidial.enrichment.runtime.PipelineRunOutcome;
import com.epam.aidial.enrichment.scan.PipelineScanTask;
import com.epam.aidial.enrichment.support.EventCapture;
import com.epam.aidial.enrichment.support.SettableClock;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.ChunkFailureHandler;
import com.epam.aidial.enrichment.worker.ChunkPayload;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.group.GroupFailureHandler;
import com.epam.aidial.enrichment.worker.group.GroupPayload;
import com.epam.aidial.enrichment.worker.upsert.UpsertFailureHandler;
import com.epam.aidial.enrichment.worker.upsert.UpsertPayload;
import com.epam.aidial.enrichment.worker.upsert.UpsertRowsTask;
import com.epam.aidial.enrichment.worker.upsert.UpsertService;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every failure path logs an HTTP rejection by its classified form only: no record — message or stack trace —
 * carries the upstream body, and a failure gets at most one stack, from the site that decides it.
 */
class FailureLogBodySafetyTest {

    private static final String MARKER = "BODY-MARKER-4b1e";
    private static final String PIPELINE = "r-safe";

    private final WorkerProperties workerProperties = new WorkerProperties(1, 1, 3, Duration.ofSeconds(10));
    private final JsonMapper jsonMapper = new JsonMapperConfiguration().jsonMapper();
    private EventCapture capture;

    @BeforeEach
    void attach() {
        capture = EventCapture.attach();
    }

    @AfterEach
    void detach() {
        capture.detach();
    }

    @Test
    void exhaustedChunkWithDialRejectionLogsNeitherBodyNorStack() {
        ChunkFailureHandler handler = new ChunkFailureHandler(mock(DlqItemWriter.class), jsonMapper, workerProperties);
        ChunkPayload payload = new ChunkPayload(PIPELINE, 3, List.of());

        handler.onFailure(failure(EnrichChunkTask.TASK_NAME, PIPELINE + "#h#c0", payload, dialRejection(), 3), operations());

        assertBodyFree();
        assertThat(line("exhausted")).contains("HTTP 503", "stage=dial_call", "pipeline=" + PIPELINE);
        assertThat(stacks()).isZero();
    }

    @Test
    void retriedChunkNamesCauseStageAndPipelineWithoutStack() {
        ChunkFailureHandler handler = new ChunkFailureHandler(mock(DlqItemWriter.class), jsonMapper, workerProperties);
        ChunkPayload payload = new ChunkPayload(PIPELINE, 3, List.of());

        handler.onFailure(failure(EnrichChunkTask.TASK_NAME, PIPELINE + "#h#c0", payload, dialRejection(), 1), operations());

        assertBodyFree();
        assertThat(line("retrying in")).contains("failed: ", "503", "stage=dial_call", "pipeline=" + PIPELINE);
        assertThat(stacks()).isZero();
    }

    @Test
    void exhaustedChunkWithPlainFailureKeepsItsOneStack() {
        ChunkFailureHandler handler = new ChunkFailureHandler(mock(DlqItemWriter.class), jsonMapper, workerProperties);
        ChunkPayload payload = new ChunkPayload(PIPELINE, 3, List.of());

        handler.onFailure(failure(EnrichChunkTask.TASK_NAME, PIPELINE + "#h#c0", payload,
                new TransientChunkException(DlqStage.DIAL_CALL, new IllegalStateException("socket closed")), 3), operations());

        assertThat(stacks()).isEqualTo(1);
    }

    @Test
    void exhaustedGroupWithDialRejectionIsBodyFree() {
        GroupFailureHandler handler = new GroupFailureHandler(mock(DlqItemWriter.class), jsonMapper, workerProperties);
        GroupPayload payload = new GroupPayload(PIPELINE, 3, "g1", 1);

        handler.onFailure(failure(GroupChunkTask.TASK_NAME, PIPELINE + "#g#h", payload, dialRejection(), 1), operations());
        handler.onFailure(failure(GroupChunkTask.TASK_NAME, PIPELINE + "#g#h", payload, dialRejection(), 3), operations());

        assertBodyFree();
        assertThat(line("retrying in")).contains("stage=dial_call", "pipeline=" + PIPELINE);
        assertThat(stacks()).isZero();
    }

    @Test
    void exhaustedUpsertWithAnAdasRejectionIsBodyFree() {
        UpsertFailureHandler handler = new UpsertFailureHandler(mock(DlqItemWriter.class), jsonMapper, workerProperties);
        UpsertPayload payload = new UpsertPayload(PIPELINE, 3, List.of(Map.of("event_id", "e0")));
        TransientChunkException cause = new TransientChunkException(DlqStage.UPSERT, HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY, body(), StandardCharsets.UTF_8));

        handler.onFailure(failure(UpsertRowsTask.TASK_NAME, PIPELINE + "#h#c0#w", payload, cause, 1), operations());
        handler.onFailure(failure(UpsertRowsTask.TASK_NAME, PIPELINE + "#h#c0#w", payload, cause, 3), operations());

        assertBodyFree();
        assertThat(line("retrying in")).contains("stage=upsert", "pipeline=" + PIPELINE);
        assertThat(stacks()).isZero();
    }

    @Test
    void failedBackfillBatchWithAnAdasRejectionIsBodyFree() {
        RunHistoryRepository repository = mock(RunHistoryRepository.class);
        RunHistoryEntity run = new RunHistoryEntity();
        run.setId("run-1");
        run.setPipelineName(PIPELINE);
        run.setState(RunState.RUNNING.dbValue());
        when(repository.findById("run-1")).thenReturn(Optional.of(run));
        when(repository.recordBatchFailureIfRunning(eq("run-1"), anyString(), any())).thenReturn(1);
        BackfillBatchFailureHandler handler = new BackfillBatchFailureHandler(repository, mock(RunnerMetrics.class), workerProperties);

        handler.onFailure(failure(BackfillTask.TASK_NAME, BackfillTask.instanceId("run-1", null), "run-1", adasRejection(), 1),
                operations());

        assertBodyFree();
        assertThat(line("retrying in")).contains("pipeline=" + PIPELINE, "ADAS 422");
        assertThat(stacks()).isZero();
    }

    @Test
    void permanentlyRejectedUpsertIsDeadLetteredWithoutTheBodyInTheLog() {
        PipelineCache pipelineCache = mock(PipelineCache.class);
        when(pipelineCache.get(PIPELINE)).thenReturn(TestPipelines.builder(PIPELINE).generation(3).build());
        AdasUpsertClient upsertClient = mock(AdasUpsertClient.class);
        when(upsertClient.upsert(anyString(), anyList())).thenThrow(adasRejection());
        UpsertService service = new UpsertService(pipelineCache, upsertClient, mock(GroupStateWriter.class),
                mock(DlqItemWriter.class), jsonMapper, mock(DlqCircuitBreaker.class), mock(RunnerMetrics.class),
                new RunnerTracer(OpenTelemetry.noop()), new AdasWorkGate(new AdasCompatibilityState()),
                new SettableClock(Instant.parse("2026-09-29T12:00:00Z")), mock(PipelineActivity.class));

        service.write(PIPELINE + "#h#c0#w", new UpsertPayload(PIPELINE, 3, List.of(Map.of("event_id", "e0"))));

        assertBodyFree();
        assertThat(line("Dead-lettered upsert")).contains("ADAS 422");
    }

    @Test
    void failedMaterializeTickLogsNoStackOfItsOwn() {
        PipelineCache pipelineCache = mock(PipelineCache.class);
        when(pipelineCache.get(PIPELINE)).thenReturn(TestPipelines.class1(PIPELINE, 1, TestPipelines.EVERY_5_MINUTES));
        AdasMaterializeClient materializeClient = mock(AdasMaterializeClient.class);
        when(materializeClient.materialize(eq(PIPELINE), any(), anyInt())).thenThrow(adasRejection());
        PipelineCursorRepository cursors = mock(PipelineCursorRepository.class);
        when(cursors.findById(PIPELINE)).thenReturn(Optional.empty());
        Class1MaterializeExecutor executor = new Class1MaterializeExecutor(pipelineCache, materializeClient, cursors,
                new PipelineCursorWriter(cursors),
                new Class1Properties(10_000, 100_000),
                new ScanProperties(1000, 10_000, 25, 1000, 3),
                mock(PipelinePauseRegistry.class), mock(RunnerMetrics.class), new RunnerTracer(OpenTelemetry.noop()),
                new AdasProperties("http://adas", "", 1000, 180_000), mock(PipelineActivity.class),
                mock(PipelineRunOutcome.class));

        assertThatThrownBy(() -> executor.execute(PIPELINE)).isInstanceOf(HttpClientErrorException.class);

        assertBodyFree();
        assertThat(line("Materialize tick failed")).contains(PIPELINE, "ADAS 422");
        assertThat(stacks()).isZero();
    }

    @Test
    void listenerFailureLineForAnHttpRejectionCarriesNoStack() {
        ExecutionMdcListener listener = new ExecutionMdcListener(mock(RunHistoryRepository.class));

        listener.onExecutionComplete(failure(PipelineScanTask.TASK_NAME, PIPELINE, null, adasRejection(), 0));

        assertBodyFree();
        assertThat(line("execution failed")).contains("task=pipeline-scan", "instance=" + PIPELINE, "ADAS 422");
        assertThat(stacks()).isZero();
    }

    private static HttpServerErrorException dialRejectionCause() {
        return HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", HttpHeaders.EMPTY,
                body(), StandardCharsets.UTF_8);
    }

    /** A DIAL 503 as the worker raises it: wrapped for a whole-item retry. */
    private static TransientChunkException dialRejection() {
        return new TransientChunkException(DlqStage.DIAL_CALL, dialRejectionCause());
    }

    /** An ADAS 422: ADAS's own answer, so it carries the contract echo. */
    private static HttpClientErrorException adasRejection() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(AdasContract.HEADER, AdasContract.VERSION_VALUE);
        return HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_CONTENT, "Unprocessable", headers, body(),
                StandardCharsets.UTF_8);
    }

    private static byte[] body() {
        return ("{\"message\":\"upstream quoted the request: " + MARKER + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static <T> ExecutionComplete failure(String task, String id, T data, Throwable cause, int consecutiveFailures) {
        Execution execution = new Execution(Instant.now(), new TaskInstance<>(task, id, data));
        execution.consecutiveFailures = consecutiveFailures;
        return ExecutionComplete.failure(execution, Instant.now(), Instant.now(), cause);
    }

    @SuppressWarnings("unchecked")
    private static <T> ExecutionOperations<T> operations() {
        return mock(ExecutionOperations.class);
    }

    private void assertBodyFree() {
        assertThat(capture.events()).isNotEmpty();
        assertThat(capture.events()).extracting(EventCapture::rendered).noneMatch(text -> text.contains(MARKER));
    }

    private String line(String fragment) {
        return capture.events().stream().map(e -> e.getMessage().getFormattedMessage())
                .filter(m -> m.contains(fragment)).findFirst()
                .orElseThrow(() -> new AssertionError("no line containing '" + fragment + "' in " + messages()));
    }

    private long stacks() {
        return capture.events().stream().filter(e -> e.getThrown() != null).count();
    }

    private List<String> messages() {
        return capture.events().stream().map(LogEvent::getMessage).map(Message::getFormattedMessage).toList();
    }
}
