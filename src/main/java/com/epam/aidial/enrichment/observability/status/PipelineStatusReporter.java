package com.epam.aidial.enrichment.observability.status;

import com.epam.aidial.enrichment.adas.AdasCompatibilityState;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.backfill.BackfillTask;
import com.epam.aidial.enrichment.backfill.RunState;
import com.epam.aidial.enrichment.configuration.DbSchedulerProperties;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.observability.ReadinessState;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry.Pause;
import com.epam.aidial.enrichment.persistence.DlqItemRepository;
import com.epam.aidial.enrichment.persistence.PipelineCursorEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.epam.aidial.enrichment.runtime.PipelineStatusResolver;
import com.epam.aidial.enrichment.runtime.PipelineStatusResolver.Status;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.sync.PipelineRouting;
import com.epam.aidial.enrichment.worker.HumanDuration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The status heartbeat: one line per synced pipeline and one runner-wide line, every
 * {@code observability.status-report.interval}.
 *
 * <p>It exists for the failure a log-only operator cannot otherwise see. A pipeline that stops writing emits
 * nothing, so the line that says so has to be emitted on a clock instead of on an event.
 *
 * <p><b>Levels.</b> A pipeline's line is WARN when the pipeline is paused (with the pause's origin, reason and age),
 * or when it has written nothing for more than its stall threshold while its lag is above zero — {@value PipelineStatusResolver#STALL_CADENCES}
 * cadences, or for a group pipeline no less than its effective idle window plus the sweep interval.
 * Otherwise it is INFO. The paused WARN is runner-ops' recurring paused-pipeline report: one line per interval per
 * paused pipeline, with no second reporter on a second clock.
 *
 * <p><b>Restart grace.</b> Write activity is held in memory ({@link PipelineActivity}), so after a restart every
 * pipeline looks as if it had never written. The no-write WARN is therefore suppressed until
 * that threshold has passed since startup, and an unknown last write is logged as {@code unknown}.
 *
 * <p>Each line is one record of {@code |}-separated sections, with instants given relative to the report ("3m ago",
 * "in 4m"). Every value the runner cannot know is logged as {@code unknown}, never estimated.
 *
 * <p>The state and the stall verdict come from {@link PipelineStatusResolver}, which the runtime view reads as well,
 * so the line and the view agree. This class only puts them into words.
 */
@Slf4j
@Component
public class PipelineStatusReporter {

    private static final String UNKNOWN = "unknown";
    private static final String SEPARATOR = " | ";
    private static final String PIPELINE_STATUS = "Pipeline status";
    private static final String RUNNER_STATUS = "Runner status";
    private static final Duration DEFAULT_POLLING_INTERVAL = Duration.ofSeconds(10);

    private final Clock clock;
    private final PipelineCache pipelineCache;
    private final PipelineActivity pipelineActivity;
    private final PipelineCursorRepository cursorRepository;
    private final SchedulerQueueReader queueReader;
    private final PipelineStatusResolver statusResolver;
    private final AdasCompatibilityState compatibilityState;
    private final ReadinessState readinessState;
    private final DlqItemRepository dlqItemRepository;
    private final RunHistoryRepository runHistoryRepository;
    private final Duration pollingInterval;

    public PipelineStatusReporter(Clock clock, PipelineCache pipelineCache, PipelineActivity pipelineActivity,
                                  PipelineCursorRepository cursorRepository, SchedulerQueueReader queueReader,
                                  PipelineStatusResolver statusResolver, AdasCompatibilityState compatibilityState,
                                  ReadinessState readinessState, DlqItemRepository dlqItemRepository,
                                  RunHistoryRepository runHistoryRepository, DbSchedulerProperties dbSchedulerProperties) {
        this.clock = clock;
        this.pipelineCache = pipelineCache;
        this.pipelineActivity = pipelineActivity;
        this.cursorRepository = cursorRepository;
        this.queueReader = queueReader;
        this.statusResolver = statusResolver;
        this.compatibilityState = compatibilityState;
        this.readinessState = readinessState;
        this.dlqItemRepository = dlqItemRepository;
        this.runHistoryRepository = runHistoryRepository;
        this.pollingInterval = dbSchedulerProperties.pollingInterval() != null
                ? dbSchedulerProperties.pollingInterval() : DEFAULT_POLLING_INTERVAL;
    }

    /** Logs every pipeline's line, then the runner line. A failed read costs one WARN, not the next report. */
    @Scheduled(fixedDelayString = "${observability.status-report.interval}",
            initialDelayString = "${observability.status-report.interval}")
    public void report() {
        try {
            Instant now = clock.instant();
            List<PipelineDto> pipelines = pipelineCache.getAll().stream().sorted(Comparator.comparing(PipelineDto::name)).toList();
            Set<String> names = pipelines.stream().map(PipelineDto::name).collect(Collectors.toSet());
            pipelineActivity.retain(names);
            Map<String, PipelineCursorEntity> cursors = cursorRepository.findAllById(names).stream()
                    .collect(Collectors.toMap(PipelineCursorEntity::getPipelineName, Function.identity()));
            Map<String, SchedulerQueueReader.NextFire> fires = queueReader.nextFires();
            for (PipelineDto pipeline : pipelines) {
                reportPipeline(pipeline, cursors.get(pipeline.name()), fires.get(pipeline.name()), now);
            }
            reportRunner(now);
        } catch (RuntimeException e) {
            log.warn("Status report failed: {}", ErrorMessages.classify(e));
        }
    }

    private void reportPipeline(PipelineDto pipeline, PipelineCursorEntity cursor, SchedulerQueueReader.NextFire fire, Instant now) {
        String name = pipeline.name();
        PipelineActivity.Snapshot activity = pipelineActivity.drain(name);
        Long lagSeconds = cursor != null ? cursor.getLagSeconds() : null;
        Status status = statusResolver.resolve(pipeline, activity.lastWrite(), lagSeconds, now);
        List<String> sections = new ArrayList<>(List.of(
                name + " (" + PipelineRouting.lane(pipeline) + ", gen " + pipeline.generation() + ")",
                status.pause() != null ? paused(status.pause(), now) : words(status.state()),
                "last write " + ago(activity.lastWrite(), now) + ", +" + activity.rowsSince() + " rows, +" + activity.dlqSince() + " DLQ",
                "lag " + (lagSeconds == null ? UNKNOWN : duration(Duration.ofSeconds(lagSeconds))),
                "last scan " + ago(cursor != null ? cursor.getLastScanAt() : null, now),
                "next fire " + nextFire(fire, now)));

        if (status.pause() != null) {
            log.warn(line(PIPELINE_STATUS, sections));
            return;
        }
        if (status.stalled()) {
            sections.add("STALLED: no write for over " + duration(status.stallAfter()) + " while lagging");
            log.warn(line(PIPELINE_STATUS, sections));
            return;
        }
        log.info(line(PIPELINE_STATUS, sections));
    }

    private void reportRunner(Instant now) {
        Map<String, Long> depth = new TreeMap<>();
        long overdue = 0;
        for (Map.Entry<String, SchedulerQueueReader.TaskDepth> entry : queueReader.depthByTask(now.minus(pollingInterval)).entrySet()) {
            depth.put(entry.getKey(), entry.getValue().depth());
            overdue += entry.getValue().overdue();
        }
        String queue = depth.entrySet().stream().filter(e -> e.getValue() > 0)
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "));
        String executing = queueReader.longestExecuting()
                .map(e -> "longest executing " + e.taskName() + " for " + duration(Duration.between(e.executionTime(), now)))
                .orElse("nothing executing");
        Instant lastSync = readinessState.getLastPipelineSyncAt();
        List<SchedulerQueueReader.OrphanedRun> orphaned = queueReader.orphanedBackfills();
        String backfills = Stream.of(
                        count(runHistoryRepository.countByState(RunState.RUNNING.dbValue()), "running"),
                        count(runHistoryRepository.countByState(RunState.PAUSED.dbValue()), "paused"),
                        count(orphaned.size(), "orphaned"))
                .filter(Objects::nonNull).collect(Collectors.joining(", "));
        log.info(line(RUNNER_STATUS, List.of(
                (lastSync == null ? "ADAS never synced" : "ADAS synced " + ago(lastSync, now))
                        + (compatibilityState.isCompatible() ? ", compatible" : ", INCOMPATIBLE"),
                "queue: " + (queue.isEmpty() ? "empty" : queue),
                overdue + " overdue, " + executing,
                "DLQ " + dlqItemRepository.count(),
                "backfills: " + (backfills.isEmpty() ? "none" : backfills))));
        // A condition that persists is reported every interval, like a paused pipeline, not only when it arises.
        orphaned.forEach(run -> log.warn("Backfill {} of pipeline {} is running but has no {} execution, so nothing will advance "
                + "it; pause and resume the run to reschedule it", run.runId(), run.pipelineName(), BackfillTask.TASK_NAME));
    }

    /** Paused wins over everything: it is the one state an operator must act on or knowingly leave. */
    private static String paused(Pause pause, Instant now) {
        return "PAUSED by " + pause.origin().name().toLowerCase(Locale.ROOT) + " " + ago(pause.since(), now) + ": " + pause.reason();
    }

    /** The unpaused states in the heartbeat's words. */
    private static String words(PipelineStatusResolver.State state) {
        return switch (state) {
            case ACTIVE -> "active";
            case HELD -> "held";
            case OVER_BUDGET -> "over budget";
            case BACKPRESSURED -> "backpressured";
            case PAUSED -> throw new IllegalStateException("a paused status carries its pause");
        };
    }

    private static String line(String title, List<String> sections) {
        return title + SEPARATOR + String.join(SEPARATOR, sections);
    }

    /** A picked execution is running now; otherwise the fire is ahead, or behind and possibly overdue. */
    private String nextFire(SchedulerQueueReader.NextFire fire, Instant now) {
        if (fire == null) {
            return UNKNOWN;
        }
        if (fire.picked()) {
            return "running now";
        }
        if (fire.executionTime().isAfter(now)) {
            return "in " + duration(Duration.between(now, fire.executionTime()));
        }
        boolean overdue = fire.executionTime().isBefore(now.minus(pollingInterval));
        return "due " + ago(fire.executionTime(), now) + (overdue ? ", overdue" : "");
    }

    /** {@code 2 running}, or {@code null} for a zero count, which the line leaves out. */
    private static String count(long count, String what) {
        return count > 0 ? count + " " + what : null;
    }

    private static String ago(Instant instant, Instant now) {
        return instant == null ? UNKNOWN : duration(Duration.between(instant, now)) + " ago";
    }

    /** {@link HumanDuration}, except that nothing reads as {@code 0s} rather than {@code 0ms}. */
    private static String duration(Duration duration) {
        return duration.isZero() ? "0s" : HumanDuration.format(duration);
    }
}
