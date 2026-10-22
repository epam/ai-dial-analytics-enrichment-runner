package com.epam.aidial.enrichment.backfill;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.exception.BackfillConflictException;
import com.epam.aidial.enrichment.exception.BackfillRunNotFoundException;
import com.epam.aidial.enrichment.exception.PipelineCacheNotReadyException;
import com.epam.aidial.enrichment.exception.PipelineNotFoundException;
import com.epam.aidial.enrichment.observability.OperatorActionLog;
import com.epam.aidial.enrichment.pause.PauseOrigin;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.RunHistoryEntity;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.epam.aidial.enrichment.spend.SpendPauseGate;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.github.kagkarlsson.scheduler.Scheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Owns the operator-facing lifecycle of backfill runs in {@code run_history} and their scheduling. A backfill
 * drives the same pipeline-scoped scan as the live path but from the source start (cursor omitted), tracking its
 * own cursor here. A start or resume and the (tx-aware) scheduling of its first batch commit together.
 *
 * <p><strong>Every verb is guarded by the run's state</strong> ({@link RunState#canPause()} and its siblings) and
 * written as a conditional single statement, so a verb applied to a state it does not admit — or to a run that
 * changed since it was read — is a {@code 409} that changes nothing.
 *
 * <p><strong>Start and resume refuse what the first batch would pause on</strong>, in order: a cold cache
 * ({@code 503}), an absent pipeline ({@code 404}), a paused pipeline, an exhausted spend budget, and another
 * running run of the pipeline (each {@code 409}). A verb that returns success and is undone a second later is a
 * surface that appears to work and does not. A cursor stall cannot be pre-checked, and resuming one is allowed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackfillService {

    /** Ceiling on the run listing. Lower than the DLQ's: a run is a rarer object than a dead letter. */
    public static final int MAX_LIMIT = 500;

    /** The partial unique index (V10) behind "at most one running run per pipeline". */
    static final String ONE_RUNNING_PER_PIPELINE_UK = "run_history_one_running_per_pipeline_uk";

    private final RunHistoryRepository runHistoryRepository;
    private final BackfillTask backfillTask;
    private final Scheduler scheduler;
    private final PipelineCache pipelineCache;
    private final PipelinePauseRegistry pauseRegistry;
    private final SpendPauseGate spendPauseGate;

    /** Starts a full-replay backfill for a pipeline (from the source start) and schedules its first batch. */
    @Transactional
    public RunHistoryEntity start(String pipelineName) {
        return start(pipelineName, BackfillWindow.UNBOUNDED);
    }

    /**
     * Starts a backfill bounded to {@code window} — the mechanism for re-applying a declaration change to a
     * range of history rather than replaying everything ever ingested. The window is persisted on the run, so a
     * later inspection can tell what it covered, and so is the pipeline generation it drains under.
     */
    @Transactional
    public RunHistoryEntity start(String pipelineName, BackfillWindow window) {
        PipelineDto pipeline = requireRunnable(pipelineName);
        RunHistoryEntity run = new RunHistoryEntity();
        run.setId(UUID.randomUUID().toString());
        run.setPipelineName(pipelineName);
        run.setKind(RunKind.BACKFILL.dbValue());
        run.setState(RunState.RUNNING.dbValue());
        run.setCursor(null);
        run.setRowsDone(0);
        run.setWindowFrom(window.from());
        run.setWindowTo(window.to());
        run.setPipelineGeneration(pipeline.generation());
        run.setStartedAt(Instant.now());
        try {
            runHistoryRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException e) {
            throw concurrentRunConflict(pipelineName, e);
        }
        scheduleRun(run.getId(), null);
        logAction("backfill_start", pipelineName, run.getId());
        return run;
    }

    public RunHistoryEntity get(String runId) {
        return runHistoryRepository.findById(runId).orElseThrow(() -> new BackfillRunNotFoundException(runId));
    }

    /**
     * Lists runs newest first, narrowed by any combination of pipeline, state and pause reason, with the limit
     * clamped to {@code [1, MAX_LIMIT]}. No paging and no total: runs are few, unlike dead letters (see {@code DlqListingService}).
     */
    public List<RunHistoryEntity> list(String pipelineName, RunState state, BackfillPauseReason pausedReason, int limit) {
        return runHistoryRepository.list(
                StringUtils.trimToNull(pipelineName),
                state == null ? null : state.dbValue(),
                pausedReason == null ? null : pausedReason.dbValue(),
                PageRequest.of(0, Math.clamp(limit, 1, MAX_LIMIT)));
    }

    @Transactional
    public RunHistoryEntity pause(String runId) {
        requireAdmits(get(runId), RunState::canPause, "only a running run can be paused");
        if (runHistoryRepository.pauseByOperator(runId, Instant.now()) == 0) {
            throw refused(runId, "only a running run can be paused");
        }
        RunHistoryEntity paused = get(runId);
        logAction("backfill_pause", paused.getPipelineName(), runId);
        return paused;
    }

    /**
     * Resumes a paused run from its committed cursor, adopting the pipeline's <em>current</em> generation — most
     * edits do not change what a row produces, and the operator who resumes a {@code pipeline_changed} run knows
     * which kind this was.
     *
     * <p>Schedules a <strong>fresh</strong> batch instance unconditionally, so the resume cannot be swallowed by
     * an execution of the run that is still running and about to exit on the {@code paused} it read. Any older
     * execution that later reaches a page commit finds the cursor or state moved and stops.
     */
    @Transactional
    public RunHistoryEntity resume(String runId) {
        RunHistoryEntity run = get(runId);
        requireAdmits(run, RunState::canResume, "only a paused run can be resumed");
        PipelineDto pipeline = requireRunnable(run.getPipelineName());
        int resumed;
        try {
            resumed = runHistoryRepository.resumeIfPaused(runId, pipeline.generation());
        } catch (DataIntegrityViolationException e) {
            throw concurrentRunConflict(run.getPipelineName(), e);
        }
        if (resumed == 0) {
            throw refused(runId, "only a paused run can be resumed");
        }
        RunHistoryEntity current = get(runId);
        scheduler.scheduleIfNotExists(backfillTask.task().instance(
                BackfillTask.resumeInstanceId(runId, current.getCursor(), Instant.now()), runId), Instant.now());
        logAction("backfill_resume", current.getPipelineName(), runId);
        return current;
    }

    @Transactional
    public RunHistoryEntity cancel(String runId) {
        requireAdmits(get(runId), RunState::canCancel, "only a running or paused run can be cancelled");
        if (runHistoryRepository.cancelIfActive(runId, Instant.now()) == 0) {
            throw refused(runId, "only a running or paused run can be cancelled");
        }
        RunHistoryEntity cancelled = get(runId);
        logAction("backfill_cancel", cancelled.getPipelineName(), runId);
        return cancelled;
    }

    /** Schedules the next continuation batch for a run to start from the given cursor. */
    public void scheduleRun(String runId, String cursor) {
        scheduler.scheduleIfNotExists(
                backfillTask.task().instance(BackfillTask.instanceId(runId, cursor), runId), Instant.now());
    }

    private static void logAction(String action, String pipelineName, String runId) {
        OperatorActionLog.record(action, pipelineName, "run=" + runId);
    }

    /**
     * The start and resume gates, in the order the first batch would meet them. Returns the cached pipeline so
     * the caller can pin its generation.
     */
    private PipelineDto requireRunnable(String pipelineName) {
        if (!pipelineCache.isPopulated()) {
            throw new PipelineCacheNotReadyException();
        }
        PipelineDto pipeline = pipelineCache.get(pipelineName);
        if (pipeline == null) {
            throw new PipelineNotFoundException(pipelineName);
        }
        pauseRegistry.current(pipelineName).ifPresent(pause -> {
            throw new BackfillConflictException("Pipeline " + pipelineName + " is " + describe(pause)
                    + "; resume the pipeline first");
        });
        if (spendPauseGate.isOverBudget(pipeline)) {
            throw new BackfillConflictException("Pipeline " + pipelineName
                    + " is over its daily spend budget; the budget resets at 00:00 UTC");
        }
        List<RunHistoryEntity> running =
                runHistoryRepository.findByPipelineNameAndState(pipelineName, RunState.RUNNING.dbValue());
        if (!running.isEmpty()) {
            throw new BackfillConflictException("A backfill is already running for pipeline " + pipelineName
                    + ": " + running.getFirst().getId());
        }
        return pipeline;
    }

    private static String describe(PipelinePauseRegistry.Pause pause) {
        String by = pause.origin() == PauseOrigin.BREAKER ? "paused by the DLQ circuit breaker" : "paused by an operator";
        return pause.resumesAt() == null ? by : by + " until " + pause.resumesAt();
    }

    /**
     * The unique index caught a start or resume racing another: the check above passed on both sides. The other
     * run's id is not readable inside this now-aborted transaction, so the message says where to find it.
     *
     * <p>Only that index's violation is a conflict. Any other integrity violation — a CHECK, a key collision — is
     * a defect, not a race, and is rethrown rather than reported as one.
     */
    private static BackfillConflictException concurrentRunConflict(String pipelineName,
                                                                   DataIntegrityViolationException e) {
        boolean oneRunning = ExceptionUtils.getThrowableList(e).stream()
                .map(Throwable::getMessage)
                .anyMatch(message -> message != null && message.contains(ONE_RUNNING_PER_PIPELINE_UK));
        if (!oneRunning) {
            throw e;
        }
        return new BackfillConflictException("Another backfill for pipeline " + pipelineName
                + " became running concurrently; find it with GET /v1/backfills?pipeline_name=" + pipelineName
                + "&state=running");
    }

    private static void requireAdmits(RunHistoryEntity run, Predicate<RunState> admits, String rule) {
        if (!admits.test(RunState.fromDbValue(run.getState()))) {
            throw new BackfillConflictException("Backfill " + run.getId() + " is " + run.getState() + "; " + rule);
        }
    }

    /** A conditional update touched nothing: the run changed since it was read. Reports what it is now. */
    private BackfillConflictException refused(String runId, String rule) {
        return new BackfillConflictException("Backfill " + runId + " is " + get(runId).getState() + "; " + rule);
    }
}
