package com.epam.aidial.enrichment.class1;

import com.epam.aidial.enrichment.adas.AdasMaterializeClient;
import com.epam.aidial.enrichment.adas.dto.MaterializeResponse;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.configuration.AdasProperties;
import com.epam.aidial.enrichment.configuration.ScanProperties;
import com.epam.aidial.enrichment.exception.AdasTransportException;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.RunnerMetrics.CursorLane;
import com.epam.aidial.enrichment.observability.RunnerTracer;
import com.epam.aidial.enrichment.observability.status.PipelineActivity;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.PipelineCursorEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.persistence.PipelineCursorWriter;
import com.epam.aidial.enrichment.runtime.PipelineRunOutcome;
import com.epam.aidial.enrichment.support.CapturingAppender;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Class1MaterializeExecutorTest {

    private static final String PIPELINE_NAME = "r-sql";
    private static final int READ_TIMEOUT_MS = 40;

    private final PipelineCache pipelineCache = mock(PipelineCache.class);
    private final AdasMaterializeClient materializeClient = mock(AdasMaterializeClient.class);
    private final PipelineCursorRepository cursorRepository = mock(PipelineCursorRepository.class);
    private final PipelineCursorWriter cursorWriter = new PipelineCursorWriter(cursorRepository);
    private final PipelinePauseRegistry pauseRegistry = mock(PipelinePauseRegistry.class);
    private final RunnerMetrics runnerMetrics = mock(RunnerMetrics.class);
    private final Class1Properties class1Properties =
            new Class1Properties(10_000, 100_000);
    private final ScanProperties scanProperties =
            new ScanProperties(1000, 10_000, 25, 1000, 3);
    private final RunnerTracer runnerTracer = new RunnerTracer(OpenTelemetry.noop());
    private final AdasProperties adasProperties = new AdasProperties("http://adas", "", 1000, READ_TIMEOUT_MS);

    private final PipelineRunOutcome runOutcome = new PipelineRunOutcome(Clock.systemUTC());

    private final Class1MaterializeExecutor executor = new Class1MaterializeExecutor(pipelineCache,
            materializeClient, cursorRepository, cursorWriter, class1Properties, scanProperties, pauseRegistry,
            runnerMetrics, runnerTracer, adasProperties, mock(PipelineActivity.class), runOutcome);

    /** The writer locks the row its insert guaranteed; against the mock, that is whatever findById is stubbed with. */
    @BeforeEach
    void lockReadsTheStubbedRow() {
        when(cursorRepository.findForUpdate(PIPELINE_NAME)).thenAnswer(invocation -> Optional.of(
                cursorRepository.findById(PIPELINE_NAME).orElseGet(() -> new PipelineCursorEntity(PIPELINE_NAME))));
    }

    @Test
    void drainsWindowsUntilTheBacklogIsExhausted() {
        cachePipeline();
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        when(materializeClient.materialize(eq(PIPELINE_NAME), any(), anyInt())).thenReturn(
                new MaterializeResponse(500L, "c1", true, 10L),
                new MaterializeResponse(120L, "c2", false, 2L));

        executor.execute(PIPELINE_NAME);

        verify(materializeClient, times(2)).materialize(eq(PIPELINE_NAME), any(), anyInt());
        // Per pipeline: a summed lane total cannot say which pipeline stopped writing.
        verify(runnerMetrics).addClass1RowsWritten(PIPELINE_NAME, 500L);
        verify(runnerMetrics).addClass1RowsWritten(PIPELINE_NAME, 120L);
    }

    @Test
    void stopsAtThePerFireBudgetEvenWhenBacklogRemains() {
        cachePipeline();
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        // Every window advances the cursor and reports more, so only the budget can stop the drain.
        when(materializeClient.materialize(eq(PIPELINE_NAME), any(), anyInt())).thenReturn(
                new MaterializeResponse(1L, "c1", true, 1L),
                new MaterializeResponse(1L, "c2", true, 1L),
                new MaterializeResponse(1L, "c3", true, 1L),
                new MaterializeResponse(1L, "c4", true, 1L));

        executor.execute(PIPELINE_NAME);

        verify(materializeClient, times(3)).materialize(eq(PIPELINE_NAME), any(), anyInt());
    }

    @Test
    void haltsWhenTheCursorDoesNotAdvanceDespiteMoreBacklog() {
        cachePipeline();
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.of(cursorAtC1()));
        when(materializeClient.materialize(eq(PIPELINE_NAME), any(), anyInt()))
                .thenReturn(new MaterializeResponse(0L, "c1", true, 1L));

        executor.execute(PIPELINE_NAME);

        verify(materializeClient, times(1)).materialize(eq(PIPELINE_NAME), any(), anyInt());
        // Nothing throws on a stall, so without this counter the halt leaves only a log line — and this lane has
        // no queue depth or DLQ size in which a pipeline that stopped progressing would show up.
        verify(runnerMetrics).incrementCursorStall(CursorLane.CLASS1);
        verify(runnerMetrics, never()).incrementClass1TickFailures();
    }

    @Test
    void advancesTheStoredCursorOnSuccess() {
        cachePipeline();
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.of(cursorAtC1()));
        when(materializeClient.materialize(eq(PIPELINE_NAME), any(), anyInt()))
                .thenReturn(new MaterializeResponse(7L, "c2", false, 3L));

        executor.execute(PIPELINE_NAME);

        assertThat(savedCursor().getCursor()).isEqualTo("c2");
        assertThat(savedCursor().getLagSeconds()).isEqualTo(3L);
    }

    @Test
    void anEmptyWindowLeavesTheStoredCursorIntact() {
        cachePipeline();
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.of(cursorAtC1()));
        // An empty window omits next_cursor entirely; writing that null back would restart the pipeline.
        when(materializeClient.materialize(eq(PIPELINE_NAME), any(), anyInt()))
                .thenReturn(new MaterializeResponse(0L, null, false, null));

        executor.execute(PIPELINE_NAME);

        assertThat(savedCursor().getCursor()).isEqualTo("c1");
    }

    @Test
    void anEmptyWindowReportsZeroLagRatherThanLeavingTheLastOneLatched() {
        cachePipeline();
        PipelineCursorEntity stored = cursorAtC1();
        stored.setLagSeconds(604_800L);      // a week behind, from the backlog this pipeline has since drained
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.of(stored));
        // An empty window omits lag_seconds because there is no window end to subtract — which is zero lag, not
        // an absence of one. Read as an absence, the pipeline reports a week of lag until the process restarts.
        when(materializeClient.materialize(eq(PIPELINE_NAME), any(), anyInt()))
                .thenReturn(new MaterializeResponse(0L, null, false, null));

        executor.execute(PIPELINE_NAME);

        verify(runnerMetrics).recordLag(PIPELINE_NAME, 0L);
        assertThat(savedCursor().getLagSeconds())
                .as("the persisted column and the exported gauge must agree on the same window")
                .isZero();
    }

    @Test
    void windowThatEstablishesNoLagKeepsTheStoredOne() {
        cachePipeline();
        PipelineCursorEntity stored = cursorAtC1();
        stored.setLagSeconds(30L);
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.of(stored));
        // Rows written but no lag_seconds: nothing was established, so nothing may be claimed — not even zero.
        when(materializeClient.materialize(eq(PIPELINE_NAME), any(), anyInt()))
                .thenReturn(new MaterializeResponse(5L, "c2", false, null));

        executor.execute(PIPELINE_NAME);

        // Null is the explicit "unknown", which recordLag leaves the gauge on — not a zero anyone invented.
        verify(runnerMetrics).recordLag(PIPELINE_NAME, null);
        assertThat(savedCursor().getLagSeconds()).isEqualTo(30L);
    }

    @Test
    void pausedPipelineDoesNotMaterialize() {
        cachePipeline();
        when(pauseRegistry.isPaused(PIPELINE_NAME)).thenReturn(true);

        executor.execute(PIPELINE_NAME);

        verify(materializeClient, never()).materialize(any(), any(), anyInt());
        verify(cursorRepository, never()).save(any());
    }

    @Test
    void pipelineAbsentFromCacheDoesNotMaterialize() {
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(null);

        executor.execute(PIPELINE_NAME);

        verify(materializeClient, never()).materialize(any(), any(), anyInt());
    }

    @Test
    void failedWindowCountsTickFailureAndLeavesCursorUnmoved() {
        cachePipeline();
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.of(cursorAtC1()));
        when(materializeClient.materialize(eq(PIPELINE_NAME), any(), anyInt()))
                .thenThrow(new IllegalStateException("boom"));

        // The tick propagates so db-scheduler records the failure and re-fires on cadence.
        assertThatThrownBy(() -> executor.execute(PIPELINE_NAME))
                .isInstanceOf(IllegalStateException.class);

        verify(runnerMetrics).incrementClass1TickFailures();
        verify(cursorRepository, never()).save(any());
    }

    @Test
    void failedWindowIsRecordedForTheRuntimeView() {
        cachePipeline();
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.of(cursorAtC1()));
        when(materializeClient.materialize(eq(PIPELINE_NAME), any(), anyInt()))
                .thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> executor.execute(PIPELINE_NAME))
                .isInstanceOf(IllegalStateException.class);

        // The classified message, the same text the failure line logs, never the raw exception message.
        assertThat(runOutcome.get(PIPELINE_NAME).orElseThrow().lastError()).isEqualTo("IllegalStateException");
    }

    @Test
    void fireRecordsTheLastWindowsBacklogAndClearsTheError() {
        cachePipeline();
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        when(materializeClient.materialize(eq(PIPELINE_NAME), any(), anyInt())).thenReturn(
                new MaterializeResponse(1L, "c1", true, 1L),
                new MaterializeResponse(1L, "c2", true, 1L),
                new MaterializeResponse(1L, "c3", true, 1L));
        runOutcome.recordFailure(PIPELINE_NAME, "earlier failure");

        executor.execute(PIPELINE_NAME);

        // Stopped by the per-fire budget with backlog left: more to read, never caught up.
        assertThat(runOutcome.get(PIPELINE_NAME)).contains(new PipelineRunOutcome.Outcome(true, null, null, null));
    }

    /** A cursor stall halts the drain with backlog still claimed, so the fire has more to read and is not caught up. */
    @Test
    void cursorStallRecordsMoreToRead() {
        cachePipeline();
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.of(cursorAtC1()));
        when(materializeClient.materialize(eq(PIPELINE_NAME), any(), anyInt()))
                .thenReturn(new MaterializeResponse(0L, "c1", true, 1L));

        executor.execute(PIPELINE_NAME);

        assertThat(runOutcome.get(PIPELINE_NAME)).contains(new PipelineRunOutcome.Outcome(true, null, null, null));
    }

    /** A fire whose budget reads no window learns nothing about the backlog, so it must not read as caught up. */
    @Test
    void zeroWindowBudgetRecordsMoreToReadAndKeepsCaughtUpAt() {
        Class1MaterializeExecutor zeroBudget = new Class1MaterializeExecutor(pipelineCache, materializeClient,
                cursorRepository, cursorWriter, class1Properties, new ScanProperties(1000, 10_000, 25, 1000, 0),
                pauseRegistry, runnerMetrics, runnerTracer, adasProperties, mock(PipelineActivity.class), runOutcome);
        cachePipeline();
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        runOutcome.recordSuccess(PIPELINE_NAME, false);
        Instant caughtUpAt = runOutcome.get(PIPELINE_NAME).orElseThrow().caughtUpAt();

        zeroBudget.execute(PIPELINE_NAME);

        verify(materializeClient, never()).materialize(any(), any(), anyInt());
        assertThat(runOutcome.get(PIPELINE_NAME)).contains(new PipelineRunOutcome.Outcome(true, caughtUpAt, null, null));
    }

    @Test
    void pausedFireRecordsNothing() {
        cachePipeline();
        when(pauseRegistry.isPaused(PIPELINE_NAME)).thenReturn(true);

        executor.execute(PIPELINE_NAME);

        assertThat(runOutcome.get(PIPELINE_NAME)).isEmpty();
    }

    /**
     * The drain position is what a bare timeout line lacks: a timeout on window 2 must say it was
     * window 2, that window 1 committed, and keep window 1's cursor.
     */
    @Test
    void transportFailureOnLaterWindowNamesItsPositionAndKeepsTheEarlierCursor() {
        cachePipeline();
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.of(cursorAtC1()));
        AdasTransportException timeout = AdasTransportException.of(HttpMethod.POST, "/v1/pipelines/" + PIPELINE_NAME + "/materialize",
                READ_TIMEOUT_MS, READ_TIMEOUT_MS, new SocketTimeoutException("Read timed out"));
        when(materializeClient.materialize(eq(PIPELINE_NAME), any(), anyInt()))
                .thenReturn(new MaterializeResponse(5L, "c2", true, 1L))
                .thenThrow(timeout);
        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            assertThatThrownBy(() -> executor.execute(PIPELINE_NAME)).isSameAs(timeout);

            assertThat(appender.messages()).anySatisfy(m -> assertThat(m)
                    .contains("at window 2 of this fire")
                    .contains("1 earlier window(s) committed")
                    .contains("adas.read-timeout-ms"));
        } finally {
            appender.detach(Level.INFO);
        }
        verify(runnerMetrics, times(1)).incrementClass1TickFailures();
        assertThat(savedCursor().getCursor()).isEqualTo("c2");
    }

    @Test
    void windowOverHalfTheReadTimeoutWarnsWithItsDuration() {
        cachePipeline();
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.empty());
        when(materializeClient.materialize(eq(PIPELINE_NAME), any(), anyInt())).thenAnswer(invocation -> {
            Thread.sleep(READ_TIMEOUT_MS);
            return new MaterializeResponse(3L, "c1", false, 0L);
        });
        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            executor.execute(PIPELINE_NAME);

            assertThat(appender.messages()).anySatisfy(m -> assertThat(m)
                    .contains("Materialized window for pipeline " + PIPELINE_NAME)
                    .contains("(limit ")
                    .contains("over half of adas.read-timeout-ms=" + READ_TIMEOUT_MS)
                    .contains("advanced.rows_per_scan"));
        } finally {
            appender.detach(Level.INFO);
        }
    }

    /**
     * The pipeline was recreated while the fire ran: the cursor row now records the new pipeline's created_at. The
     * window ADAS wrote is not recorded against the new pipeline, and the fire ends quietly rather than as a failure.
     */
    @Test
    void cursorOwnedByRecreatedPipelineEndsTheFireWithoutRecordingIt() {
        cachePipeline();
        PipelineCursorEntity stored = cursorAtC1();
        ReflectionTestUtils.setField(stored, "pipelineCreatedAt", TestPipelines.CREATED_AT.plusSeconds(86_400));
        when(cursorRepository.findById(PIPELINE_NAME)).thenReturn(Optional.of(stored));
        when(materializeClient.materialize(eq(PIPELINE_NAME), any(), anyInt()))
                .thenReturn(new MaterializeResponse(7L, "c2", true, 3L));

        executor.execute(PIPELINE_NAME);

        verify(materializeClient, times(1)).materialize(eq(PIPELINE_NAME), any(), anyInt());
        verify(cursorRepository, never()).save(any());
        verify(runnerMetrics, never()).incrementClass1TickFailures();
        verify(runnerMetrics, never()).addClass1RowsWritten(any(), anyLong());
        assertThat(runOutcome.get(PIPELINE_NAME)).isEmpty();
    }

    private void cachePipeline() {
        PipelineDto pipeline = TestPipelines.class1(PIPELINE_NAME, 1, TestPipelines.EVERY_5_MINUTES);
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
    }

    /** The pipeline's stored cursor, positioned at {@code c1}. */
    private static PipelineCursorEntity cursorAtC1() {
        PipelineCursorEntity entity = new PipelineCursorEntity(PIPELINE_NAME);
        entity.setCursor("c1");
        return entity;
    }

    private PipelineCursorEntity savedCursor() {
        ArgumentCaptor<PipelineCursorEntity> captor = ArgumentCaptor.forClass(PipelineCursorEntity.class);
        verify(cursorRepository).save(captor.capture());
        return captor.getValue();
    }
}
