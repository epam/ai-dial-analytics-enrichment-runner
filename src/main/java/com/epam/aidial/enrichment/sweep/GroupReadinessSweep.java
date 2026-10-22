package com.epam.aidial.enrichment.sweep;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.configuration.DbSchedulerProperties;
import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.exception.LogSafe;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.RunnerTracer;
import com.epam.aidial.enrichment.observability.TransitionLog;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.GroupStateWriter;
import com.epam.aidial.enrichment.scan.BackpressureGate;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.scan.InflightCounter;
import com.epam.aidial.enrichment.spend.SpendPauseGate;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.sync.PipelineRouting;
import com.epam.aidial.enrichment.worker.WorkPriority;
import com.epam.aidial.enrichment.worker.WorkTasks;
import com.epam.aidial.enrichment.worker.WorkerMdc;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.group.GroupPayload;
import com.github.kagkarlsson.scheduler.Scheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enqueues one work item per group that is <em>dirty</em>, <em>ready</em> and <em>within budget</em>.
 *
 * <p>This is the second half of the group-enrichment completeness argument, and the reason it is a scheduled pass
 * rather than part of the scan: a group whose last member has already arrived is never dirtied again, so
 * nothing in the scan path would ever re-examine it. Readiness is a question about elapsed time, which
 * only a clock can answer.
 *
 * <p>Dirtiness compares {@code group_version} to {@code computed_version}; readiness is the disjunction of
 * the pipeline's declared conditions — {@code ready_when.idle} against last activity, {@code max_staleness}
 * against the last computation, and the recorded {@code ready_when.signal} verdict, which fires with no time
 * bound because a group declared complete should not wait out its idle window. A further condition applies
 * to a pipeline that declares one: the group must be within its {@code ready_when.cost_ceiling} for the current
 * UTC day. All of them are evaluated in the database against runner-owned state, so a pass costs one indexed
 * query per group pipeline and never touches ADAS.
 *
 * <p>A pass is bounded twice: by {@code group.sweep-batch}, which limits how fast the queue fills, and by
 * {@code group.max-inflight}, which limits how deep it gets. The second is the one that matters after an
 * outage, and it is not about throughput — the pool drains at the same rate either way. It is about where a
 * backlog waits. A group waiting in {@code group_state} is one durable row that costs nothing, is re-selected
 * for free next pass, and is evaluated against the world as it is when it finally runs; the same group
 * waiting in {@code scheduled_tasks} is a queued execution pinning a stale {@code group_version}, exposed to
 * every restart, that at any batch size materially larger than the pool will not run for many minutes.
 *
 * <p>A group that is dirty and ready is still skipped while work for that exact version is already queued.
 * Dirtiness is cleared by the <em>write</em>, not by the compute, so between a compute finishing and its write
 * landing the group selects as dirty with nothing for {@code scheduleIfNotExists} to suppress — and a second
 * evaluation there is pure waste, since the write's deterministic id discards its result. The window is
 * covered because the write is enqueued in the compute's own transaction, so one of the two rows is always
 * present.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@LogExecution
public class GroupReadinessSweep {

    private final PipelineCache pipelineCache;
    private final GroupStateWriter groupStateWriter;
    private final GroupChunkTask groupChunkTask;
    private final Scheduler scheduler;
    private final GroupProperties groupProperties;
    private final DbSchedulerProperties dbSchedulerProperties;
    private final InflightCounter inflightCounter;
    private final BackpressureGate backpressureGate;
    private final PipelinePauseRegistry pauseRegistry;
    private final SpendPauseGate spendPauseGate;
    private final RunnerMetrics runnerMetrics;
    private final RunnerTracer runnerTracer;
    private final Clock clock;

    /** Whether a pipeline was last reported with groups at its cost ceiling, and whether as paused. */
    private final TransitionLog<Boolean> ceilingTransitions = new TransitionLog<>(Boolean.FALSE);
    private final TransitionLog<Boolean> pauseTransitions = new TransitionLog<>(Boolean.FALSE);

    /** Forgets a pipeline's last reported pause and ceiling states when it leaves the synced set, bounding the maps. */
    public void evict(String pipelineName) {
        pauseTransitions.forget(pipelineName);
        ceilingTransitions.forget(pipelineName);
    }

    @Scheduled(fixedDelayString = "${group.sweep-interval:30s}",
            initialDelayString = "${group.sweep-initial-delay:15s}")
    public void sweep() {
        long startedAt = System.nanoTime();
        int blocked = 0;
        int enqueued = 0;
        int suppressed = 0;
        int deferred = 0;
        for (PipelineDto pipeline : pipelineCache.getAll()) {
            if (!PipelineRouting.isGroup(pipeline)) {
                continue;
            }
            try {
                SweepOutcome outcome = sweepPipeline(pipeline);
                blocked += outcome.blocked();
                enqueued += outcome.enqueued();
                suppressed += outcome.suppressed();
                deferred += outcome.deferred();
            } catch (RuntimeException e) {
                // Isolate one pipeline's failure so it cannot stop the other group pipelines' sweeps.
                // Classified, and the stack only at DEBUG: a persistent failure repeats every pass.
                log.warn("Group sweep failed for pipeline {}: {}", pipeline.name(), ErrorMessages.classify(e));
                log.debug("Group sweep failure for pipeline {}", pipeline.name(), LogSafe.loggable(e));
            }
        }
        runnerMetrics.setGroupCeilingBlocked(blocked);
        runnerMetrics.recordGroupSweep(enqueued, suppressed, deferred,
                Duration.ofNanos(System.nanoTime() - startedAt));
    }

    /**
     * What one pipeline's sweep contributed to the pass's totals.
     *
     * <p>{@code suppressed} and {@code deferred} are separate on purpose. Suppressed means a group whose own
     * work is already queued — the sweep doing exactly what it should, and the number an operator drives to
     * zero. Deferred means a group the queue had no room for, which is normal while a backlog drains and a
     * symptom when it persists. Folded together they would be unreadable as either.
     */
    private record SweepOutcome(int enqueued, int blocked, int suppressed, int deferred) {
        static final SweepOutcome NOTHING = new SweepOutcome(0, 0, 0, 0);
    }

    /**
     * Sweeps one pipeline.
     *
     * @return what it enqueued, what the cost ceiling declined, and what it held back as already in flight
     */
    private SweepOutcome sweepPipeline(PipelineDto pipeline) {
        String pipelineName = pipeline.name();
        boolean paused = pauseRegistry.isPaused(pipelineName);
        // The sweep passes every few seconds, so the skip is INFO only when the pause begins or ends; the heartbeat
        // reports a pause that persists.
        if (pauseTransitions.changed(pipelineName, paused)) {
            log.info(paused ? "Pipeline {} is paused; skipping group sweep" : "Pipeline {} is no longer paused; group sweep resumes", pipelineName);
        } else if (paused) {
            log.debug("Pipeline {} is paused; skipping group sweep", pipelineName);
        }
        if (paused) {
            return SweepOutcome.NOTHING;
        }
        // The two gates log their own transitions; the per-pass skip is DEBUG so a closed gate is one line, not one
        // per pass.
        if (spendPauseGate.isOverBudget(pipeline)) {
            log.debug("Pipeline {} is over its daily spend budget; skipping group sweep", pipelineName);
            return SweepOutcome.NOTHING;
        }
        if (backpressureGate.isBackpressured(pipelineName)) {
            // The sweep, not the dirty-mark, is what enqueues group work, so it is where the gate belongs:
            // backpressuring the scan of a group pipeline only slows the marking. Skipping a pass is free — the
            // groups stay dirty and the next pass picks them up.
            log.debug("Backpressure: skipping group sweep for pipeline {}", pipelineName);
            return SweepOutcome.NOTHING;
        }

        try (WorkerMdc.Scope ignored = WorkerMdc.scope().put(WorkerMdc.PIPELINE_NAME, pipelineName)) {
            return runnerTracer.inSpan("enrichment.group-sweep", Map.of("pipeline.name", pipelineName),
                    () -> enqueueReady(pipeline));
        }
    }

    /**
     * The pipeline's readiness declaration resolved against {@code now}: the one construction the sweep selects with
     * and the runtime view counts with, so the two cannot disagree about which groups are ready.
     */
    public static GroupStateWriter.Readiness readiness(PipelineDto pipeline, Duration defaultIdle, Instant now) {
        Duration maxStaleness = PipelineRouting.maxStaleness(pipeline);
        return new GroupStateWriter.Readiness(
                now.minus(PipelineRouting.idle(pipeline, defaultIdle)),
                maxStaleness == null ? null : now.minus(maxStaleness),
                PipelineRouting.hasSignal(pipeline));
    }

    private SweepOutcome enqueueReady(PipelineDto pipeline) {
        Instant now = clock.instant();
        Integer ceiling = PipelineRouting.costCeiling(pipeline);
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        int batch = groupProperties.resolveSweepBatch();
        GroupStateWriter.Readiness readiness = readiness(pipeline, groupProperties.defaultIdle(), now);

        List<GroupStateWriter.DirtyGroup> ready = groupStateWriter.findDirtyAndReady(
                pipeline.name(), readiness, today, ceiling, batch);

        // Only a pipeline that declares a ceiling can have anything declined by it.
        int blocked = ceiling == null ? 0
                : groupStateWriter.countCeilingBlocked(pipeline.name(), readiness, today, ceiling);
        // INFO when the pipeline starts or stops having groups at the ceiling; every pass in between is DEBUG, since
        // the gauge carries the count.
        boolean atCeiling = blocked > 0;
        if (ceilingTransitions.changed(pipeline.name(), atCeiling)) {
            log.info("Group sweep for pipeline {}: {} group(s) at their daily cost ceiling of {}",
                    pipeline.name(), blocked, ceiling);
        } else if (atCeiling) {
            log.debug("Group sweep for pipeline {}: {} group(s) at their daily cost ceiling of {}",
                    pipeline.name(), blocked, ceiling);
        }

        if (ready.isEmpty()) {
            return new SweepOutcome(0, blocked, 0, 0);
        }

        // One query per pipeline per pass, over the same tasks and prefix backpressure counts. Fetched here
        // rather than per group so the check costs a set lookup, not a round trip.
        Set<String> inFlight = inflightCounter.instanceIdsByTasksAndPrefix(
                WorkTasks.PIPELINE_SCOPED, DeterministicId.pipelinePrefix(pipeline.name()));

        // The same id set, read for a second purpose: how much room is left under the pipeline's depth
        // ceiling. Pending write-back counts toward it, which is the point — a pipeline whose writes are
        // backed up must not have fresh compute stacked on top of them.
        //
        // This is not scan.max-inflight, and does not replace it. That one is the coarse, cross-lane stop for
        // a pipeline that has run away, an order of magnitude above this; it does not fire for a merely deep group
        // queue, which is what this ceiling bounds, and it is not meant to.
        int headroom = Math.max(0,
                groupProperties.resolveInflightCeiling(dbSchedulerProperties.threads()) - inFlight.size());

        long generation = pipeline.generation();
        int enqueued = 0;
        int suppressed = 0;
        int deferred = 0;
        for (GroupStateWriter.DirtyGroup group : ready) {
            String instanceId = DeterministicId.groupInstanceId(
                    pipeline.name(), generation, group.groupKey(), group.groupVersion());
            if (inFlight.contains(instanceId)
                    || inFlight.contains(DeterministicId.upsertInstanceId(instanceId))) {
                // A group is stamped clean only once its write lands, so from the moment its compute item is
                // deleted until its write completes it still selects as dirty — and scheduleIfNotExists has
                // nothing left to collide with. Re-evaluating there buys nothing: the second result is
                // discarded by the write's own deterministic id, so the model call is spent for no label.
                suppressed++;
                continue;
            }
            if (enqueued >= headroom) {
                // Deferred, not dropped: the group stays dirty, keeps its place in the oldest-activity
                // ordering, and is enqueued by a later pass once this pipeline's pending work drains.
                deferred++;
                continue;
            }
            GroupPayload payload = new GroupPayload(
                    pipeline.name(), generation, group.groupKey(), group.groupVersion());
            // scheduleIfNotExists on a version-keyed id is what makes a re-sweep of an unchanged group a
            // no-op — the sweep is free to run more often than groups change. The id carries no backfill run,
            // deliberately: a group backfill only marks groups dirty, and this sweep — not the run — enqueues the
            // evaluation, so a dead letter from it is never a run's.
            boolean scheduled = scheduler.scheduleIfNotExists(
                    groupChunkTask.task().instanceBuilder(instanceId)
                            .data(payload)
                            .priority(WorkPriority.LIVE)
                            .build(),
                    now);
            if (scheduled) {
                enqueued++;
            } else {
                // Every replica sweeps, so two passes can read the same id set and reach here together; the
                // one that loses the insert created nothing. Counting it as enqueued would inflate exactly
                // the number the suppressed count exists to drive to zero.
                suppressed++;
            }
        }
        // A pass that enqueued nothing — everything in flight or deferred — repeats every interval while the queue
        // drains, so it is DEBUG; the metrics carry the counts.
        log.atLevel(enqueued > 0 ? Level.INFO : Level.DEBUG)
                .log("Group sweep for pipeline {}: {} dirty and ready group(s) enqueued{}{}{}",
                        pipeline.name(), enqueued,
                        suppressed == 0 ? "" : ", " + suppressed + " skipped (work in flight)",
                        deferred == 0 ? "" : ", " + deferred + " deferred (queue at its depth ceiling)",
                        ready.size() == batch ? " (batch full, more may remain)" : "");
        return new SweepOutcome(enqueued, blocked, suppressed, deferred);
    }
}
