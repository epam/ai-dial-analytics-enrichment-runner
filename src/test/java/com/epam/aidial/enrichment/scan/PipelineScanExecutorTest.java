package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.adas.AdasScanClient;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ScanResponse;
import com.epam.aidial.enrichment.configuration.ScanProperties;
import com.epam.aidial.enrichment.dlq.DlqCircuitBreaker;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.exception.PipelineCacheColdException;
import com.epam.aidial.enrichment.exception.PipelineRecreatedException;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.RunnerTracer;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.runtime.PipelineRunOutcome;
import com.epam.aidial.enrichment.scan.group.GroupDirtyMarkCommitter;
import com.epam.aidial.enrichment.scan.group.GroupMarkBatch;
import com.epam.aidial.enrichment.scan.group.GroupMarkBuilder;
import com.epam.aidial.enrichment.spend.SpendPauseGate;
import com.epam.aidial.enrichment.support.CapturingAppender;
import com.epam.aidial.enrichment.support.TestPages;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.ChunkPayload;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineScanExecutorTest {

    private static final String PIPELINE_NAME = "r1";

    private final PipelineCache pipelineCache = mock(PipelineCache.class);
    private final AdasScanClient scanClient = mock(AdasScanClient.class);
    private final ChunkBuilder chunkBuilder = mock(ChunkBuilder.class);
    private final ScanPageCommitter committer = mock(ScanPageCommitter.class);
    private final PipelineCursorRepository cursorRepository = mock(PipelineCursorRepository.class);
    private final BackpressureGate backpressureGate = mock(BackpressureGate.class);
    private final PipelinePauseRegistry pauseRegistry = mock(PipelinePauseRegistry.class);
    private final DlqCircuitBreaker dlqCircuitBreaker = mock(DlqCircuitBreaker.class);
    private final SpendPauseGate spendPauseGate = mock(SpendPauseGate.class);
    private final RunnerMetrics runnerMetrics = mock(RunnerMetrics.class);
    private final ScanProperties properties =
            new ScanProperties(1000, 10_000, 25, 1000, 5);

    private final RunnerTracer runnerTracer = new RunnerTracer(OpenTelemetry.noop());

    private final GroupMarkBuilder groupMarkBuilder = mock(GroupMarkBuilder.class);
    private final GroupDirtyMarkCommitter groupDirtyMarkCommitter = mock(GroupDirtyMarkCommitter.class);

    private final PipelineRunOutcome runOutcome = new PipelineRunOutcome(Clock.systemUTC());

    private final PipelineScanExecutor executor = new PipelineScanExecutor(pipelineCache, scanClient, chunkBuilder,
            committer, groupMarkBuilder, groupDirtyMarkCommitter, cursorRepository, backpressureGate,
            properties, pauseRegistry, dlqCircuitBreaker, spendPauseGate, runnerMetrics, runnerTracer, runOutcome);

    /** One INFO line per fire, totalled over its pages, with what an operator needs to read the fire's effect. */
    @Test
    void eachFireLogsOneSummaryTotalledOverItsPages() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        List<Map<String, Object>> rows = List.of(Map.of("event_id", "e1"), Map.of("event_id", "e2"),
                Map.of("event_id", "e3"), Map.of("event_id", "e4"));
        when(scanClient.scan(anyString(), any(), anyInt()))
                .thenReturn(new ScanResponse(rows, "c1", true, 40L))
                .thenReturn(new ScanResponse(rows, "c2", false, 7L));
        PageAccounting accounting = new PageAccounting(4, 2, 1, Map.of(RunnerMetrics.SkipReason.SAMPLING, 1));
        ChunkPayload chunk = new ChunkPayload(PIPELINE_NAME, 1, List.of());
        when(chunkBuilder.build(any(), anyInt(), any()))
                .thenReturn(new ChunkBatch(List.of(chunk), List.of(DlqItemEntity.builder().build()), accounting));

        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            executor.execute(PIPELINE_NAME);

            assertThat(appender.messages().stream().filter(m -> m != null && m.startsWith("scan fire:")))
                    .singleElement().asString()
                    .startsWith("scan fire: pipeline=r1 pages=2 rows=8 chunks=2 marks=0 sampled_out=2 input_map_dlq=2 lag_s=7 "
                            + "cursor_advanced=true ms=");
        } finally {
            appender.detach(Level.INFO);
        }
    }

    @Test
    void haltsDrainWhenBreakerPausesPipelineMidFire() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(spendPauseGate.isOverBudget(pipeline)).thenReturn(false);
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        // Not paused at the top-of-fire check, then auto-paused right after the first page is processed.
        when(pauseRegistry.isPaused(PIPELINE_NAME)).thenReturn(false, true);
        // Every page reports more rows follow, so only a mid-fire pause can stop the drain.
        when(scanClient.scan(anyString(), any(), anyInt()))
                .thenReturn(new ScanResponse(List.of(), "c1", true, 0L));
        when(chunkBuilder.build(any(), anyInt(), any()))
                .thenReturn(new ChunkBatch(List.of(), List.of(), TestPages.allWork(0)));

        executor.execute(PIPELINE_NAME);

        // Without the mid-drain pause check the loop would scan up to maxPagesPerFire (5) pages.
        verify(scanClient, times(1)).scan(eq(PIPELINE_NAME), any(), anyInt());
    }

    @Test
    void pageWithoutNextCursorIsCommittedAsNoProgress() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        // has_more with no next_cursor: the drain must stop, and — the part that used to be wrong — the commit
        // must not be handed a null cursor. Null is not "no progress", it is the source start, so persisting it
        // would re-scan the pipeline's whole history on the next fire.
        when(scanClient.scan(anyString(), any(), anyInt()))
                .thenReturn(new ScanResponse(List.of(), null, true, 0L));
        when(chunkBuilder.build(any(), anyInt(), any()))
                .thenReturn(new ChunkBatch(List.of(), List.of(), TestPages.allWork(0)));

        executor.execute(PIPELINE_NAME);

        verify(scanClient, times(1)).scan(eq(PIPELINE_NAME), any(), anyInt());
        // The committer decides what to store, but it must be told the truth about what came back; the
        // integration tests assert the persisted value.
        verify(committer).commit(eq(PIPELINE_NAME), anyLong(), eq(TestPipelines.CREATED_AT), eq(null), eq(null), any(), any());
    }

    @Test
    void anEmptyPageReportsAndCommitsZeroLagRatherThanUnknown() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        // Caught up: no rows, and lag_seconds omitted because there is no page end version to subtract. Read as
        // "unknown", the pipeline keeps exporting the last backlog's lag forever.
        when(scanClient.scan(anyString(), any(), anyInt()))
                .thenReturn(new ScanResponse(List.of(), "c1", false, null));
        when(chunkBuilder.build(any(), anyInt(), any()))
                .thenReturn(new ChunkBatch(List.of(), List.of(), TestPages.allWork(0)));

        executor.execute(PIPELINE_NAME);

        verify(runnerMetrics).recordLag(PIPELINE_NAME, 0L);
        // The same resolved value reaches the commit, so the persisted column cannot contradict the gauge.
        verify(committer).commit(eq(PIPELINE_NAME), anyLong(), eq(TestPipelines.CREATED_AT), eq(null), eq("c1"), eq(0L), any());
    }

    @Test
    void pageWithRowsButNoLagLeavesTheSignalUntouched() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        when(scanClient.scan(anyString(), any(), anyInt()))
                .thenReturn(new ScanResponse(List.of(Map.of("event_id", "e1")), "c1", false, null));
        when(chunkBuilder.build(any(), anyInt(), any()))
                .thenReturn(new ChunkBatch(List.of(), List.of(), TestPages.allWork(0)));

        executor.execute(PIPELINE_NAME);

        // Rows came back but no lag was established, so nothing may be claimed — not even zero.
        verify(runnerMetrics).recordLag(PIPELINE_NAME, null);
        verify(committer).commit(eq(PIPELINE_NAME), anyLong(), eq(TestPipelines.CREATED_AT), eq(null), eq("c1"), eq(null), any());
    }

    @Test
    void stalledCursorIsCounted() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        when(scanClient.scan(anyString(), any(), anyInt()))
                .thenReturn(new ScanResponse(List.of(), null, true, 0L));
        when(chunkBuilder.build(any(), anyInt(), any()))
                .thenReturn(new ChunkBatch(List.of(), List.of(), TestPages.allWork(0)));

        executor.execute(PIPELINE_NAME);

        // The halt is otherwise silent: nothing throws, and the pipeline simply stops making progress.
        verify(runnerMetrics).incrementCursorStall(RunnerMetrics.CursorLane.SCAN);
    }

    /**
     * What the gate counts is {@link BackpressureGate}'s own test; what matters here is that a backpressured
     * pipeline is not scanned at all, rather than scanned and then discarded.
     */
    @Test
    void backpressuredPipelineIsNotScanned() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(backpressureGate.isBackpressured(PIPELINE_NAME)).thenReturn(true);

        executor.execute(PIPELINE_NAME);

        verify(scanClient, never()).scan(anyString(), any(), anyInt());
    }

    @Test
    void completedFireRecordsWhetherTheLastPageHadMore() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        when(scanClient.scan(anyString(), any(), anyInt())).thenReturn(new ScanResponse(List.of(), "c1", false, 0L));
        when(chunkBuilder.build(any(), anyInt(), any()))
                .thenReturn(new ChunkBatch(List.of(), List.of(), TestPages.allWork(0)));
        runOutcome.recordFailure(PIPELINE_NAME, "earlier failure");

        executor.execute(PIPELINE_NAME);

        PipelineRunOutcome.Outcome outcome = runOutcome.get(PIPELINE_NAME).orElseThrow();
        assertThat(outcome.hasMore()).isFalse();
        assertThat(outcome.caughtUpAt()).isNotNull();
        assertThat(outcome.lastError()).isNull();
    }

    /** The failure is recorded for the runtime view and still reaches db-scheduler unchanged. */
    @Test
    void failedFireRecordsItsErrorAndRethrows() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        IllegalStateException failure = new IllegalStateException("boom");
        when(scanClient.scan(anyString(), any(), anyInt())).thenThrow(failure);

        assertThatThrownBy(() -> executor.execute(PIPELINE_NAME)).isSameAs(failure);

        assertThat(runOutcome.get(PIPELINE_NAME).orElseThrow().lastError()).isEqualTo(ErrorMessages.classify(failure));
    }

    /** A held fire counts in db-scheduler's consecutive failures, so it is recorded like any other failure. */
    @Test
    void heldFireIsRecordedAsFailure() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        PipelineCacheColdException held = new PipelineCacheColdException("scan");
        when(scanClient.scan(anyString(), any(), anyInt())).thenThrow(held);

        assertThatThrownBy(() -> executor.execute(PIPELINE_NAME)).isSameAs(held);

        assertThat(runOutcome.get(PIPELINE_NAME).orElseThrow().lastError()).isEqualTo(ErrorMessages.classify(held));
    }

    /** A gate read that fails fails the fire, so it is recorded too. */
    @Test
    void failingGateReadIsRecorded() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        IllegalStateException failure = new IllegalStateException("postgres down");
        when(spendPauseGate.isOverBudget(pipeline)).thenThrow(failure);

        assertThatThrownBy(() -> executor.execute(PIPELINE_NAME)).isSameAs(failure);

        assertThat(runOutcome.get(PIPELINE_NAME).orElseThrow().lastError()).isEqualTo(ErrorMessages.classify(failure));
    }

    /** A drain stopped by the page budget with rows left has more to read, so it never reads as caught up. */
    @Test
    void drainStoppedByThePageBudgetRecordsMoreToRead() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        when(scanClient.scan(anyString(), any(), anyInt())).thenReturn(
                new ScanResponse(List.of(), "c1", true, 9L), new ScanResponse(List.of(), "c2", true, 9L),
                new ScanResponse(List.of(), "c3", true, 9L), new ScanResponse(List.of(), "c4", true, 9L),
                new ScanResponse(List.of(), "c5", true, 9L), new ScanResponse(List.of(), "c6", true, 9L));
        when(chunkBuilder.build(any(), anyInt(), any()))
                .thenReturn(new ChunkBatch(List.of(), List.of(), TestPages.allWork(0)));

        executor.execute(PIPELINE_NAME);

        verify(scanClient, times(properties.maxPagesPerFire())).scan(eq(PIPELINE_NAME), any(), anyInt());
        assertThat(runOutcome.get(PIPELINE_NAME)).contains(new PipelineRunOutcome.Outcome(true, null, null, null));
    }

    /** A cursor stall halts the drain with rows still claimed, so the fire has more to read and is not caught up. */
    @Test
    void cursorStallRecordsMoreToRead() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        PipelineCursorEntity stored = new PipelineCursorEntity(PIPELINE_NAME);
        stored.setCursor("c1");
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.of(stored));
        // has_more, but the cursor echoes back unchanged: the drain halts after one page.
        when(scanClient.scan(anyString(), any(), anyInt())).thenReturn(new ScanResponse(List.of(), "c1", true, 9L));
        when(chunkBuilder.build(any(), anyInt(), any()))
                .thenReturn(new ChunkBatch(List.of(), List.of(), TestPages.allWork(0)));

        executor.execute(PIPELINE_NAME);

        verify(scanClient, times(1)).scan(eq(PIPELINE_NAME), any(), anyInt());
        verify(runnerMetrics).incrementCursorStall(RunnerMetrics.CursorLane.SCAN);
        assertThat(runOutcome.get(PIPELINE_NAME)).contains(new PipelineRunOutcome.Outcome(true, null, null, null));
    }

    /** A fire whose budget reads no page learns nothing about the input, so it must not read as caught up. */
    @Test
    void zeroPageBudgetRecordsMoreToReadAndKeepsCaughtUpAt() {
        ScanProperties noPages = new ScanProperties(1000, 10_000, 25, 1000, 0);
        PipelineScanExecutor zeroBudget = new PipelineScanExecutor(pipelineCache, scanClient, chunkBuilder,
                committer, groupMarkBuilder, groupDirtyMarkCommitter, cursorRepository, backpressureGate,
                noPages, pauseRegistry, dlqCircuitBreaker, spendPauseGate, runnerMetrics, runnerTracer, runOutcome);
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        runOutcome.recordSuccess(PIPELINE_NAME, false);
        Instant caughtUpAt = runOutcome.get(PIPELINE_NAME).orElseThrow().caughtUpAt();

        zeroBudget.execute(PIPELINE_NAME);

        verify(scanClient, never()).scan(anyString(), any(), anyInt());
        assertThat(runOutcome.get(PIPELINE_NAME)).contains(new PipelineRunOutcome.Outcome(true, caughtUpAt, null, null));
    }

    /** A gated skip is not a run, so it leaves the last fire's outcome as it was. */
    @Test
    void gatedSkipLeavesTheOutcomeUntouched() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(backpressureGate.isBackpressured(PIPELINE_NAME)).thenReturn(true);
        runOutcome.recordFailure(PIPELINE_NAME, "earlier failure");
        PipelineRunOutcome.Outcome before = runOutcome.get(PIPELINE_NAME).orElseThrow();

        executor.execute(PIPELINE_NAME);

        assertThat(runOutcome.get(PIPELINE_NAME)).contains(before);
    }

    /**
     * A commit refused because the pipeline was recreated mid-fire ends the fire quietly: it is neither a failure
     * db-scheduler should count nor a success the runtime view should report for the new pipeline.
     */
    @Test
    void recreatedPipelineEndsTheFireWithoutRecordingAnOutcome() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        when(scanClient.scan(anyString(), any(), anyInt())).thenReturn(new ScanResponse(List.of(), "c1", true, 0L));
        when(chunkBuilder.build(any(), anyInt(), any()))
                .thenReturn(new ChunkBatch(List.of(), List.of(), TestPages.allWork(0)));
        doThrow(recreated()).when(committer)
                .commit(eq(PIPELINE_NAME), anyLong(), eq(TestPipelines.CREATED_AT), any(), any(), any(), any());

        executor.execute(PIPELINE_NAME);

        verify(scanClient, times(1)).scan(eq(PIPELINE_NAME), any(), anyInt());
        assertThat(runOutcome.get(PIPELINE_NAME)).isEmpty();
        verify(dlqCircuitBreaker, never()).record(anyString(), anyInt(), anyInt());
    }

    @Test
    void recreatedGroupPipelineEndsTheFireWithoutRecordingAnOutcome() {
        PipelineDto pipeline = TestPipelines.group(PIPELINE_NAME, 1, "chat_id", "10m");
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        when(scanClient.scan(anyString(), any(), anyInt())).thenReturn(new ScanResponse(List.of(), "c1", true, 0L));
        when(groupMarkBuilder.build(any(), any()))
                .thenReturn(new GroupMarkBatch(List.of(), List.of(), TestPages.allWork(0)));
        doThrow(recreated()).when(groupDirtyMarkCommitter)
                .commit(eq(PIPELINE_NAME), eq(TestPipelines.CREATED_AT), any(), any(), any());

        executor.execute(PIPELINE_NAME);

        verify(groupDirtyMarkCommitter).commit(eq(PIPELINE_NAME), eq(TestPipelines.CREATED_AT), eq("c1"), eq(0L), any());
        verify(committer, never()).commit(any(), anyLong(), any(), any(), any(), any(), any());
        assertThat(runOutcome.get(PIPELINE_NAME)).isEmpty();
        verify(dlqCircuitBreaker, never()).record(anyString(), anyInt(), anyInt());
    }

    private static PipelineRecreatedException recreated() {
        return new PipelineRecreatedException(PIPELINE_NAME, TestPipelines.CREATED_AT, Instant.parse("2026-10-06T00:00:00Z"));
    }
}
