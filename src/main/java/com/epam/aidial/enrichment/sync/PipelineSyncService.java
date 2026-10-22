package com.epam.aidial.enrichment.sync;

import com.epam.aidial.enrichment.adas.AdasRegistryClient;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.class1.Class1MaterializeTask;
import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.epam.aidial.enrichment.dial.DialRateLimiter;
import com.epam.aidial.enrichment.dlq.DlqCircuitBreaker;
import com.epam.aidial.enrichment.exception.AdasContractMismatchException;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.exception.LogSafe;
import com.epam.aidial.enrichment.observability.ReadinessState;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.status.PipelineActivity;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.PipelineCursorEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.runtime.PipelineRunOutcome;
import com.epam.aidial.enrichment.scan.BackpressureGate;
import com.epam.aidial.enrichment.scan.PipelineScanTask;
import com.epam.aidial.enrichment.scan.ScheduleFactory;
import com.epam.aidial.enrichment.spend.SpendPauseGate;
import com.epam.aidial.enrichment.spend.SpendService;
import com.epam.aidial.enrichment.sweep.GroupReadinessSweep;
import com.epam.aidial.enrichment.worker.HumanDuration;
import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceCurrentlyExecutingException;
import com.github.kagkarlsson.scheduler.task.SchedulableInstance;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.github.kagkarlsson.scheduler.task.helper.PlainScheduleAndData;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTaskWithPersistentSchedule;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Polls the ADAS pipeline registry and reconciles local state: the {@link PipelineCache} and the per-pipeline
 * db-scheduler recurring tasks.
 *
 * <p>The sync feed has no ETag/304, so the runner polls the full list and diffs client-side on each
 * pipeline's {@code generation}. For each enabled pipeline it creates a recurring instance under the task name
 * its {@code transform.type} selects — {@code class1-materialize} for a {@code sql} transform, {@code pipeline-scan}
 * otherwise — or reschedules it when the generation changed or the schedule the instance stores differs from the
 * derived one; pipelines that disappear from the feed
 * (disabled/deleted) have their instance cancelled. Because a pipeline can change lanes across a generation
 * bump, reconciliation sweeps every task name pipelines can be scheduled onto. A name deleted and recreated in ADAS is
 * detected by its new {@code created_at}, and the state the runner holds under the name is reset first (see
 * {@link #resetRecreated}).
 *
 * <p>Because the poll fetches the <em>full</em> enabled-pipeline list (never an incremental delta), a
 * successful empty response legitimately means "zero enabled pipelines" — including the case of disabling
 * or deleting the last remaining pipeline — and reconciles to empty (all tasks cancelled, cache cleared).
 * It takes <strong>two consecutive</strong> such polls, though: see {@link #reconcile}.
 * A <em>failed</em> poll is a different signal: {@link #sync()} catches the exception and returns
 * before {@code reconcile} runs, leaving the current cache and tasks untouched, so a transient ADAS
 * outage never mass-cancels pipelines.
 *
 * <p>An <strong>incompatible</strong> ADAS lands on that same arm, deliberately. A response without this
 * build's contract echo is one the runner has just decided it cannot read, so the one thing it must not do
 * is act on the body: the cache and tasks are kept, {@code reconcile} is never entered, and — the
 * load-bearing half — the sync is <em>not</em> marked succeeded, so a freshly started pod never reaches
 * readiness and its predecessor keeps serving the ADAS it matches. The verdict itself is recorded by
 * {@code AdasContractInterceptor}, not here, because a skew is usually met first by a scan tick or a
 * write-back rather than by this poll.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogExecution
public class PipelineSyncService {

    private final AdasRegistryClient registryClient;
    private final PipelineCache cache;
    private final Scheduler scheduler;
    private final PipelineScanTask pipelineScanTask;
    private final Class1MaterializeTask class1MaterializeTask;
    private final ScheduleFactory scheduleFactory;
    private final ReadinessState readinessState;
    private final DialRateLimiter dialRateLimiter;
    private final DlqCircuitBreaker dlqCircuitBreaker;
    private final RunnerMetrics runnerMetrics;
    private final SpendService spendService;
    private final SpendPauseGate spendPauseGate;
    private final BackpressureGate backpressureGate;
    private final GroupReadinessSweep groupReadinessSweep;
    private final Clock clock;
    private final PipelineRunOutcome runOutcome;
    private final GroupProperties groupProperties;
    private final PipelineCursorRepository cursorRepository;
    private final PipelineStateReset stateReset;
    private final PipelinePauseRegistry pauseRegistry;
    private final PipelineActivity pipelineActivity;

    /** Last refused generation per pipeline name, so a persistent refusal is logged once, not every poll. */
    private final Map<String, Long> refusedGenerations = new HashMap<>();

    /**
     * Last generation per group pipeline whose idle window was warned as too short for the mark-scan cadence — the
     * {@code refusedGenerations} idiom, so the warning is logged once per generation rather than every poll.
     */
    private final Map<String, Long> shortIdleGenerations = new HashMap<>();

    /** How many mark-scan cadences a group pipeline's idle window must span before admission stops warning. */
    static final int MIN_IDLE_CADENCES = 2;

    /**
     * Consecutive successful polls that returned an empty envelope. Reset by any non-empty poll; a
     * reconcile-to-empty needs {@link #EMPTY_FEEDS_TO_CONFIRM} of them — see {@link #reconcile}.
     */
    private int consecutiveEmptyFeeds;

    /**
     * The contract condition this service last logged, so a skew that meets every poll for the length of a
     * deploy says so once rather than once per poll. The same {@code refusedGenerations} idiom, for the same
     * reason — and the reason is part of the key, because "no echo at all" and "echoed 2" are different
     * deployments on the far side and an operator needs the one that is currently true.
     */
    private String lastRefusedContractReason;

    /**
     * How many consecutive empty feeds cancel every task. Two, so an unexpected empty answer costs one poll
     * interval to confirm instead of going straight to "every recurring instance cancelled, cache cleared,
     * readiness latched UP, and nothing anywhere reporting that enrichment stopped".
     */
    private static final int EMPTY_FEEDS_TO_CONFIRM = 2;

    /**
     * A persistent sync failure is logged on its first poll (with the one stack it gets) and then on every Nth, so an
     * outage costs a line every few minutes rather than a stack every poll. Its end is logged once, with the count.
     */
    static final int FAILURE_LOG_EVERY = 10;

    /** Consecutive failed polls, and when the first of them failed; reset by a poll that fetched the feed. */
    private int consecutiveFailures;
    private Instant failingSince;

    @Scheduled(fixedDelayString = "${adas.pipelines.poll-interval-ms:30000}",
            initialDelayString = "${adas.pipelines.initial-delay-ms:10000}")
    public void sync() {
        List<PipelineDto> pipelines;
        try {
            pipelines = registryClient.fetchPipelines(null);
        } catch (AdasContractMismatchException e) {
            // Its own arm, not because the outcome differs — it is the same "keep everything, reconcile
            // nothing, mark nothing succeeded" — but because the cause does. A transient outage is a WARN
            // that clears itself; this is a deploy-order fact that clears only when the matching ADAS lands,
            // and an operator reading the log needs to be told which of the two they are looking at. This
            // line is the "what the poll did about it" half; AdasCompatibilityState logs the condition.
            if (!Objects.equals(lastRefusedContractReason, e.reason())) {
                log.error("Pipeline sync refused: {}. Keeping the current cache and tasks, not reconciling, and "
                        + "not marking the sync succeeded, so a freshly started pod stays out of service",
                        e.reason());
                lastRefusedContractReason = e.reason();
            }
            return;
        } catch (RuntimeException e) {
            recordFailure(e);
            return;
        }
        recordRecovery();
        lastRefusedContractReason = null;
        reconcile(pipelines);
    }

    /** Logs a failed poll: the first with its stack, then every {@link #FAILURE_LOG_EVERY}th, the rest at DEBUG. */
    private void recordFailure(RuntimeException e) {
        consecutiveFailures++;
        if (consecutiveFailures == 1) {
            failingSince = clock.instant();
            log.warn("Pipeline sync failed; keeping current cache and tasks: {}", ErrorMessages.classify(e),
                    LogSafe.loggable(e));
        } else if (consecutiveFailures % FAILURE_LOG_EVERY == 0) {
            log.warn("Pipeline sync still failing after {} consecutive failures in {}; keeping current cache and tasks: {}",
                    consecutiveFailures, HumanDuration.format(Duration.between(failingSince, clock.instant())),
                    ErrorMessages.classify(e));
        } else {
            log.debug("Pipeline sync failed ({} consecutive): {}", consecutiveFailures, ErrorMessages.classify(e));
        }
    }

    /** Logs the end of a run of failed polls, once, with how many there were and how long they lasted. */
    private void recordRecovery() {
        if (consecutiveFailures == 0) {
            return;
        }
        log.info("Pipeline sync recovered after {} failure(s) in {}", consecutiveFailures,
                HumanDuration.format(Duration.between(failingSince, clock.instant())));
        consecutiveFailures = 0;
        failingSince = null;
    }

    /**
     * Reconciles recurring tasks + cache against the fetched pipeline list. Package-visible for tests.
     *
     * <p>An empty feed stays authoritative — a real "the last pipeline was disabled" must still cancel its
     * tasks — but it takes a <strong>confirming</strong> poll, costing one poll interval. The first,
     * unconfirmed one logs, meters, and returns having changed nothing else.
     *
     * <p>That first empty feed still marks the sync <strong>succeeded</strong>, which is not an oversight:
     * the readiness contributor answers "has this process ever read the registry", not "does the registry
     * have contents". Withholding readiness for a legitimately empty registry would keep a fresh pod out of
     * service and — with a poll interval longer than the startup budget — restart it forever over a state
     * that is not an error.
     */
    void reconcile(List<PipelineDto> pipelines) {
        // Poll failures never reach here — sync() catches them and returns, keeping the cache and tasks. The
        // !enabled() skip in admit() below is kept even so: it is what makes reconcile() correct for any
        // caller, independent of which filter the fetch happened to send, and it is the one place a disabled
        // pipeline is refused a task.
        if (pipelines.isEmpty()) {
            // Counted on every empty poll, the unconfirmed one included: it is the only trace of a feed that
            // said "no pipelines" and was not acted on.
            runnerMetrics.incrementEmptyFeeds();
            consecutiveEmptyFeeds++;
            if (consecutiveEmptyFeeds < EMPTY_FEEDS_TO_CONFIRM) {
                log.warn("Pipeline sync received an empty feed ({} of {} needed to confirm); keeping the current "
                                + "cache and tasks until the next poll agrees",
                        consecutiveEmptyFeeds, EMPTY_FEEDS_TO_CONFIRM);
                readinessState.markPipelineSyncSucceeded();
                return;
            }
            if (consecutiveEmptyFeeds == EMPTY_FEEDS_TO_CONFIRM) {
                // Once, on the poll that confirms it — the `refusedGenerations` idiom again. A registry that
                // is *steadily* empty reconciles to empty on every subsequent poll too, and a WARN repeated
                // every poll interval for a state an operator chose would bury the one that says it changed.
                log.warn("Pipeline sync received {} consecutive empty feeds; reconciling to zero enabled "
                        + "pipelines", consecutiveEmptyFeeds);
            }
        } else {
            consecutiveEmptyFeeds = 0;
        }

        Map<String, PipelineDto> desired = admit(pipelines);
        Set<String> previousPipelineNames = cache.getAll().stream().map(PipelineDto::name).collect(Collectors.toSet());
        // Before any scheduling, so a recreated pipeline is scheduled onto state that is already empty.
        Set<String> recreated = resetRecreated(desired);

        // Fetch existing instances per task name, with the schedule each one stores. A pipeline whose transform
        // type changed has a stale instance under its former lane, so every task name pipelines can land on must
        // be swept.
        Map<String, Map<String, PlainScheduleAndData>> current = new HashMap<>();
        for (String taskName : PipelineRouting.PIPELINE_TASK_NAMES) {
            Map<String, PlainScheduleAndData> instances = new HashMap<>();
            scheduler.fetchScheduledExecutionsForTask(taskName, PlainScheduleAndData.class,
                    exec -> instances.put(exec.getTaskInstance().getId(), storedData(exec)));
            current.put(taskName, instances);
        }

        int created = 0;
        int rescheduled = 0;
        int cancelled = 0;

        for (PipelineDto pipeline : desired.values()) {
            try {
                String taskName = PipelineRouting.taskName(pipeline);
                Schedule schedule = scheduleFactory.forPipeline(pipeline);
                PlainScheduleAndData data = new PlainScheduleAndData(schedule, pipeline.name());
                SchedulableInstance<PlainScheduleAndData> instance = taskFor(taskName).schedulableInstance(pipeline.name(), data);
                PipelineDto cached = cache.get(pipeline.name());
                Map<String, PlainScheduleAndData> existing = current.get(taskName);
                if (!existing.containsKey(pipeline.name())) {
                    if (!scheduler.scheduleIfNotExists(instance)) {
                        // The listing above omits a picked instance, so "absent" can mean "running", killed mid-run
                        // and not yet declared dead, or just created by another replica. Nothing was created; the
                        // stored-schedule comparison below catches it on a later poll, once it is listed.
                        log.debug("Pipeline {} not created on {}: an instance already exists (picked, or created concurrently)",
                                pipeline.name(), taskName);
                        continue;
                    }
                    created++;
                    log.info("Pipeline {} added: lane={} generation={}{}", pipeline.name(), PipelineRouting.lane(pipeline),
                            generationChange(cached, pipeline), cached == null ? "" : " (moved from lane " + PipelineRouting.lane(cached) + ")");
                } else {
                    // The stored schedule is what converges, because ADAS can rewrite a trigger without a bump (V44
                    // did), and an instance picked at one poll is simply compared again at the next. A restart alone
                    // (empty cache) does not reschedule a matching instance: that would push an overdue cron fire to
                    // its next slot and wipe the instance's failure counters. A generation bump still does — but only
                    // on this poll: the cache records the new generation regardless, so a generation-only reschedule
                    // that is lost here is never retried. That costs timing only, because the stored schedule already
                    // matches the new declaration.
                    boolean scheduleChanged = !data.equals(existing.get(pipeline.name()));
                    boolean generationChanged = cached != null && cached.generation() != pipeline.generation();
                    // A recreated pipeline is rescheduled like a generation bump even when the generation coincides,
                    // so the instance's stored failure counters do not carry over from the pipeline that was deleted.
                    boolean wasRecreated = recreated.contains(pipeline.name());
                    // A false answer is a lost optimistic-version race (db-scheduler logs it): a differing stored
                    // schedule is compared again next poll, a generation-only bump is not.
                    if ((scheduleChanged || generationChanged || wasRecreated) && scheduler.reschedule(instance)) {
                        rescheduled++;
                        log.info("Pipeline {} rescheduled: lane={} generation={}{}{}", pipeline.name(), PipelineRouting.lane(pipeline),
                                generationChange(cached, pipeline), scheduleChanged ? " (stored schedule differed)" : "",
                                wasRecreated ? " (recreated)" : "");
                    }
                }
            } catch (TaskInstanceCurrentlyExecutingException e) {
                // Picked between the listing and the reschedule. A differing stored schedule is compared again next
                // poll; a generation-only bump is not retried, since the cache records the new generation anyway.
                log.debug("Pipeline {} not rescheduled: its instance is picked by an execution", pipeline.name());
            } catch (RuntimeException e) {
                // Isolate a single pipeline's failure (invalid schedule, transient scheduler/DB error) so it can't
                // abort the whole reconcile and leave orphan tasks or a divergent cache. The pipeline stays cached
                // at its new declaration: an uncached one has its queued work dropped irreversibly by the workers,
                // and the create path and the stored-schedule comparison retry it on the next poll anyway.
                log.warn("Skipping pipeline {} during reconcile: {}", pipeline.name(), e.toString());
            }
        }

        // Cancel an instance whose pipeline left the feed entirely, was refused by the admission gate, or is
        // no longer routed to this task name — the last case is what migrates a type-changed pipeline.
        for (Map.Entry<String, Map<String, PlainScheduleAndData>> entry : current.entrySet()) {
            String taskName = entry.getKey();
            for (String instanceId : entry.getValue().keySet()) {
                // The instance id of a recurring per-pipeline task is the pipeline name.
                PipelineDto pipeline = desired.get(instanceId);
                if (pipeline != null && PipelineRouting.taskName(pipeline).equals(taskName)) {
                    continue;
                }
                try {
                    scheduler.cancel(TaskInstanceId.of(taskName, instanceId));
                    cancelled++;
                    if (pipeline == null) {
                        // A lane migration is reported by the "added" line of the lane it moved to.
                        PipelineDto cached = cache.get(instanceId);
                        log.info("Pipeline {} removed: lane={} generation={}", instanceId,
                                cached == null ? taskName : PipelineRouting.lane(cached), cached == null ? "-" : cached.generation());
                    }
                } catch (RuntimeException e) {
                    log.warn("Failed to cancel {} task for pipeline {}: {}", taskName, instanceId, e.toString());
                }
            }
        }

        cache.replaceAll(desired);
        evictRemovedPipelines(previousPipelineNames, desired.keySet());
        runOutcome.retain(desired.keySet());
        desired.keySet().forEach(name -> runnerMetrics.recordSpend(name, spendService.today(name).calls()));
        readinessState.markPipelineSyncSucceeded();
        // Every poll ends here; only one that changed something is worth an INFO line.
        log.atLevel(created + rescheduled + cancelled > 0 ? Level.INFO : Level.DEBUG)
                .log("Pipeline sync: {} enabled pipelines ({} created, {} rescheduled, {} cancelled)",
                        desired.size(), created, rescheduled, cancelled);
    }

    /**
     * The schedule and data an existing instance stores, or null when they cannot be read — which compares unequal
     * to every derived value, so an unreadable instance is rescheduled rather than trusted.
     */
    private static PlainScheduleAndData storedData(ScheduledExecution<PlainScheduleAndData> execution) {
        try {
            return execution.getData();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code from->to} against the cached declaration, or the bare generation when nothing was cached. */
    private static String generationChange(PipelineDto cached, PipelineDto pipeline) {
        return cached == null ? String.valueOf(pipeline.generation()) : cached.generation() + "->" + pipeline.generation();
    }

    /**
     * Filters the feed down to the pipelines the runner can actually execute. A pipeline it cannot execute is
     * refused here rather than cached and scheduled into repeated failure: caching it would make it look
     * healthy in the ops introspection endpoint while every fire failed. The refusal is logged once per
     * generation (the poll repeats every 30s) and published as a gauge.
     */
    private Map<String, PipelineDto> admit(List<PipelineDto> pipelines) {
        Map<String, PipelineDto> desired = new LinkedHashMap<>();
        Map<String, Long> refused = new HashMap<>();
        Map<String, Long> shortIdle = new HashMap<>();
        for (PipelineDto pipeline : pipelines) {
            if (!pipeline.enabled()) {
                continue;
            }
            String reason = PipelineRouting.unsupportedReason(pipeline);
            if (reason == null) {
                desired.put(pipeline.name(), pipeline);
                warnOnShortIdle(pipeline, shortIdle);
                continue;
            }
            refused.put(pipeline.name(), pipeline.generation());
            if (!Objects.equals(refusedGenerations.get(pipeline.name()), pipeline.generation())) {
                log.warn("Refusing pipeline {} (generation {}): {}", pipeline.name(), pipeline.generation(), reason);
            }
        }
        refusedGenerations.keySet().retainAll(refused.keySet());
        refusedGenerations.putAll(refused);
        shortIdleGenerations.keySet().retainAll(shortIdle.keySet());
        shortIdleGenerations.putAll(shortIdle);
        runnerMetrics.setUnsupportedPipelines(refused.size());
        return desired;
    }

    /**
     * Warns, once per generation, when an admitted group pipeline's effective idle window is shorter than
     * {@value #MIN_IDLE_CADENCES} mark-scan cadences. A later row can then sit unmarked for longer than the group's
     * quiet period, so the sweep may judge a still-active group idle, evaluate it early, and evaluate it again once
     * the row is marked — a second paid call. A warning rather than a refusal: the pipeline still labels, and the
     * fix is the runner's {@code group.scan-cadence}, not the declaration.
     */
    private void warnOnShortIdle(PipelineDto pipeline, Map<String, Long> shortIdle) {
        if (!PipelineRouting.isGroup(pipeline)) {
            return;
        }
        Duration idle;
        try {
            idle = PipelineRouting.idle(pipeline, groupProperties.defaultIdle());
        } catch (RuntimeException e) {
            // An unparseable idle window fails the sweep loudly on its own; admission is not the place to report it.
            return;
        }
        Duration minimum = groupProperties.scanCadence().multipliedBy(MIN_IDLE_CADENCES);
        if (idle.compareTo(minimum) >= 0) {
            return;
        }
        shortIdle.put(pipeline.name(), pipeline.generation());
        if (!Objects.equals(shortIdleGenerations.get(pipeline.name()), pipeline.generation())) {
            log.warn("Group pipeline {} (generation {}) has an idle window of {}, under {}x group.scan-cadence ({}): a still-active "
                            + "group can look idle and be evaluated early, then again. Lower GROUP_SCAN_CADENCE",
                    pipeline.name(), pipeline.generation(), HumanDuration.format(idle), MIN_IDLE_CADENCES,
                    HumanDuration.format(groupProperties.scanCadence()));
        }
    }

    private RecurringTaskWithPersistentSchedule<PlainScheduleAndData> taskFor(String taskName) {
        if (Class1MaterializeTask.TASK_NAME.equals(taskName)) {
            return class1MaterializeTask.task();
        }
        return pipelineScanTask.task();
    }

    /**
     * Frees per-pipeline resources for pipelines that left the synced set: the rate limiter's bucket, the per-pipeline
     * metric gauges, the breaker's group-failure count and the gates' last reported transitions — each an in-memory
     * map keyed by pipeline name, which would otherwise retain an entry per pipeline the runner has ever seen, and
     * hand a re-enabled pipeline the failure count or gate state it left behind.
     */
    private void evictRemovedPipelines(Set<String> previous, Set<String> desired) {
        for (String pipelineName : previous) {
            if (!desired.contains(pipelineName)) {
                evict(pipelineName);
            }
        }
    }

    private void evict(String pipelineName) {
        dialRateLimiter.evict(pipelineName);
        runnerMetrics.evictPipeline(pipelineName);
        dlqCircuitBreaker.evict(pipelineName);
        spendPauseGate.evict(pipelineName);
        backpressureGate.evict(pipelineName);
        groupReadinessSweep.evict(pipelineName);
    }

    /**
     * Resets the state of every admitted pipeline whose name was recreated, and answers which ones were.
     *
     * <p>ADAS identifies a pipeline by name, so a delete followed by a create under that name is a different pipeline
     * that would otherwise inherit everything the runner keyed on the name. {@code generation} cannot tell the two
     * apart — it restarts at 1 and often climbs back to the old value — but {@code created_at} can: ADAS sets it once,
     * at registration. The value last seen is stored on the pipeline's cursor row and compared on every poll:
     *
     * <ul>
     *   <li>none stored (no row, or a row from before the column): adopt the feed's value, with no reset. This is the
     *       first sighting of a pipeline, and the first sync after deploy. The exception is a row from before the
     *       column last written before the pipeline was created: it belonged to a predecessor, and is reset;</li>
     *   <li>equal: nothing to do, and nothing is written;</li>
     *   <li>different: the name was recreated. Its persisted state is reset ({@link PipelineStateReset}), its
     *       in-memory state evicted, and its recurring instance rescheduled.</li>
     * </ul>
     *
     * <p>The in-memory state is also reset when only the <em>cached</em> declaration's value differs: another pod
     * reset the persisted state during a rolling-update overlap. The persisted reset is a compare-and-set on the value
     * read here, so a second pod that read the same old value resets nothing, and never discards progress the new
     * pipeline has already made.
     *
     * <p>A failure is isolated per pipeline and retried next poll — except a failure to read the stored values at all,
     * which skips the check for every pipeline this poll. Until a reset succeeds, the stale cursor cannot be advanced:
     * the cursor commit refuses a write whose {@code created_at} does not match the row's.
     */
    private Set<String> resetRecreated(Map<String, PipelineDto> desired) {
        Set<String> recreated = new HashSet<>();
        Map<String, PipelineCursorEntity> stored = new HashMap<>();
        try {
            for (PipelineCursorEntity row : cursorRepository.findAllById(desired.keySet())) {
                stored.put(row.getPipelineName(), row);
            }
        } catch (RuntimeException e) {
            log.warn("Skipping the recreated-pipeline check this poll: {}", ErrorMessages.classify(e));
            return recreated;
        }
        for (PipelineDto pipeline : desired.values()) {
            Instant createdAt = pipeline.createdAt();
            if (createdAt == null) {
                // Unknown: neither adopt nor reset. The compiled feed always carries it.
                continue;
            }
            String name = pipeline.name();
            try {
                PipelineCursorEntity row = stored.get(name);
                Instant persisted = row == null ? null : row.getPipelineCreatedAt();
                Optional<PipelineStateReset.Removed> removed = Optional.empty();
                if (row == null || (persisted == null && !predates(row, createdAt))) {
                    cursorRepository.adopt(name, createdAt, clock.instant());
                } else if (!createdAt.equals(persisted)) {
                    removed = stateReset.reset(name, persisted, createdAt);
                }
                if (removed.isPresent()) {
                    // Recorded before anything else can throw: the persisted reset has committed, and the next poll
                    // finds nothing left to compare, so this poll is the only one that can reschedule the instance.
                    recreated.add(name);
                    runnerMetrics.incrementPipelineStateResets();
                    Optional<PipelinePauseRegistry.Pause> keptPause = resetInMemory(name);
                    log.warn("Pipeline {} was recreated (created_at {} -> {}); runner state reset: cursor cleared, {} group(s) deleted, "
                                    + "{} queued work item(s) deleted, {} backfill run(s) cancelled{}", name, persisted, createdAt,
                            removed.get().groups(), removed.get().workItems(), removed.get().runsCancelled(), keptPause(keptPause));
                    continue;
                }
                // Not reset here, but this pod may still hold the deleted pipeline in memory: another pod reset the
                // persisted state first (the cursor row already recorded the new value, or the compare-and-set found
                // it handed over).
                PipelineDto cached = cache.get(name);
                if (cached != null && cached.createdAt() != null && !cached.createdAt().equals(createdAt)) {
                    Optional<PipelinePauseRegistry.Pause> keptPause = resetInMemory(name);
                    log.info("Pipeline {} was recreated (created_at {} -> {}) and its persisted state already reset; "
                            + "in-memory state reset{}", name, cached.createdAt(), createdAt, keptPause(keptPause));
                }
            } catch (RuntimeException e) {
                log.warn("Recreated-pipeline check failed for pipeline {}; retrying next poll: {}", name,
                        ErrorMessages.classify(e));
            }
        }
        return recreated;
    }

    /**
     * Whether a row that records no {@code created_at} was last written before the pipeline now under its name was
     * created. Such a row is from before the column, left unadopted because its pipeline was not in the feed at the
     * first sync after deploy — and it cannot be this pipeline's, which did not exist when the row was last written.
     */
    private static boolean predates(PipelineCursorEntity row, Instant createdAt) {
        Instant lastWrite = row.getLastScanAt() == null || row.getUpdatedAt().isAfter(row.getLastScanAt())
                ? row.getUpdatedAt() : row.getLastScanAt();
        return lastWrite.isBefore(createdAt);
    }

    private static String keptPause(Optional<PipelinePauseRegistry.Pause> pause) {
        return pause.map(p -> "; operator pause kept (" + p.reason() + ")").orElse("");
    }

    /**
     * Evicts what the runner holds in memory for a recreated pipeline, through the same path as a removal, plus the
     * last fire outcome and last write it would otherwise report as the new pipeline's. A breaker pause is lifted;
     * an operator pause is kept and returned.
     */
    private Optional<PipelinePauseRegistry.Pause> resetInMemory(String pipelineName) {
        evict(pipelineName);
        runOutcome.forget(pipelineName);
        pipelineActivity.forget(pipelineName);
        return pauseRegistry.clearBreakerPause(pipelineName);
    }
}
