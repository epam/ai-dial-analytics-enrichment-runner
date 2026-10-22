package com.epam.aidial.enrichment.observability.status;

import com.epam.aidial.enrichment.adas.AdasCompatibilityState;
import com.epam.aidial.enrichment.configuration.DbSchedulerProperties;
import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.epam.aidial.enrichment.observability.ReadinessState;
import com.epam.aidial.enrichment.pause.PauseOrigin;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.DlqItemRepository;
import com.epam.aidial.enrichment.persistence.PipelineCursorEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.epam.aidial.enrichment.runtime.PipelineStatusResolver;
import com.epam.aidial.enrichment.scan.BackpressureGate;
import com.epam.aidial.enrichment.scan.ScheduleFactory;
import com.epam.aidial.enrichment.spend.SpendPauseGate;
import com.epam.aidial.enrichment.support.EventCapture;
import com.epam.aidial.enrichment.support.SettableClock;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PipelineStatusReporterTest {

    private static final Instant START = Instant.parse("2026-09-29T12:00:00Z");
    private static final String PIPELINE = "labels";

    private final SettableClock clock = new SettableClock(START);
    private final PipelineCache pipelineCache = new PipelineCache();
    private final PipelineActivity activity = new PipelineActivity(clock);
    private final PipelineCursorRepository cursorRepository = mock(PipelineCursorRepository.class);
    private final SchedulerQueueReader queueReader = mock(SchedulerQueueReader.class);
    private final PipelinePauseRegistry pauseRegistry = new PipelinePauseRegistry(clock);
    private final ReadinessState readinessState = new ReadinessState(clock);
    private final DlqItemRepository dlqItemRepository = mock(DlqItemRepository.class);
    private final RunHistoryRepository runHistoryRepository = mock(RunHistoryRepository.class);

    private final GroupProperties groupProperties =
            new GroupProperties(500, 0, Duration.ofDays(7), Duration.ofMinutes(10), 200, 60_000, Duration.ofMinutes(1));

    private final ScheduleFactory scheduleFactory = new ScheduleFactory(groupProperties);

    private final AdasCompatibilityState compatibilityState = new AdasCompatibilityState();

    private final PipelineStatusResolver statusResolver = new PipelineStatusResolver(clock, pauseRegistry, compatibilityState,
            mock(SpendPauseGate.class), mock(BackpressureGate.class), scheduleFactory, groupProperties, Duration.ofSeconds(30));

    private final PipelineStatusReporter reporter = new PipelineStatusReporter(clock, pipelineCache, activity, cursorRepository,
            queueReader, statusResolver, compatibilityState, readinessState, dlqItemRepository, runHistoryRepository,
            new DbSchedulerProperties(Duration.ofSeconds(45), Duration.ofSeconds(10), 3, true));

    private EventCapture capture;

    @BeforeEach
    void setUp() {
        capture = EventCapture.attach();
        pipelineCache.replaceAll(Map.of(PIPELINE, TestPipelines.schedule(PIPELINE, 4, TestPipelines.EVERY_MINUTE)));
        cursorTwoMinutesBehind();
        when(queueReader.nextFires()).thenReturn(Map.of(PIPELINE, new SchedulerQueueReader.NextFire(START.plusSeconds(600), false)));
        when(queueReader.depthByTask(any())).thenReturn(Map.of(EnrichChunkTask.TASK_NAME, new SchedulerQueueReader.TaskDepth(5, 2)));
        when(queueReader.longestExecuting()).thenReturn(Optional.empty());
        when(dlqItemRepository.count()).thenReturn(7L);
        when(runHistoryRepository.countByState("running")).thenReturn(1L);
        when(runHistoryRepository.countByState("paused")).thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        capture.detach();
    }

    @Test
    void stalledPipelineWarnsWithItsLastWriteAndLag() {
        clock.advance(Duration.ofSeconds(10));
        activity.recordWrite(PIPELINE, 3);
        clock.advance(Duration.ofMinutes(4));

        reporter.report();

        List<LogEvent> lines = pipelineLines();
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(message(lines.getFirst())).contains("| active |", "last write 4m ago", "lag 2m", "STALLED: no write for over 3m while lagging");
    }

    @Test
    void healthyPipelineReportsAtInfoWithRowsSinceThePreviousLine() {
        clock.advance(Duration.ofMinutes(4));
        activity.recordWrite(PIPELINE, 4);
        activity.recordWrite(PIPELINE, 3);
        activity.recordDeadLetters(PIPELINE, 2);

        reporter.report();
        clock.advance(Duration.ofSeconds(30));
        reporter.report();

        List<LogEvent> lines = pipelineLines();
        assertThat(lines).hasSize(2).allSatisfy(line -> assertThat(line.getLevel()).isEqualTo(Level.INFO));
        assertThat(message(lines.get(0))).isEqualTo(
                "Pipeline status | labels (row, gen 4) | active | last write 0s ago, +7 rows, +2 DLQ | lag 2m | last scan 4m ago | next fire in 6m");
        assertThat(message(lines.get(1))).contains("last write 30.0s ago, +0 rows, +0 DLQ", "next fire in 5m 30s");
    }

    @Test
    void freshStartDoesNotCryStall() {
        clock.advance(Duration.ofMinutes(2));

        reporter.report();

        List<LogEvent> lines = pipelineLines();
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().getLevel()).isEqualTo(Level.INFO);
        assertThat(message(lines.getFirst())).contains("last write unknown", "lag 2m").doesNotContain("STALLED");
    }

    @Test
    void pausedPipelineGetsExactlyOneWarnPerIntervalWithOriginReasonAndAge() {
        pauseRegistry.pause(PIPELINE, PauseOrigin.OPERATOR, "maintenance");
        clock.advance(Duration.ofMinutes(10));

        reporter.report();
        assertThat(warnings()).hasSize(1);
        assertThat(message(warnings().getFirst())).contains("labels (row, gen 4) | PAUSED by operator 10m ago: maintenance |")
                .doesNotContain("STALLED");

        clock.advance(Duration.ofMinutes(5));
        reporter.report();
        assertThat(warnings()).hasSize(2);
        assertThat(message(warnings().get(1))).contains("PAUSED by operator 15m ago");
    }

    @Test
    void runnerLineCarriesSyncAgeQueueAndBackfills() {
        readinessState.markPipelineSyncSucceeded();
        clock.advance(Duration.ofSeconds(40));

        reporter.report();

        List<String> runner = capture.events().stream().map(PipelineStatusReporterTest::message).filter(m -> m.startsWith("Runner status |")).toList();
        assertThat(runner).containsExactly("Runner status | ADAS synced 40.0s ago, compatible | queue: enrich-chunk 5 | 2 overdue, nothing executing "
                + "| DLQ 7 | backfills: 1 running");
    }

    @Test
    void orphanedBackfillIsWarnedEveryReport() {
        when(queueReader.orphanedBackfills()).thenReturn(List.of(new SchedulerQueueReader.OrphanedRun("run-1", PIPELINE)));

        reporter.report();
        reporter.report();

        assertThat(capture.events().stream().map(PipelineStatusReporterTest::message))
                .filteredOn(m -> m.contains("has no backfill-run execution"))
                .hasSize(2)
                .allSatisfy(m -> assertThat(m).contains("run-1", "pipeline " + PIPELINE));
        assertThat(capture.events().stream().map(PipelineStatusReporterTest::message))
                .anyMatch(m -> m.startsWith("Runner status |") && m.contains("backfills: 1 running, 1 orphaned"));
    }

    @Test
    void unknownSyncAndCursorAreDashes() {
        when(cursorRepository.findAllById(any())).thenReturn(List.of());

        reporter.report();

        assertThat(message(pipelineLines().getFirst())).contains("lag unknown | last scan unknown");
        assertThat(capture.events().stream().map(PipelineStatusReporterTest::message)).anyMatch(m -> m.startsWith("Runner status | ADAS never synced, compatible |"));
    }

    private void cursorTwoMinutesBehind() {
        PipelineCursorEntity cursor = new PipelineCursorEntity(PIPELINE);
        cursor.setLagSeconds(120L);
        cursor.setLastScanAt(START);
        when(cursorRepository.findAllById(any())).thenReturn(List.of(cursor));
    }

    private List<LogEvent> pipelineLines() {
        return capture.events().stream().filter(e -> message(e).startsWith("Pipeline status |")).toList();
    }

    private List<LogEvent> warnings() {
        return pipelineLines().stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    private static String message(LogEvent event) {
        return event.getMessage().getFormattedMessage();
    }
}
