package com.epam.aidial.enrichment.runtime;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.epam.aidial.enrichment.exception.PipelineCacheNotReadyException;
import com.epam.aidial.enrichment.exception.PipelineNotFoundException;
import com.epam.aidial.enrichment.observability.status.PipelineActivity;
import com.epam.aidial.enrichment.observability.status.SchedulerQueueReader;
import com.epam.aidial.enrichment.observability.status.SchedulerQueueReader.RecurringExecution;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry.Pause;
import com.epam.aidial.enrichment.persistence.DlqCounts;
import com.epam.aidial.enrichment.persistence.DlqItemRepository;
import com.epam.aidial.enrichment.persistence.GroupStateWriter;
import com.epam.aidial.enrichment.persistence.GroupStateWriter.GroupCounts;
import com.epam.aidial.enrichment.persistence.PipelineCursorEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.runtime.PipelineRunOutcome.Outcome;
import com.epam.aidial.enrichment.runtime.PipelineStatusResolver.Status;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.scan.InflightCounter;
import com.epam.aidial.enrichment.spend.DailySpend;
import com.epam.aidial.enrichment.spend.SpendService;
import com.epam.aidial.enrichment.sweep.GroupReadinessSweep;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.sync.PipelineRouting;
import com.epam.aidial.enrichment.web.dto.runtime.PipelineRuntimeDto;
import com.epam.aidial.enrichment.web.dto.runtime.RuntimeFailuresDto;
import com.epam.aidial.enrichment.web.dto.runtime.RuntimeGroupsDto;
import com.epam.aidial.enrichment.web.dto.runtime.RuntimeProgressDto;
import com.epam.aidial.enrichment.web.dto.runtime.RuntimeQueueDto;
import com.epam.aidial.enrichment.web.dto.runtime.RuntimeScheduleDto;
import com.epam.aidial.enrichment.web.dto.runtime.RuntimeSpendDto;
import com.epam.aidial.enrichment.web.dto.runtime.RuntimeStatusDto;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.epam.aidial.enrichment.worker.WorkTasks;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.upsert.UpsertRowsTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Assembles a pipeline's runtime view from the state the runner already keeps: the cursor, the scheduler's rows,
 * group state, spend and dead letters in Postgres, and the pause registry, the write activity and the
 * run outcome in memory.
 *
 * <p>Read-only, and on an explicit request only: nothing here runs on a clock. Every read is bounded to one pipeline.
 * The status comes from {@link PipelineStatusResolver}, the same resolver the heartbeat uses.
 */
@Service
@RequiredArgsConstructor
public class PipelineRuntimeService {

    private static final String LIVE_LANE = "live";

    private final Clock clock;
    private final PipelineCache pipelineCache;
    private final PipelineStatusResolver statusResolver;
    private final PipelineRunOutcome runOutcome;
    private final PipelineActivity pipelineActivity;
    private final PipelineCursorRepository cursorRepository;
    private final SchedulerQueueReader queueReader;
    private final InflightCounter inflightCounter;
    private final GroupStateWriter groupStateWriter;
    private final SpendService spendService;
    private final DlqItemRepository dlqItemRepository;
    private final GroupProperties groupProperties;

    /**
     * The pipeline's runtime view.
     *
     * @throws PipelineCacheNotReadyException when the cache has never synced, so the runner cannot yet say whether the
     *                                        pipeline exists
     * @throws PipelineNotFoundException      when a synced cache does not hold the pipeline
     */
    public PipelineRuntimeDto runtime(String pipelineName) {
        // Checked first: on a cold cache every pipeline would otherwise read as missing.
        if (!pipelineCache.isPopulated()) {
            throw new PipelineCacheNotReadyException();
        }
        PipelineDto pipeline = pipelineCache.get(pipelineName);
        if (pipeline == null) {
            throw new PipelineNotFoundException(pipelineName);
        }
        Instant now = clock.instant();
        boolean class1 = PipelineRouting.isClass1(pipeline);
        boolean group = PipelineRouting.isGroup(pipeline);

        PipelineCursorEntity cursor = cursorRepository.findById(pipelineName).orElse(null);
        Long lagSeconds = cursor == null ? null : cursor.getLagSeconds();
        Instant lastWrite = pipelineActivity.lastWrite(pipelineName);
        Optional<Outcome> outcome = runOutcome.get(pipelineName);
        RecurringExecution recurring = queueReader.recurring(PipelineRouting.taskName(pipeline), pipelineName).orElse(null);

        return new PipelineRuntimeDto(
                pipelineName,
                PipelineRouting.lane(pipeline),
                pipeline.generation(),
                status(statusResolver.resolve(pipeline, lastWrite, lagSeconds, now)),
                schedule(cursor, recurring, outcome),
                new RuntimeProgressDto(lagSeconds, outcome.map(Outcome::hasMore).orElse(null),
                        outcome.map(Outcome::caughtUpAt).orElse(null), lastWrite),
                class1 ? null : queue(pipelineName),
                group ? groups(pipeline, now).orElse(null) : null,
                class1 ? null : spend(pipelineName),
                class1 ? null : failures(pipelineName));
    }

    private static RuntimeStatusDto status(Status status) {
        String state = lowercase(status.state());
        Pause pause = status.pause();
        if (pause == null) {
            return new RuntimeStatusDto(state, null, null, null, null);
        }
        return new RuntimeStatusDto(state, lowercase(pause.origin()), pause.reason(), pause.since(),
                pause.resumesAt());
    }

    /**
     * The recurring row is durable and the outcome is not, and the outcome also misses a failure no executor catches
     * (a JVM {@link Error}, or one thrown outside the executor). Whenever the row counts consecutive failures and the
     * outcome names no error, the failure instant comes from the row; the message is not stored there, so it stays
     * unknown. A still-failing pipeline therefore never reads as cleared. A gated skip resets the row's count, so the
     * fallback can only under-report a failure, never invent one.
     */
    private static RuntimeScheduleDto schedule(PipelineCursorEntity cursor, RecurringExecution recurring,
                                               Optional<Outcome> outcome) {
        String lastError = outcome.map(Outcome::lastError).orElse(null);
        Instant lastErrorAt = outcome.map(Outcome::lastErrorAt).orElse(null);
        if (lastErrorAt == null && recurring != null && recurring.consecutiveFailures() > 0) {
            lastErrorAt = recurring.lastFailure();
        }
        return new RuntimeScheduleDto(
                cursor == null ? null : cursor.getLastScanAt(),
                // While a fire runs, execution_time is that fire's due time, not the next one.
                recurring == null || recurring.picked() ? null : recurring.executionTime(),
                recurring == null ? null : recurring.picked(),
                recurring == null ? null : recurring.consecutiveFailures(),
                lastError,
                lastErrorAt);
    }

    /** The same task set and prefix backpressure counts, so computing plus awaiting_write is its depth. */
    private RuntimeQueueDto queue(String pipelineName) {
        Map<String, Long> counts = inflightCounter.countsByTaskAndInstancePrefix(
                WorkTasks.PIPELINE_SCOPED, DeterministicId.pipelinePrefix(pipelineName));
        return new RuntimeQueueDto(
                counts.getOrDefault(EnrichChunkTask.TASK_NAME, 0L) + counts.getOrDefault(GroupChunkTask.TASK_NAME, 0L),
                counts.getOrDefault(UpsertRowsTask.TASK_NAME, 0L));
    }

    /**
     * Empty when the pipeline's readiness windows cannot be parsed: the sweep skips such a pipeline, and the readiness
     * counts would have no predicate to count with. The rest of the view still answers.
     */
    private Optional<RuntimeGroupsDto> groups(PipelineDto pipeline, Instant now) {
        GroupStateWriter.Readiness readiness;
        try {
            readiness = GroupReadinessSweep.readiness(pipeline, groupProperties.defaultIdle(), now);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        GroupCounts counts = groupStateWriter.counts(pipeline.name(), readiness,
                LocalDate.ofInstant(now, ZoneOffset.UTC), PipelineRouting.costCeiling(pipeline));
        return Optional.of(new RuntimeGroupsDto(counts.pending(), counts.ready(), counts.waitingIdle(), counts.ceilingBlocked()));
    }

    private RuntimeSpendDto spend(String pipelineName) {
        DailySpend today = spendService.today(pipelineName);
        return new RuntimeSpendDto(today.calls(), today.tokens(), today.failures());
    }

    /** Counted over the DLQ listing's own filter (this pipeline, lane live), so the totals equal the listing's. */
    private RuntimeFailuresDto failures(String pipelineName) {
        DlqCounts counts = dlqItemRepository.counts(pipelineName, null, LIVE_LANE);
        return new RuntimeFailuresDto(counts.total(), counts.requeueable());
    }

    private static String lowercase(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
