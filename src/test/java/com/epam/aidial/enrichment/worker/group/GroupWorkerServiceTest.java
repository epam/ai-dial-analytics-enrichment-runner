package com.epam.aidial.enrichment.worker.group;

import com.epam.aidial.enrichment.adas.AdasCompatibilityState;
import com.epam.aidial.enrichment.adas.AdasGroupFetchClient;
import com.epam.aidial.enrichment.adas.AdasUpsertClient;
import com.epam.aidial.enrichment.adas.AdasWorkGate;
import com.epam.aidial.enrichment.adas.dto.GroupFetchResponse;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ReadyWhenDto;
import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.epam.aidial.enrichment.configuration.JsonMapperConfiguration;
import com.epam.aidial.enrichment.dlq.DlqCircuitBreaker;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.AdasIncompatibleHoldException;
import com.epam.aidial.enrichment.exception.AdasTransportException;
import com.epam.aidial.enrichment.exception.InputMappingException;
import com.epam.aidial.enrichment.exception.OutputMappingException;
import com.epam.aidial.enrichment.exception.TransientChunkException;
import com.epam.aidial.enrichment.exception.UnresolvedPlaceholderException;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.RunnerTracer;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.persistence.GroupStateWriter;
import com.epam.aidial.enrichment.spend.SpendService;
import com.epam.aidial.enrichment.support.SettableClock;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.PreFoldWorkItem;
import com.epam.aidial.enrichment.worker.ProvenanceColumns;
import com.epam.aidial.enrichment.worker.TransformInvoker;
import com.epam.aidial.enrichment.worker.upsert.UpsertEnqueuer;
import com.epam.aidial.enrichment.worker.upsert.UpsertPayload;
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupWorkerServiceTest {

    private static final String PIPELINE_NAME = "g1";
    private static final String GROUP_KEY = "chat-a";
    private static final long GROUP_VERSION = 1_782_400_795_816L;
    private static final long GENERATION = 3;
    private static final LocalDate TODAY = LocalDate.parse("2026-07-28");

    private final PipelineCache pipelineCache = mock(PipelineCache.class);
    private final AdasGroupFetchClient groupFetchClient = mock(AdasGroupFetchClient.class);
    private final GroupInputAssembler inputAssembler = mock(GroupInputAssembler.class);
    private final TransformInvoker transformInvoker = mock(TransformInvoker.class);
    private final AdasUpsertClient upsertClient = mock(AdasUpsertClient.class);
    private final GroupStateWriter groupStateWriter = mock(GroupStateWriter.class);
    private final DlqItemWriter dlqItemWriter = mock(DlqItemWriter.class);
    private final DlqCircuitBreaker dlqCircuitBreaker = mock(DlqCircuitBreaker.class);
    private final SpendService spendService = mock(SpendService.class);
    private final RunnerMetrics runnerMetrics = mock(RunnerMetrics.class);
    private final RunnerTracer runnerTracer = new RunnerTracer(OpenTelemetry.noop());
    private final GroupProperties groupProperties =
            new GroupProperties(500, 0, Duration.ofDays(7), Duration.ofMinutes(10), 200, 60_000, Duration.ofMinutes(1));

    private final SettableClock clock = new SettableClock(Instant.parse("2026-07-28T12:00:00Z"));

    private final UpsertEnqueuer upsertEnqueuer = mock(UpsertEnqueuer.class);

    // A real gate over a real state object, left compatible — see WorkerServiceTest.
    private final AdasCompatibilityState compatibilityState = new AdasCompatibilityState();

    private final GroupWorkerService worker = new GroupWorkerService(pipelineCache, groupFetchClient,
            inputAssembler, upsertEnqueuer, transformInvoker, groupStateWriter, dlqItemWriter,
            dlqCircuitBreaker, spendService, runnerMetrics, runnerTracer, groupProperties,
            new AdasWorkGate(compatibilityState),
            new JsonMapperConfiguration().jsonMapper(), clock);

    /**
     * The lane it would be easiest to overlook, and the most expensive per item: this one assembles a
     * group's members through ADAS and then makes <em>one</em> model call over them, so a group pipeline
     * left ungated keeps paying for the largest calls the runner makes for the whole length of a skew.
     *
     * <p>Its {@code group-rows} call precedes its DIAL call, so the exception arm would cover it well even
     * without this gate — but only for the <em>first</em> item, which is an argument for having both arms,
     * not for skipping the cheap one here.
     */
    @Test
    void dueGroupIsHeldWithoutFetchingMembersOrCallingDial() {
        cachePipeline();
        compatibilityState.markIncompatible("no Adas-Contract-Version echo");

        assertThatThrownBy(() -> worker.process("g0", payload()))
                .isInstanceOf(AdasIncompatibleHoldException.class);

        verify(groupFetchClient, never()).fetch(anyString(), anyString(), anyInt());
        verify(transformInvoker, never()).derive(any(), any(), any());
        // Not dead-lettered, not stamped: nothing about the group's evaluation state changed.
        verify(dlqItemWriter, never()).upsert(any());
        verify(groupStateWriter, never()).stampComputed(anyString(), anyString(), anyLong(), any(), anyBoolean());
    }

    /**
     * And the same item runs the moment a matching ADAS answers — no requeue, no operator action. Asserted
     * on this lane too, and not left to the other two, because it is the lane whose gate was added last and
     * the one whose held items are the most expensive to have to re-derive by hand.
     */
    @Test
    void theSameGroupExecutesOnceCompatibilityReturns() {
        cachePipeline();
        when(groupFetchClient.fetch(eq(PIPELINE_NAME), eq(GROUP_KEY), anyInt()))
                .thenReturn(new GroupFetchResponse(List.of(), false));
        compatibilityState.markIncompatible("no Adas-Contract-Version echo");
        compatibilityState.markCompatible();

        worker.process("g0", payload());

        verify(groupFetchClient).fetch(eq(PIPELINE_NAME), eq(GROUP_KEY), anyInt());
    }

    /**
     * An ADAS read timeout on the member fetch is an {@link AdasTransportException}, which stays a
     * {@code RestClientException} precisely so this arm keeps classifying it transient in its own stage.
     */
    @Test
    void groupFetchTimeoutIsTransientFailureInTheGroupFetchStage() {
        cachePipeline();
        when(groupFetchClient.fetch(eq(PIPELINE_NAME), eq(GROUP_KEY), anyInt())).thenThrow(AdasTransportException.of(
                HttpMethod.POST, "/v1/pipelines/" + PIPELINE_NAME + "/group-rows", 200, 200, new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> worker.process("g0", payload()))
                .isInstanceOfSatisfying(TransientChunkException.class,
                        e -> assertThat(e.getStage()).isEqualTo(DlqStage.GROUP_FETCH));
        verify(transformInvoker, never()).derive(any(), any(), any());
        verify(dlqItemWriter, never()).upsert(any());
    }

    @Test
    void writesOneRowKeyedByGroupKeyWithFullProvenance() {
        cachePipeline();
        stubMembers(false);
        stubAssembly(false);
        when(transformInvoker.derive(any(), any(), any())).thenReturn(Map.of("topic", "billing"));

        worker.process("item-1", payload());

        Map<String, Object> row = upsertedRow();
        assertThat(row).containsEntry("chat_id", GROUP_KEY);
        assertThat(row).containsEntry("topic", "billing");
        assertThat(row).containsEntry(ProvenanceColumns.PIPELINE_GENERATION, GENERATION);
        assertThat(row).containsEntry(ProvenanceColumns.MODEL, "gpt-4o");
        assertThat(row).containsKey(ProvenanceColumns.ENRICHED_AT);
        assertThat(row).containsEntry(ProvenanceColumns.GROUP_VERSION, GROUP_VERSION);
        assertThat(row).containsEntry(ProvenanceColumns.TRUNCATED, false);
    }

    @Test
    void writesTheGrainKeyWhenGroupByNamesAnEnrichmentColumn() {
        // group_by resolves to the grain rather than spelling it: the key is reachable only through an
        // enrichment of the read source, so the pipeline declares the qualified name the scan projects it under.
        // The enrichment table itself has no such column, so writing that name back is a permanent 422
        // (write_column_not_allowed) that dead-letters every group of the pipeline.
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(TestPipelines.builder(PIPELINE_NAME)
                .generation(GENERATION)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("usage_client_identity.client_session_id")
                .grainKey("client_session_id")
                .readyWhen(new ReadyWhenDto(null, "10m", null, null))
                .model("gpt-4o")
                .build());
        stubMembers(false);
        stubAssembly(false);
        when(transformInvoker.derive(any(), any(), any())).thenReturn(Map.of("topic", "billing"));

        worker.process("item-1", payload());

        Map<String, Object> row = upsertedRow();
        assertThat(row).containsEntry("client_session_id", GROUP_KEY);
        assertThat(row).doesNotContainKey("usage_client_identity.client_session_id");
    }

    @Test
    void adasCapIsHandedToTheAssemblerThatFoldsIt() {
        cachePipeline();
        stubMembers(true);          // ADAS trimmed the group
        stubAssembly(true);
        when(transformInvoker.derive(any(), any(), any())).thenReturn(Map.of("topic", "billing"));

        worker.process("item-1", payload());

        // The fetch's verdict is an input to the assembly, not something the worker re-folds afterwards: one
        // truth for "the judgement is partial", so the prompt's caveat and the row's provenance agree.
        verify(inputAssembler).assemble(any(), any(), eq(true));
        assertThat(upsertedRow()).containsEntry(ProvenanceColumns.TRUNCATED, true);
        // The stamp now happens on the successful write, so the payload must carry what it needs.
        assertThat(enqueuedWrite().isTruncated()).isTrue();
        assertThat(enqueuedWrite().getGroupKey()).isEqualTo(GROUP_KEY);
        assertThat(enqueuedWrite().getGroupVersion()).isEqualTo(GROUP_VERSION);
    }

    @Test
    void trimmedAssemblyMarksTruncated() {
        cachePipeline();
        stubMembers(false);
        stubAssembly(true);
        when(transformInvoker.derive(any(), any(), any())).thenReturn(Map.of("topic", "billing"));

        worker.process("item-1", payload());

        assertThat(upsertedRow()).containsEntry(ProvenanceColumns.TRUNCATED, true);
    }

    @Test
    void emptyGroupWritesNothingButStampsClean() {
        cachePipeline();
        when(groupFetchClient.fetch(eq(PIPELINE_NAME), eq(GROUP_KEY), anyInt()))
                .thenReturn(new GroupFetchResponse(List.of(), false));

        worker.process("item-1", payload());

        verify(upsertClient, never()).upsert(anyString(), any());
        verify(dlqItemWriter, never()).upsert(any());
        verify(groupStateWriter).stampComputed(eq(PIPELINE_NAME), eq(GROUP_KEY), eq(GROUP_VERSION), any(),
                eq(false));
        // Reported to the breaker as the completed group it is: a denominator counting only the groups that
        // reach a model would read a pipeline whose groups mostly resolve empty as far more failure-prone.
        verify(dlqCircuitBreaker).recordGroupSuccess(PIPELINE_NAME);
    }

    @Test
    void staleItemMakesNoModelCall() {
        // The pipeline has been re-declared at a higher generation since the sweep enqueued this item.
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(groupPipeline(GENERATION + 1));

        worker.process("item-1", payload());

        verify(groupFetchClient, never()).fetch(anyString(), anyString(), anyInt());
        verify(transformInvoker, never()).derive(any(), any(), any());
        verify(upsertClient, never()).upsert(anyString(), any());
    }

    /**
     * The one lane that does <em>not</em> adopt. A {@code GroupPayload} is keys only and the group is
     * still dirty, so the readiness sweep re-derives it at the current generation by itself — and running
     * this item too would pay twice, because the sweep's in-flight dedup keys on a group instance id that
     * embeds the generation and so does not see a pre-fold item at all.
     *
     * <p>Discarded with <strong>no dead-letter row</strong>: a terminal, un-requeueable row for work the
     * sweep has already redone is misinformation, and it inflates {@code runner.dlq.size}. The WARN and
     * the lane-tagged counter are the whole record.
     */
    @Test
    void preFoldGroupItemIsDiscardedWithNoDeadLetterRow() {
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(groupPipeline(GENERATION));

        worker.process("item-1", preFoldPayload());

        verify(groupFetchClient, never()).fetch(anyString(), anyString(), anyInt());
        verify(transformInvoker, never()).derive(any(), any(), any());
        verify(dlqItemWriter, never()).upsert(any());
        verify(dlqCircuitBreaker, never()).recordGroupFailure(anyString());
        verify(runnerMetrics).recordPreFoldWorkItem(PreFoldWorkItem.Lane.GROUP);
        // Left dirty on purpose: stamping it clean — at either version — would deny the sweep the
        // re-derivation that is this lane's whole recovery.
        verify(groupStateWriter, never()).stampComputed(anyString(), anyString(), anyLong(), any(),
                anyBoolean());
        verify(groupStateWriter, never()).stampComputedAndCharge(anyString(), anyString(), anyLong(), any(),
                anyBoolean(), any());
    }

    @Test
    void pipelineAbsentFromCacheDropsTheItem() {
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(null);

        worker.process("item-1", payload());

        verify(groupFetchClient, never()).fetch(anyString(), anyString(), anyInt());
    }

    /**
     * A refusal the runner rules permanent must still be <em>recoverable</em>. The classification is made
     * from the HTTP status alone, so a capacity refusal that arrives as a 4xx — an upstream that could not
     * meet a read budget — is indistinguishable from a rejection of the request as posed and lands here.
     * Archiving without a payload made that misreading destroy the work silently, and produced the perverse
     * result that a clean deterministic refusal was worse to receive than a socket timeout, which is ruled
     * transient and archived requeueably.
     */
    @Test
    void refusedGroupFetchIsDeadLetteredWithItsPayload() {
        cachePipeline();
        when(groupFetchClient.fetch(anyString(), anyString(), anyInt()))
                .thenThrow(new RestClientResponseException("422 pipeline_read_budget_exceeded",
                        HttpStatusCode.valueOf(422), "Unprocessable Content", new HttpHeaders(), null, null));

        worker.process("item-1", payload());

        DlqItemEntity item = deadLettered();
        assertThat(item.getStage()).isEqualTo(DlqStage.GROUP_FETCH.dbValue());
        assertThat(item.getTaskName()).isEqualTo(GroupChunkTask.TASK_NAME);
        assertThat(item.getPayload())
                .as("a group payload is four scalars; storing it makes a misclassification cost a requeue, "
                        + "not the work")
                .contains(GROUP_KEY)
                .contains(PIPELINE_NAME);
        // No model call was made, so the ceiling is not charged — only stamped, to stop the re-sweep loop.
        verify(transformInvoker, never()).derive(any(), any(), any());
    }

    @Test
    void mappingFailureDeadLettersAtGroupMapAndStampsToStopResweeping() {
        cachePipeline();
        stubMembers(false);
        stubAssembly(false);
        stubCharge(true);
        when(transformInvoker.derive(any(), any(), any()))
                .thenThrow(new OutputMappingException("topic", new IllegalStateException("no such field")));

        worker.process("item-1", payload());

        DlqItemEntity item = deadLettered();
        assertThat(item.getStage()).isEqualTo(DlqStage.GROUP_MAP.dbValue());
        assertThat(item.getGrainKey()).isEqualTo(GROUP_KEY);
        verify(upsertClient, never()).upsert(anyString(), any());
        // Stamped despite failing: otherwise the sweep re-enqueues the same doomed evaluation forever. And
        // charged, because the model call was spent whether or not its response proved usable.
        verify(groupStateWriter).stampComputedAndCharge(eq(PIPELINE_NAME), eq(GROUP_KEY), eq(GROUP_VERSION), any(),
                eq(false), eq(TODAY));
        verify(dlqCircuitBreaker).recordGroupFailure(PIPELINE_NAME);
    }

    @Test
    void successPathDefersTheStampToTheWrite() {
        // "Clean" must mean "written": stamping at compute time would mark a group clean before its row landed,
        // so a write that then failed permanently would leave a group the sweep never revisits.
        cachePipeline();
        stubMembers(false);
        stubAssembly(false);
        when(transformInvoker.derive(any(), any(), any())).thenReturn(Map.of("topic", "billing"));

        worker.process("item-1", payload());

        assertThat(enqueuedWrite().isGroupWrite()).isTrue();
        verify(groupStateWriter, never()).stampComputedAndCharge(anyString(), anyString(), anyLong(), any(),
                anyBoolean(), any());
        verify(groupStateWriter, never()).stampComputed(anyString(), anyString(), anyLong(), any(),
                anyBoolean());
        // And defers the breaker outcome with it: the write reports the success, so reporting one here too
        // would put two outcomes in the window for one group attempt and halve its measured failure rate.
        verify(dlqCircuitBreaker, never()).recordGroupSuccess(anyString());
    }

    @Test
    void emptyGroupChargesNothing() {
        // No model call was spent, so charging it would let a misconfigured pipeline exhaust a ceiling without
        // ever producing a label.
        cachePipeline();
        when(groupFetchClient.fetch(eq(PIPELINE_NAME), eq(GROUP_KEY), anyInt()))
                .thenReturn(new GroupFetchResponse(List.of(), false));

        worker.process("item-1", payload());

        verify(groupStateWriter).stampComputed(eq(PIPELINE_NAME), eq(GROUP_KEY), eq(GROUP_VERSION), any(),
                eq(false));
        verify(groupStateWriter, never()).stampComputedAndCharge(anyString(), anyString(), anyLong(), any(),
                anyBoolean(), any());
    }

    @Test
    void assemblyFailureChargesNothing() {
        // Assembly precedes the model call, so this group cost nothing to fail.
        cachePipeline();
        stubMembers(false);
        when(inputAssembler.assemble(any(), any(), anyBoolean()))
                .thenThrow(new InputMappingException("text", new IllegalStateException("bad jsonata")));

        worker.process("item-1", payload());

        assertThat(deadLettered().getStage()).isEqualTo(DlqStage.GROUP_MAP.dbValue());
        verify(groupStateWriter).stampComputed(eq(PIPELINE_NAME), eq(GROUP_KEY), eq(GROUP_VERSION), any(),
                eq(false));
        verify(groupStateWriter, never()).stampComputedAndCharge(anyString(), anyString(), anyLong(), any(),
                anyBoolean(), any());
    }

    @Test
    void chargeSupersededByNewerEvaluationIsReported() {
        // Two versions of this group were in flight and completed out of order. The older one's stamp is
        // refused — correctly — but its model call was spent, so it is charged, and the fact is reported
        // rather than swallowed: it means the group's evaluations are outrunning the sweep interval.
        cachePipeline();
        stubMembers(false);
        stubAssembly(false);
        stubCharge(false);
        when(transformInvoker.derive(any(), any(), any()))
                .thenThrow(new OutputMappingException("topic", new IllegalStateException("no such field")));

        worker.process("item-1", payload());

        verify(runnerMetrics).recordSupersededGroupEvaluation();
    }

    /**
     * The group lane's half of the render-time placeholder assertion. The interesting assertion is the
     * <em>charge</em>: the render happens before the request is assembled, so no model call was made and the
     * evaluation must not count against the group's daily ceiling. Left to the generic catch-all this would
     * be archived as {@code modelCalled}, letting one bad placeholder exhaust a ceiling without ever
     * producing a label.
     */
    @Test
    void anUnboundPlaceholderDeadLettersTheGroupWithoutChargingIt() {
        cachePipeline();
        stubMembers(false);
        stubAssembly(false);
        when(transformInvoker.derive(any(), any(), any()))
                .thenThrow(new UnresolvedPlaceholderException(List.of("missing_var")));

        worker.process("item-1", payload());

        ArgumentCaptor<DlqItemEntity> captor = ArgumentCaptor.forClass(DlqItemEntity.class);
        verify(dlqItemWriter).upsert(captor.capture());
        assertThat(captor.getValue().getStage()).isEqualTo("group_map");
        assertThat(captor.getValue().getError()).contains("missing_var");
        assertThat(captor.getValue().getPipelineGeneration()).isEqualTo(GENERATION);

        // Stamped clean so the sweep does not re-run the same doomed evaluation forever, but NOT charged.
        verify(groupStateWriter).stampComputed(eq(PIPELINE_NAME), eq(GROUP_KEY), eq(GROUP_VERSION), any(),
                eq(false));
        verify(groupStateWriter, never()).stampComputedAndCharge(anyString(), anyString(), anyLong(), any(),
                anyBoolean(), any());
        verify(upsertEnqueuer, never()).commit(anyString(), any(), anyList());
    }

    private void cachePipeline() {
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(groupPipeline(GENERATION));
    }

    private void stubMembers(boolean truncated) {
        when(groupFetchClient.fetch(eq(PIPELINE_NAME), eq(GROUP_KEY), anyInt())).thenReturn(
                new GroupFetchResponse(List.of(Map.of("chat_id", GROUP_KEY, "request_body", "hi")), truncated));
    }

    @Test
    void contentCutToFitTheBudgetIsCounted() {
        cachePipeline();
        stubMembers(false);
        stubAssembly(true, true);
        stubCharge(true);
        when(transformInvoker.derive(any(), any(), any())).thenReturn(Map.of("topic", "billing"));

        worker.process("item-1", payload());

        verify(runnerMetrics).recordInputBudgetCut();
    }

    @Test
    void truncatedGroupWhoseMembersWereNotCutIsNotCounted() {
        // The trap this counter exists to avoid: a pipeline whose member policy narrows every group reports
        // truncated on all of them, so counting `truncated` would report a pipeline working as designed as a
        // budget problem.
        cachePipeline();
        stubMembers(true);
        stubAssembly(true, false);
        stubCharge(true);
        when(transformInvoker.derive(any(), any(), any())).thenReturn(Map.of("topic", "billing"));

        worker.process("item-1", payload());

        verify(runnerMetrics, never()).recordInputBudgetCut();
    }

    private void stubAssembly(boolean truncated) {
        stubAssembly(truncated, false);
    }

    private void stubAssembly(boolean truncated, boolean itemsCut) {
        when(inputAssembler.assemble(any(), any(), anyBoolean())).thenReturn(
                new GroupInputAssembler.AssembledInput(List.of(Map.of("text", "hi")), truncated, 0, itemsCut));
    }

    /** The writer's default outcome: charged, and this evaluation's stamp is the newest. */
    private void stubCharge(boolean stamped) {
        when(groupStateWriter.stampComputedAndCharge(anyString(), anyString(), anyLong(), any(), anyBoolean(),
                any())).thenReturn(new GroupStateWriter.ChargeOutcome(true, stamped));
    }

    private static GroupPayload payload() {
        return new GroupPayload(PIPELINE_NAME, GENERATION, GROUP_KEY, GROUP_VERSION);
    }

    /** As the queue holds it: enqueued by the pre-fold image, read back through db-scheduler's serializer. */
    private static GroupPayload preFoldPayload() {
        String preFoldJson = "{\"pipelineName\":\"" + PIPELINE_NAME + "\",\"evaluatorVersion\":2,"
                + "\"groupKey\":\"" + GROUP_KEY + "\",\"groupVersion\":" + GROUP_VERSION + "}";
        return new JacksonSerializer()
                .deserialize(GroupPayload.class, preFoldJson.getBytes(StandardCharsets.UTF_8));
    }

    private static PipelineDto groupPipeline(long generation) {
        return TestPipelines.builder(PIPELINE_NAME)
                .generation(generation)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .grainKey("chat_id")
                .readyWhen(new ReadyWhenDto(null, "10m", null, null))
                .model("gpt-4o")
                .build();
    }

    /** The write item the compute phase queued: the row plus the group facts its write will stamp. */
    private UpsertPayload enqueuedWrite() {
        ArgumentCaptor<UpsertPayload> captor = ArgumentCaptor.forClass(UpsertPayload.class);
        verify(upsertEnqueuer).commit(anyString(), captor.capture(), any());
        return captor.getValue();
    }

    private Map<String, Object> upsertedRow() {
        UpsertPayload payload = enqueuedWrite();
        assertThat(payload.getRows()).hasSize(1);
        return payload.getRows().getFirst();
    }

    private DlqItemEntity deadLettered() {
        ArgumentCaptor<DlqItemEntity> captor = ArgumentCaptor.forClass(DlqItemEntity.class);
        verify(dlqItemWriter).upsert(captor.capture());
        return captor.getValue();
    }
}
