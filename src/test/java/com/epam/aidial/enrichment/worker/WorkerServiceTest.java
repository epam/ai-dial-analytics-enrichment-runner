package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.adas.AdasCompatibilityState;
import com.epam.aidial.enrichment.adas.AdasWorkGate;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.configuration.WorkerProperties;
import com.epam.aidial.enrichment.dial.DialClient;
import com.epam.aidial.enrichment.dial.DialRateLimiter;
import com.epam.aidial.enrichment.dial.dto.ChatResponse;
import com.epam.aidial.enrichment.dial.dto.Choice;
import com.epam.aidial.enrichment.dial.dto.Message;
import com.epam.aidial.enrichment.dlq.DlqCircuitBreaker;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.AdasIncompatibleHoldException;
import com.epam.aidial.enrichment.exception.OutputMappingException;
import com.epam.aidial.enrichment.exception.TransientChunkException;
import com.epam.aidial.enrichment.exception.UnresolvedPlaceholderException;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.RunnerTracer;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.spend.SpendService;
import com.epam.aidial.enrichment.support.EventCapture;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.upsert.UpsertEnqueuer;
import com.epam.aidial.enrichment.worker.upsert.UpsertPayload;
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerServiceTest {

    private static final String PIPELINE_NAME = "r1";
    private static final long GENERATION = 3;

    private final PipelineCache pipelineCache = mock(PipelineCache.class);
    private final DialClient dialClient = mock(DialClient.class);
    private final DialRateLimiter dialRateLimiter = mock(DialRateLimiter.class);
    private final TemplateRenderer templateRenderer = mock(TemplateRenderer.class);
    private final DialRequestBuilder requestBuilder = mock(DialRequestBuilder.class);
    private final ResponseValidator responseValidator = mock(ResponseValidator.class);
    private final OutputMapper outputMapper = mock(OutputMapper.class);
    private final DlqItemWriter dlqItemWriter = mock(DlqItemWriter.class);
    private final DlqCircuitBreaker dlqCircuitBreaker = mock(DlqCircuitBreaker.class);
    private final SpendService spendService = mock(SpendService.class);
    private final RunnerMetrics runnerMetrics = mock(RunnerMetrics.class);
    private final WorkerProperties workerProperties = mock(WorkerProperties.class);
    private final RunnerTracer runnerTracer = new RunnerTracer(OpenTelemetry.noop());
    private final JsonMapper jsonMapper = mock(JsonMapper.class);

    // A real invoker over the same leaf mocks, so every assertion below still exercises the whole
    // render -> DIAL -> validate -> map pipeline through WorkerService. Mocking the invoker instead would
    // make these tests pass without the extracted code running at all.
    private final TransformInvoker transformInvoker = new TransformInvoker(dialClient, dialRateLimiter,
            templateRenderer, requestBuilder, responseValidator, outputMapper, spendService, runnerMetrics,
            runnerTracer, workerProperties, jsonMapper);

    private final UpsertEnqueuer upsertEnqueuer = mock(UpsertEnqueuer.class);

    // A real gate over a real state object, left compatible: these tests are about the lane, and a mocked
    // gate would let a regression that removed the gate call pass unnoticed here.
    private final AdasCompatibilityState compatibilityState = new AdasCompatibilityState();

    private final WorkerService worker = new WorkerService(pipelineCache, transformInvoker, upsertEnqueuer,
            dlqCircuitBreaker, spendService, runnerMetrics, workerProperties,
            new AdasWorkGate(compatibilityState));

    private static ChunkPayload oneRowChunk() {
        return new ChunkPayload(PIPELINE_NAME, GENERATION,
                List.of(new ChunkRow("e0", Map.of("prompt", "hi"))));
    }

    /**
     * Seeded the way the queue actually holds it: a chunk enqueued by the image that predates ADAS's
     * evaluator fold, deserialized through db-scheduler's own serializer. It does not fail to parse — the
     * retired {@code evaluatorVersion} is ignored and {@code pipelineGeneration} defaults to 0.
     */
    private static ChunkPayload preFoldChunk() {
        String preFoldJson = """
                {"pipelineName":"r1","evaluatorVersion":2,
                 "rows":[{"grainKey":"e0","inputs":{"prompt":"hi"}}]}""";
        return new JacksonSerializer()
                .deserialize(ChunkPayload.class, preFoldJson.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The irreversible one. {@code ScanPageCommitter} advanced the cursor in the transaction that enqueued
     * this chunk, so nothing will present its rows again — and the stale-declaration guard dropped it at
     * {@code info}, in the same line an ordinary knob edit produces. It is run instead, normalised onto
     * the live generation: the rows are the only thing that cannot be recovered, and the model call is
     * made under the declaration that is actually current.
     */
    @Test
    void preFoldChunkAdoptsTheLiveGenerationAndRuns() {
        stubHappyPath();

        worker.process("c0", preFoldChunk());

        verify(dialClient).chatCompletion(anyString(), anyString());
        // Nothing dead-lettered: this is not a failure, and a terminal DLQ row for it would be
        // misinformation — the work ran.
        verify(dlqItemWriter, never()).upsert(any());
        verify(dlqCircuitBreaker, never()).record(anyString(), anyInt(), anyInt());
        verify(runnerMetrics).recordPreFoldWorkItem(PreFoldWorkItem.Lane.CHUNK);
    }

    /**
     * The half of adoption that assignment buys and "skip the stale check" would not: the derived write
     * carries the generation this chunk adopted. Left at 0 it would reach the write lane as a pre-fold
     * payload in turn, which is where a paid-for result starts being handled as a relic.
     */
    @Test
    void theWriteDerivedFromAnAdoptedChunkCarriesTheAdoptedGeneration() {
        stubHappyPath();

        worker.process("c0", preFoldChunk());

        UpsertPayload write = enqueuedWrite();
        assertThat(write.getPipelineGeneration()).isEqualTo(GENERATION);
        assertThat(PreFoldWorkItem.isPreFold(write.getPipelineGeneration())).isFalse();
        // And the row's own provenance is the live generation, stamped from the pipeline rather than the
        // payload — the label WAS produced by the current declaration, so claiming it is honest here.
        assertThat(write.getRows()).singleElement().satisfies(row ->
                assertThat(row).containsEntry(ProvenanceColumns.PIPELINE_GENERATION, GENERATION));
    }

    /**
     * What bounds the downside of adopting, pinned rather than argued: a pre-fold chunk whose old inputs
     * cannot bind the current template fails at the render assertion, before the request is assembled — so
     * no DIAL call is made and no spend recorded — and the row dead-letters at {@code input_map}.
     *
     * <p>The dead-letter carries the <em>adopted</em> generation. A 0 there is a row
     * {@code DlqRequeueService} refuses to requeue permanently, because that value is the third component
     * of {@code dlq_item_row_identity_uk}.
     */
    @Test
    void anAdoptedChunkWhoseInputsCannotRenderFailsBeforeTheModelCall() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).generation(GENERATION).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(dialRateLimiter.acquire(anyString(), any())).thenReturn(true);
        when(templateRenderer.render(any(), any()))
                .thenThrow(new UnresolvedPlaceholderException(List.of("summary")));

        worker.process("c0", preFoldChunk());

        verify(dialClient, never()).chatCompletion(anyString(), anyString());
        verify(spendService, never()).record(anyString(), anyLong(), anyLong(), anyBoolean());
        assertThat(enqueuedDlqItems()).singleElement().satisfies(item -> {
            assertThat(item.getStage()).isEqualTo(DlqStage.INPUT_MAP.dbValue());
            assertThat(item.getPipelineGeneration()).isEqualTo(GENERATION);
        });
    }

    /**
     * Every row-lane stage a backfill chunk dead-letters at names its run, recovered from the chunk's own
     * instance id; the same failure on a live chunk names none.
     */
    @Test
    void everyStageOfBackfillChunkNamesItsRunAndLiveChunkNamesNone() {
        stubHappyPath();
        String backfillChunk = DeterministicId.backfillChunkInstanceId("run-7", GENERATION, "c1", 0);
        String liveChunk = DeterministicId.chunkInstanceId(PIPELINE_NAME, GENERATION, "c1", 0);

        // input_map: a placeholder nothing bound.
        when(templateRenderer.render(any(), any())).thenThrow(new UnresolvedPlaceholderException(List.of("x")));
        worker.process(backfillChunk, oneRowChunk());
        // dial_call: a permanent (non-retryable) DIAL rejection.
        doReturn("tmpl").when(templateRenderer).render(any(), any());
        doThrow(new RestClientResponseException(
                "400 bad request", 400, "Bad Request", null, null, null))
                .when(dialClient).chatCompletion(anyString(), anyString());
        worker.process(backfillChunk, oneRowChunk());
        // validate: the response still fails its schema.
        doReturn("raw").when(dialClient).chatCompletion(anyString(), anyString());
        when(responseValidator.validate(any(), any())).thenReturn(List.of("label is required"));
        worker.process(backfillChunk, oneRowChunk());
        // output_map: the mapping of a valid response fails.
        when(responseValidator.validate(any(), any())).thenReturn(List.of());
        when(outputMapper.map(any(), any())).thenThrow(new OutputMappingException("label", new IllegalStateException()));
        worker.process(backfillChunk, oneRowChunk());
        worker.process(liveChunk, oneRowChunk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DlqItemEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(upsertEnqueuer, times(5)).commit(anyString(), any(), captor.capture());
        List<DlqItemEntity> items = captor.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(items).extracting(DlqItemEntity::getStage).containsExactly(
                DlqStage.INPUT_MAP.dbValue(), DlqStage.DIAL_CALL.dbValue(), DlqStage.VALIDATE.dbValue(),
                DlqStage.OUTPUT_MAP.dbValue(), DlqStage.OUTPUT_MAP.dbValue());
        assertThat(items).extracting(DlqItemEntity::getRunId)
                .containsExactly("run-7", "run-7", "run-7", "run-7", null);
    }

    /** The guard is about generation 0 only: a genuinely stale chunk keeps its ordinary silent drop. */
    @Test
    void staleChunkIsStillDroppedWithoutAnArchive() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).generation(GENERATION + 1).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);

        worker.process("c0", oneRowChunk());

        verify(dialClient, never()).chatCompletion(anyString(), anyString());
        verify(dlqItemWriter, never()).upsert(any());
        verify(runnerMetrics, never()).recordPreFoldWorkItem(any());
    }

    /**
     * The cheap arm of the paid-work gate: a chunk enqueued <em>before</em> the skew began is still in the
     * queue during it, and the lane's shape is derive-then-write — so without this gate the derivation
     * happens first, the DIAL call is made and charged, and only the write-back is refused. Holding the item
     * costs nothing and loses nothing, because the queue is durable and the derivation is not yet due.
     */
    @Test
    void dueChunkIsHeldWithoutCallingDialWhileAdasIsIncompatible() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).generation(GENERATION).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        compatibilityState.markIncompatible("no Adas-Contract-Version echo");

        assertThatThrownBy(() -> worker.process("c0", oneRowChunk()))
                .isInstanceOf(AdasIncompatibleHoldException.class);

        verify(dialClient, never()).chatCompletion(anyString(), anyString());
        verify(upsertEnqueuer, never()).commit(anyString(), any(), anyList());
        verify(dlqItemWriter, never()).upsert(any());
    }

    /** And it runs normally the moment a matching ADAS answers — no operator action, no requeue. */
    @Test
    void theSameChunkExecutesOnceCompatibilityReturns() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).generation(GENERATION).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(dialRateLimiter.acquire(anyString(), any())).thenReturn(true);
        when(dialClient.chatCompletion(anyString(), anyString())).thenReturn("raw");
        when(jsonMapper.readValue("raw", ChatResponse.class))
                .thenReturn(new ChatResponse(List.of(new Choice(new Message("content"))), null));
        when(jsonMapper.readValue(eq("content"), eq(Object.class))).thenReturn(Map.of("label", "positive"));
        when(requestBuilder.build(any(), any(), any())).thenReturn("body");
        when(templateRenderer.render(any(), any())).thenReturn("tmpl");
        when(responseValidator.validate(any(), any())).thenReturn(List.of());
        when(outputMapper.map(any(), any())).thenReturn(Map.of("label", "positive"));
        compatibilityState.markIncompatible("no Adas-Contract-Version echo");
        compatibilityState.markCompatible();

        worker.process("c0", oneRowChunk());

        verify(dialClient).chatCompletion(anyString(), anyString());
    }

    @Test
    void deadLettersOffendingRowAndStillUpsertsGoodRows() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).generation(GENERATION).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(dialRateLimiter.acquire(anyString(), any())).thenReturn(true);
        when(dialClient.chatCompletion(anyString(), anyString())).thenReturn("raw");
        when(jsonMapper.readValue("raw", ChatResponse.class))
                .thenReturn(new ChatResponse(List.of(new Choice(new Message("content"))), null));
        when(jsonMapper.readValue(eq("content"), eq(Object.class))).thenReturn(Map.of("label", "x"));
        when(requestBuilder.build(any(), any(), any())).thenReturn("body");
        when(responseValidator.validate(any(), any())).thenReturn(List.of());
        when(outputMapper.map(any(), any())).thenReturn(Map.of("label", "x"));

        // The good rows render fine; the bad row throws an unexpected RuntimeException from rendering.
        when(templateRenderer.render(any(), any())).thenReturn("tmpl");
        when(templateRenderer.render(any(), eq(Map.of("k", "bad"))))
                .thenThrow(new IllegalStateException("boom"));

        ChunkPayload payload = new ChunkPayload(PIPELINE_NAME, GENERATION, List.of(
                new ChunkRow("g1", Map.of("k", "good1")),
                new ChunkRow("bad", Map.of("k", "bad")),
                new ChunkRow("g2", Map.of("k", "good2"))));

        // The chunk must not fail as a whole — the bad row is isolated.
        assertThatCode(() -> worker.process("chunk-1", payload)).doesNotThrowAnyException();

        // Both good rows are queued for writing, and the bad row's dead-letter commits with that enqueue.
        assertThat(enqueuedWrite().getRows()).hasSize(2);
        assertThat(enqueuedDlqItems()).hasSize(1);
        assertThat(enqueuedDlqItems().getFirst().getGrainKey()).isEqualTo("bad");
        verify(dlqCircuitBreaker).record(eq(PIPELINE_NAME), eq(1), eq(3));
    }

    @Test
    void rateLimitExhaustionFailsWholeChunkForRetry() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).generation(GENERATION).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(templateRenderer.render(any(), any())).thenReturn("tmpl");
        when(requestBuilder.build(any(), any(), any())).thenReturn("body");
        when(dialRateLimiter.acquire(anyString(), any())).thenReturn(false); // no permit → transient

        ChunkPayload payload = new ChunkPayload(PIPELINE_NAME, GENERATION,
                List.of(new ChunkRow("g1", Map.of("k", "good1"))));

        // A rate-limited row must propagate (whole chunk backs off), NOT be dead-lettered.
        assertThatCode(() -> worker.process("chunk-2", payload))
                .isInstanceOf(TransientChunkException.class);
        verify(dlqItemWriter, never()).upsertAll(any());
        verify(spendService, never()).record(anyString(), anyInt(), anyLong(), eq(true));
    }

    @Test
    void processesRowsInParallelPreservingInputOrder() {
        stubHappyPath();
        when(workerProperties.rowParallelism()).thenReturn(4);

        List<ChunkRow> rows = IntStream.range(0, 6)
                .mapToObj(i -> new ChunkRow("g" + i, Map.of("k", "v" + i)))
                .toList();
        worker.process("chunk-par", new ChunkPayload(PIPELINE_NAME, GENERATION, rows));

        List<Object> grainKeys = enqueuedWrite().getRows().stream().map(row -> row.get("event_id")).toList();
        assertThat(grainKeys).containsExactly("g0", "g1", "g2", "g3", "g4", "g5");
    }

    /**
     * A DIAL read timeout reaches the lane as RestClient's {@code ResourceAccessException}; the timing interceptor
     * logs it and rethrows unchanged, so the chunk still retries as a transient failure in its DIAL stage.
     */
    @Test
    void dialReadTimeoutIsTransientFailureInTheDialStage() {
        stubHappyPath();
        when(dialClient.chatCompletion(anyString(), anyString()))
                .thenThrow(new ResourceAccessException("I/O error on POST", new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> worker.process("chunk-timeout", oneRowChunk()))
                .isInstanceOfSatisfying(TransientChunkException.class,
                        e -> assertThat(e.getStage()).isEqualTo(DlqStage.DIAL_CALL));
        verify(dlqItemWriter, never()).upsertAll(any());
    }

    @Test
    void parallelTransientFailureFailsWholeChunkWithoutWrites() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).generation(GENERATION).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(workerProperties.rowParallelism()).thenReturn(4);
        when(templateRenderer.render(any(), any())).thenReturn("tmpl");
        when(requestBuilder.build(any(), any(), any())).thenReturn("body");
        // No DIAL permit for any row → each row throws TransientChunkException on its worker thread.
        when(dialRateLimiter.acquire(anyString(), any())).thenReturn(false);

        List<ChunkRow> rows = IntStream.range(0, 6)
                .mapToObj(i -> new ChunkRow("g" + i, Map.of("k", "v" + i)))
                .toList();

        assertThatCode(() -> worker.process("chunk-par-fail", new ChunkPayload(PIPELINE_NAME, GENERATION, rows)))
                .isInstanceOf(TransientChunkException.class);
        // Nothing is queued for writing and nothing is dead-lettered: the whole chunk retries.
        verify(upsertEnqueuer, never()).commit(anyString(), any(), any());
        verify(dlqItemWriter, never()).upsertAll(any());
    }

    @Test
    void propagatesCorrelationMdcToWorkerThreads() {
        stubHappyPath();
        when(workerProperties.rowParallelism()).thenReturn(4);

        List<Map<String, String>> snapshots = Collections.synchronizedList(new ArrayList<>());
        when(dialClient.chatCompletion(anyString(), anyString())).thenAnswer(invocation -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            snapshots.add(context == null ? Map.of() : context);
            return "raw";
        });

        List<ChunkRow> rows = List.of(
                new ChunkRow("g0", Map.of("k", "v0")),
                new ChunkRow("g1", Map.of("k", "v1")));
        worker.process("chunk-corr", new ChunkPayload(PIPELINE_NAME, GENERATION, rows));

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots).allSatisfy(context -> {
            assertThat(context).containsEntry("pipeline_name", PIPELINE_NAME);
            assertThat(context).containsEntry("chunk_id", "chunk-corr");
            assertThat(context).containsEntry("pipeline_generation", "3");
        });
        assertThat(snapshots.stream().map(context -> context.get("grain_key")).toList())
                .containsExactlyInAnyOrder("g0", "g1");
        // MDC is cleaned up on the calling thread after the chunk completes.
        assertThat(MDC.get("pipeline_name")).isNull();
    }

    /**
     * The render-time placeholder assertion, from the lane's side: the failure has to reach the DLQ and stop
     * there. What is asserted is mostly what does <em>not</em> happen — no DIAL call, and no spend — because
     * that is the whole value of catching it before the request is assembled. A version handshake catches
     * "wrong build"; this catches "right build, wrong declaration", and the declaration can be wrong without
     * any skew at all: ADAS validates placeholders at arm and at preview, not on a write that leaves the
     * pipeline disabled.
     *
     * <p>Dead-lettered at {@code input_map}, not {@code output_map}: an unbound name is a fault in what the
     * transform reads, and the stage is what points an operator at the half of the declaration that is
     * wrong.
     */
    @Test
    void anUnboundPlaceholderDeadLettersTheRowBeforeAnyDialCall() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).generation(GENERATION).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(dialRateLimiter.acquire(anyString(), any())).thenReturn(true);
        when(templateRenderer.render(any(), any()))
                .thenThrow(new UnresolvedPlaceholderException(List.of("missing_var")));

        assertThatCode(() -> worker.process("c-unbound", oneRowChunk())).doesNotThrowAnyException();

        assertThat(enqueuedDlqItems()).singleElement().satisfies(item -> {
            assertThat(item.getStage()).isEqualTo("input_map");
            assertThat(item.getGrainKey()).isEqualTo("e0");
            assertThat(item.getError()).contains("missing_var");
            assertThat(item.getPipelineGeneration()).isEqualTo(GENERATION);
        });
        verify(dialClient, never()).chatCompletion(anyString(), anyString());
        verify(spendService, never()).record(anyString(), anyLong(), anyLong(), anyBoolean());
        verify(spendService, never()).record(anyString(), anyLong(), anyLong(), anyString(), anyBoolean());
    }

    /**
     * A broken declaration fails every row, and the lane keeps going: the pipeline is not stopped, the DLQ
     * fills with one item per grain, and the operator sees which declaration is at fault rather than a
     * pipeline that has silently gone quiet.
     */
    @Test
    void brokenDeclarationDeadLettersEveryRowWithoutFailingTheChunk() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).generation(GENERATION).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(dialRateLimiter.acquire(anyString(), any())).thenReturn(true);
        when(templateRenderer.render(any(), any()))
                .thenThrow(new UnresolvedPlaceholderException(List.of("missing_var")));

        ChunkPayload payload = new ChunkPayload(PIPELINE_NAME, GENERATION, List.of(
                new ChunkRow("g1", Map.of("k", "a")),
                new ChunkRow("g2", Map.of("k", "b"))));

        assertThatCode(() -> worker.process("c-all-bad", payload)).doesNotThrowAnyException();

        assertThat(enqueuedDlqItems()).hasSize(2);
        assertThat(enqueuedWrite()).isNull();
        verify(dialClient, never()).chatCompletion(anyString(), anyString());
    }

    /**
     * With no row written there is no "computed" line, so the dead-letter WARN is the only trace the chunk leaves
     * above DEBUG. It names the pipeline, the count and the stage — and never the row's content.
     */
    @Test
    void chunkThatDeadLettersEveryRowWarnsWithCountAndStage() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).generation(GENERATION).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(dialRateLimiter.acquire(anyString(), any())).thenReturn(true);
        when(templateRenderer.render(any(), any()))
                .thenThrow(new UnresolvedPlaceholderException(List.of("missing_var")));
        ChunkPayload payload = new ChunkPayload(PIPELINE_NAME, GENERATION, List.of(
                new ChunkRow("g1", Map.of("k", "secret-row-content")),
                new ChunkRow("g2", Map.of("k", "secret-row-content"))));

        EventCapture capture = EventCapture.attach();
        try {
            worker.process("c-all-dlq", payload);

            assertThat(capture.events()).filteredOn(event -> event.getLevel() == Level.WARN)
                    .extracting(event -> event.getMessage().getFormattedMessage())
                    .contains("Chunk c-all-dlq for pipeline r1 dead-lettered 2 of 2 row(s) at stage(s) input_map");
            assertThat(capture.events()).extracting(EventCapture::rendered)
                    .noneMatch(text -> text.contains("secret-row-content"));
        } finally {
            capture.detach();
        }
    }

    private void stubHappyPath() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).generation(GENERATION).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(dialRateLimiter.acquire(anyString(), any())).thenReturn(true);
        when(dialClient.chatCompletion(anyString(), anyString())).thenReturn("raw");
        when(jsonMapper.readValue("raw", ChatResponse.class))
                .thenReturn(new ChatResponse(List.of(new Choice(new Message("content"))), null));
        when(jsonMapper.readValue(eq("content"), eq(Object.class))).thenReturn(Map.of("label", "x"));
        when(templateRenderer.render(any(), any())).thenReturn("tmpl");
        when(requestBuilder.build(any(), any(), any())).thenReturn("body");
        when(responseValidator.validate(any(), any())).thenReturn(List.of());
        when(outputMapper.map(any(), any())).thenReturn(Map.of("label", "x"));
    }

    /**
     * An aligned target's scan row carries {@code __partition_v}; the write row must hand it back as the same
     * string, or ADAS refuses the row (422 {@code validation_error}) after its model call was paid for.
     */
    @Test
    void writeRowEchoesThePartitionValueUnchanged() {
        stubHappyPath();

        worker.process("c0", new ChunkPayload(PIPELINE_NAME, GENERATION,
                List.of(new ChunkRow("e0", Map.of("prompt", "hi"), "2026-10-15T10:00:00.000Z"))));

        assertThat(enqueuedWrite().getRows()).singleElement().satisfies(row ->
                assertThat(row).containsEntry("__partition_v", "2026-10-15T10:00:00.000Z"));
    }

    /** An unaligned target refuses the member as a system column, so a row without a value never names it. */
    @Test
    void writeRowOmitsThePartitionValueWhenTheRowHasNone() {
        stubHappyPath();

        worker.process("c0", oneRowChunk());

        assertThat(enqueuedWrite().getRows()).singleElement().satisfies(row ->
                assertThat(row).doesNotContainKey("__partition_v").containsEntry("event_id", "e0"));
    }

    /**
     * A chunk queued before contract version 4 carries no partition value. It deserializes through the queue's own
     * serializer and is processed as before, with no {@code __partition_v} on its write row.
     */
    @Test
    void chunkQueuedBeforeThePartitionEchoDeserializesAndRuns() {
        stubHappyPath();
        String queuedJson = """
                {"pipelineName":"r1","pipelineGeneration":3,
                 "rows":[{"grainKey":"e0","inputs":{"prompt":"hi"}}]}""";
        ChunkPayload payload = new JacksonSerializer()
                .deserialize(ChunkPayload.class, queuedJson.getBytes(StandardCharsets.UTF_8));

        assertThat(payload.getRows()).singleElement().extracting(ChunkRow::getPartitionValue).isNull();

        worker.process("c0", payload);

        assertThat(enqueuedWrite().getRows()).singleElement().satisfies(row ->
                assertThat(row).doesNotContainKey("__partition_v").containsEntry("event_id", "e0"));
    }

    /** A row without a value serializes exactly as before the field existed; one with a value round-trips. */
    @Test
    void chunkRowSerializesThePartitionValueOnlyWhenPresent() {
        JacksonSerializer serializer = new JacksonSerializer();

        String without = new String(serializer.serialize(oneRowChunk()), StandardCharsets.UTF_8);
        ChunkPayload with = new ChunkPayload(PIPELINE_NAME, GENERATION,
                List.of(new ChunkRow("e0", Map.of("prompt", "hi"), "2026-10-15T10:00:00.000Z")));
        ChunkPayload roundTripped = serializer.deserialize(ChunkPayload.class, serializer.serialize(with));

        assertThat(without).doesNotContain("partitionValue");
        assertThat(roundTripped.getRows()).singleElement().extracting(ChunkRow::getPartitionValue)
                .isEqualTo("2026-10-15T10:00:00.000Z");
    }

    /** The write item the compute phase queued — the write itself is a separate execution now. */
    private UpsertPayload enqueuedWrite() {
        ArgumentCaptor<UpsertPayload> captor = ArgumentCaptor.forClass(UpsertPayload.class);
        verify(upsertEnqueuer).commit(anyString(), captor.capture(), anyList());
        return captor.getValue();
    }

    /** The per-row dead-letters the compute phase committed alongside that enqueue. */
    @SuppressWarnings("unchecked")
    private List<DlqItemEntity> enqueuedDlqItems() {
        ArgumentCaptor<List<DlqItemEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(upsertEnqueuer).commit(anyString(), any(), captor.capture());
        return captor.getValue();
    }
}
