package com.epam.aidial.enrichment.observability;

import com.epam.aidial.enrichment.adas.AdasCompatibilityState;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.DlqItemRepository;
import com.epam.aidial.enrichment.persistence.GroupStateRepository;
import com.epam.aidial.enrichment.scan.InflightCounter;
import com.epam.aidial.enrichment.scan.LagSignal;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.PreFoldWorkItem;
import com.epam.aidial.enrichment.worker.WorkTasks;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Setter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Micrometer instrumentation for the runner's own signals, exposed via the Prometheus scrape
 * endpoint. Gauges read cheap live state (pipelines cached, DLQ size refreshed on a timer, enrich-chunk
 * queue depth via a COUNT(*), per-pipeline scan lag); counters/timer capture DIAL latency and outcomes,
 * validation failures, and enrichment rows written. Per-pipeline lag gauges are evicted on pipeline removal so
 * their cardinality stays bounded. Auto-instrumentation covers the outbound HTTP spans separately.
 */
@Component
public class RunnerMetrics {

    private final MeterRegistry registry;
    private final InflightCounter inflightCounter;
    private final DlqItemRepository dlqItemRepository;
    private final GroupStateRepository groupStateRepository;

    /**
     * Every task whose depth is worth reporting; the recurring ticks are one row each and are not. Shared with
     * backpressure so the number an operator reads and the number the scan gates on cannot diverge.
     */
    private static final List<String> QUEUE_TASKS = WorkTasks.PIPELINE_SCOPED;

    /**
     * The cursor-driven lanes, as a bounded tag for the stall counter. A stall is a property of the cursor
     * protocol rather than of what a page becomes, so the row and group scans share one lane: both stall in
     * {@code PipelineScanExecutor}'s drain loop, on the same condition, with the same consequence.
     */
    public enum CursorLane {
        SCAN, CLASS1, BACKFILL;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Why a scanned row produced no work. A bounded tag on {@code runner.scan.rows.skipped}, which is the
     * one event on the scan path that moves nothing else: the cursor advances, the lane reports success, lag
     * reports caught up and the DLQ stays empty. Counting the skips by reason is what tells "this pipeline
     * enriches a sampled fraction" from "this pipeline enriched nothing".
     */
    public enum SkipReason {
        /** Deterministically sampled out by the pipeline's {@code sample_fraction}. */
        SAMPLING,
        /** The grain column was present in the page but held null, so there is no key to write a row at. */
        NULL_GRAIN_KEY,
        /** The group-by column was present but held null, so the row belongs to no group. */
        NULL_GROUP_KEY;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Why a page's cursor advance was refused. The pair of {@link SkipReason}: it says the runner declined
     * to advance, which is otherwise visible only as a tick failure among all other tick failures.
     */
    public enum RefusalReason {
        /** A column the lane dereferences was absent from the page — a projection skew, not row data. */
        MISSING_COLUMN,
        /** The page's accounted outcomes did not cover every row it read. */
        UNACCOUNTED_ROWS;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Timer dialLatency;
    private final Counter dialSuccess;
    private final Counter dialError;
    private final Counter validationFailures;
    private final Counter rowsUpserted;
    private final Counter class1TickFailures;
    private final Counter dialRateLimited;
    private final Counter groupsEvaluated;
    private final Counter groupsDeadLettered;
    private final Counter supersededGroupEvaluations;
    private final Counter groupsEnqueued;
    private final Counter groupsSweepSuppressed;
    private final Counter groupsSweepDeferred;
    private final Counter groupInputBudgetCuts;
    private final Counter emptyFeeds;
    private final Counter pipelineStateResets;
    private final Timer groupSweepDuration;

    private final Map<String, Long> lagByPipeline = new ConcurrentHashMap<>();
    private final Map<String, Gauge> lagGauges = new ConcurrentHashMap<>();
    private final Map<String, Long> spendByPipeline = new ConcurrentHashMap<>();
    private final Map<String, Gauge> spendGauges = new ConcurrentHashMap<>();
    private final Map<String, Counter> class1RowsByPipeline = new ConcurrentHashMap<>();
    private final Map<String, Counter> backfillBatchFailuresByPipeline = new ConcurrentHashMap<>();
    private final Map<CursorLane, Counter> cursorStalls = new EnumMap<>(CursorLane.class);
    private final Map<PreFoldWorkItem.Lane, Counter> preFoldWorkItems =
            new EnumMap<>(PreFoldWorkItem.Lane.class);
    // Nested per pipeline, not flat on "pipeline|reason": eviction has to drop every reason a pipeline ever
    // recorded, and a flat key would leave it scanning the map for a prefix.
    private final Map<String, Map<SkipReason, Counter>> skippedRows = new ConcurrentHashMap<>();
    private final Map<String, Map<RefusalReason, Counter>> refusedPages = new ConcurrentHashMap<>();

    // Refreshed off the scrape path so a Prometheus scrape never triggers a count(*) over dlq_item.
    private volatile double dlqSize;

    /**
     * How many pipelines the latest sync refused to cache or schedule because the runner cannot execute them.
     * Published by each pipeline sync and read straight off the gauge. A gauge rather than a counter: the useful
     * question is "how many are refused right now", and a counter incremented every poll would read as a rate.
     */
    @Setter
    private volatile int unsupportedPipelines;

    /**
     * How many groups the latest readiness sweep declined on their pipeline's daily cost ceiling. Published by each
     * sweep pass and read straight off the gauge. A working ceiling is otherwise indistinguishable from a group
     * that stopped changing — no work items, no errors, no log output — so this is what tells a protecting
     * budget from a broken pipeline.
     */
    @Setter
    private volatile int groupCeilingBlocked;

    // Refreshed off the scrape path, like dlqSize: a scrape must never become a function of table size.
    private volatile long activeGroups;
    private volatile long dirtyGroups;
    private final Map<String, Long> queueDepths = new ConcurrentHashMap<>();

    public RunnerMetrics(MeterRegistry registry, InflightCounter inflightCounter,
                         PipelineCache pipelineCache, DlqItemRepository dlqItemRepository,
                         GroupStateRepository groupStateRepository, PipelinePauseRegistry pauseRegistry,
                         AdasCompatibilityState compatibilityState) {
        this.registry = registry;
        this.inflightCounter = inflightCounter;
        this.dlqItemRepository = dlqItemRepository;
        this.groupStateRepository = groupStateRepository;

        Gauge.builder("runner.pipelines.cached", pipelineCache, PipelineCache::size).register(registry);
        // A pipeline the DLQ breaker auto-paused stops enqueueing and says so in one log line, which is not a
        // signal anything watches. The gauge is what makes the pause outlive the line it was logged on: it
        // stays up until somebody resumes the pipeline, which is exactly the condition worth alerting on.
        Gauge.builder("runner.pipelines.paused", pauseRegistry, PipelinePauseRegistry::pausedCount).register(registry);
        // Split out because the total cannot carry an alert: an operator pause is a legitimate steady state,
        // so a rule written against the total is either permanently lit or permanently ignored — and it was
        // the total that existed while a pipeline sat auto-paused for fourteen hours with every surface
        // reading healthy. A breaker pause has no legitimate steady state, so "one of these has persisted"
        // is exactly the alert condition.
        Gauge.builder("runner.pipelines.paused.auto", pauseRegistry, PipelinePauseRegistry::autoPausedCount)
                .register(registry);
        Gauge.builder("runner.dlq.size", this, m -> m.dlqSize).register(registry);
        // Per task: the group and Class-1 lanes turned one work-item type into four, so a single summed depth adds unrelated
        // things and cannot answer which lane is backed up — the only question depth is asked for.
        for (String taskName : QUEUE_TASKS) {
            Gauge.builder("runner.queue.depth", this, m -> m.queueDepths.getOrDefault(taskName, 0L))
                    .tag("task", taskName)
                    .register(registry);
        }
        Gauge.builder("runner.pipelines.unsupported", this, m -> m.unsupportedPipelines).register(registry);
        // Registered at startup, and untagged. At startup because a version skew's only other symptoms are
        // absences — the sync is not failing, the DLQ is empty, no tick-failure counter moves and scan lag
        // falls to zero rather than rising — so the series has to exist before the first skew for an alert to
        // be written against it. Untagged because the condition is a property of the pair of deployments,
        // which no pipeline owns.
        Gauge.builder("runner.adas.contract.compatible", compatibilityState,
                        state -> state.isCompatible() ? 1 : 0)
                .register(registry);
        Gauge.builder("runner.group.ceiling.blocked", this, m -> m.groupCeilingBlocked).register(registry);

        this.dialLatency = Timer.builder("runner.dial.latency").register(registry);
        this.dialSuccess = Counter.builder("runner.dial.calls").tag("outcome", "success").register(registry);
        this.dialError = Counter.builder("runner.dial.calls").tag("outcome", "error").register(registry);
        this.validationFailures = Counter.builder("runner.validation.failures").register(registry);
        this.rowsUpserted = Counter.builder("runner.enrichment.rows.upserted").register(registry);
        this.class1TickFailures = Counter.builder("runner.class1.tick.failures").register(registry);
        // Pre-registered per lane, like queue depth: a stall is rare, and a series that springs into existence
        // only once it has already happened cannot be alerted on before then.
        for (CursorLane lane : CursorLane.values()) {
            cursorStalls.put(lane, Counter.builder("runner.cursor.stalls").tag("lane", lane.tag()).register(registry));
        }
        // Pre-registered per lane, for the reason the stall counters are: this fires only in the deploy
        // window that follows the evaluator fold, and an alert cannot be written against a series that
        // springs into existence at the moment it is already too late. Untagged by pipeline — the condition
        // is a property of the roll-out (a queue that outlived the old image), not of any one declaration.
        for (PreFoldWorkItem.Lane lane : PreFoldWorkItem.Lane.values()) {
            preFoldWorkItems.put(lane, Counter.builder("runner.workitem.prefold")
                    .tag("lane", lane.tag()).register(registry));
        }
        // A 429 is also counted as an error above: it *is* a failed call, and removing it from the error total
        // would make the error rate under-report. Two dimensions, not two populations.
        this.dialRateLimited = Counter.builder("runner.dial.rate.limited").register(registry);
        this.groupsEvaluated = Counter.builder("runner.group.evaluated").register(registry);
        this.groupsDeadLettered = Counter.builder("runner.group.dead.lettered").register(registry);
        this.supersededGroupEvaluations =
                Counter.builder("runner.group.evaluation.superseded").register(registry);
        this.groupsEnqueued = Counter.builder("runner.group.sweep.enqueued").register(registry);
        this.groupsSweepSuppressed = Counter.builder("runner.group.sweep.suppressed").register(registry);
        // Its own series, never folded into "suppressed". Suppressed is the sweep working — a group whose
        // own item is already queued — and is the number to drive to zero. Deferred is the queue having no
        // room, which is ordinary while a backlog drains and a symptom when it persists. Summed, neither
        // question can be asked.
        this.groupsSweepDeferred = Counter.builder("runner.group.sweep.deferred").register(registry);
        this.groupInputBudgetCuts = Counter.builder("runner.group.input.budget.cut").register(registry);
        this.emptyFeeds = Counter.builder("runner.sync.empty.feeds").register(registry);
        this.pipelineStateResets = Counter.builder("runner.pipeline.state.resets").register(registry);
        this.groupSweepDuration = Timer.builder("runner.group.sweep.duration").register(registry);

        Gauge.builder("runner.group.active", this, m -> m.activeGroups).register(registry);
        Gauge.builder("runner.group.dirty", this, m -> m.dirtyGroups).register(registry);
    }

    public Timer.Sample startDialTimer() {
        return Timer.start(registry);
    }

    public void recordDialSuccess(Timer.Sample sample) {
        sample.stop(dialLatency);
        dialSuccess.increment();
    }

    public void recordDialError(Timer.Sample sample) {
        sample.stop(dialLatency);
        dialError.increment();
    }

    /**
     * Records a rate-limited (429) DIAL call. Also counted as an error by the caller: rate-limit pressure needs
     * telling apart from model failure, but a 429 is still a failed call.
     */
    public void recordDialRateLimited() {
        dialRateLimited.increment();
    }

    /** Records a group evaluated to completion. */
    public void recordGroupEvaluated() {
        groupsEvaluated.increment();
    }

    /** Records a group dead-lettered. */
    public void recordGroupDeadLettered() {
        groupsDeadLettered.increment();
    }

    /**
     * Records an evaluation that was charged but whose stamp was refused, because a newer evaluation of the
     * same group had already stamped it. Every increment is a model call spent on a label that was superseded
     * before it landed — the symptom of a group whose evaluations take longer than the sweep interval, and
     * otherwise invisible: the charge is silent and the stamp simply matches nothing.
     */
    public void recordSupersededGroupEvaluation() {
        supersededGroupEvaluations.increment();
    }

    /**
     * Records groups a sweep pass enqueued, groups it held back because work for that version was already in
     * flight, and how long the pass took.
     *
     * <p>The suppressed count is what a re-evaluation would have cost: before it existed, every one of those
     * groups was evaluated a second time and the result discarded by the write's deterministic id — a paid
     * model call for no label. It rises with the write backlog, so it is highest during a backfill, and it is
     * the number that says the compute-to-write window is being covered.
     */
    public void recordGroupSweep(int enqueued, int suppressed, int deferred, Duration duration) {
        groupsEnqueued.increment(enqueued);
        groupsSweepSuppressed.increment(suppressed);
        groupsSweepDeferred.increment(deferred);
        groupSweepDuration.record(duration);
    }

    /**
     * Records a group input whose sole remaining member had its own content cut to fit the token budget.
     *
     * <p>Distinct from the row's {@code truncated} provenance, which a pipeline's own member-selection policy
     * sets on every narrowed group and which is therefore not a signal about the budget at all. This counts
     * only the case where content the model would otherwise have seen was removed — the measurement any
     * change to {@code group.input-token-budget} has to be argued from.
     */
    public void recordInputBudgetCut() {
        groupInputBudgetCuts.increment();
    }

    /**
     * Records a successful poll that returned an empty envelope — <em>including</em> the first, unconfirmed
     * one that changes nothing else. That one is the whole point: it is the only trace of a feed that
     * answered "no pipelines" and was not acted on, and a run of it is what a confirmed reconcile-to-empty
     * looks like one poll ahead of time.
     */
    public void incrementEmptyFeeds() {
        emptyFeeds.increment();
    }

    /**
     * Records a pipeline whose name was recreated and whose runner state was therefore reset. Untagged, like the
     * empty-feed counter: it describes the registry rather than one pipeline, the reset's WARN line names the
     * pipeline, and a per-pipeline series would be evicted with the very pipeline it counts.
     */
    public void incrementPipelineStateResets() {
        pipelineStateResets.increment();
    }

    /**
     * Records rows a page deliberately produced no work for, by reason and pipeline. Zero is not recorded:
     * a lane that skips nothing should leave the series absent rather than flat at zero.
     */
    public void recordSkippedRows(String pipelineName, SkipReason reason, int rows) {
        if (rows <= 0) {
            return;
        }
        skippedRows.computeIfAbsent(pipelineName, name -> new ConcurrentHashMap<>())
                .computeIfAbsent(reason, r -> Counter.builder("runner.scan.rows.skipped")
                        .tag("pipeline", pipelineName)
                        .tag("reason", r.tag())
                        .register(registry))
                .increment(rows);
    }

    /**
     * Records a page whose cursor advance was refused, by reason and pipeline. Recorded for a refused
     * <em>backfill</em> page as well as a live one: the tag is the pipeline, and a backfill's refusals
     * belong to it.
     */
    public void recordRefusedPage(String pipelineName, RefusalReason reason) {
        refusedPages.computeIfAbsent(pipelineName, name -> new ConcurrentHashMap<>())
                .computeIfAbsent(reason, r -> Counter.builder("runner.scan.pages.refused")
                        .tag("pipeline", pipelineName)
                        .tag("reason", r.tag())
                        .register(registry))
                .increment();
    }

    /** Records a pipeline's current-day spend, so an approaching budget is visible before it pauses the pipeline. */
    public void recordSpend(String pipelineName, long calls) {
        spendByPipeline.put(pipelineName, calls);
        spendGauges.computeIfAbsent(pipelineName, name ->
                Gauge.builder("runner.spend.calls.today", spendByPipeline, map -> map.getOrDefault(name, 0L))
                        .tag("pipeline", name)
                        .register(registry));
    }

    public void incrementValidationFailures() {
        validationFailures.increment();
    }

    public void addRowsUpserted(int rows) {
        rowsUpserted.increment(rows);
    }

    /**
     * Records rows a Class-1 materialize window wrote, per pipeline. This is the Class-1 lane's only throughput
     * signal: it enqueues nothing and dead-letters nothing, so there is no queue depth or DLQ size to read —
     * and a single summed total cannot say <em>which</em> pipeline stopped writing, which is the only question the
     * signal is asked. Pipeline names are bounded and evicted with the pipeline, so the tag is affordable; a sum over the
     * series recovers the lane total.
     */
    public void addClass1RowsWritten(String pipelineName, long rows) {
        class1RowsByPipeline.computeIfAbsent(pipelineName, name ->
                        Counter.builder("runner.class1.rows.written").tag("pipeline", name).register(registry))
                .increment(rows);
    }

    /**
     * Records a failed backfill batch, per pipeline. The batch retries indefinitely by design, so a batch that can
     * never succeed re-issues while its run reads {@code running}; this is the counter that moves while it does.
     *
     * <p>Tagged by pipeline and <em>not</em> by run: a run id is unbounded cardinality, and the run that failed is
     * named on its own row, which is where an operator reads the cause. This series answers the aggregate
     * question — how often backfill batches fail — which is what any auto-pause threshold has to be chosen from.
     * A held batch is never recorded here.
     */
    public void incrementBackfillBatchFailures(String pipelineName) {
        backfillBatchFailuresByPipeline.computeIfAbsent(pipelineName, name ->
                        Counter.builder("runner.backfill.batch.failures").tag("pipeline", name).register(registry))
                .increment();
    }

    /** Records a failed Class-1 tick — the lane has no DLQ, so this is how a stuck pipeline surfaces. */
    public void incrementClass1TickFailures() {
        class1TickFailures.increment();
    }

    /**
     * Records a drain halted because the cursor did not advance while ADAS still reported backlog.
     *
     * <p>The halt itself is correct — re-reading one window for the rest of the budget would be worse — but
     * nothing throws, so no tick failure is counted and the pipeline simply stops making progress. Without this
     * counter the only trace is a log line, and on the Class-1 lane there is no queue depth or DLQ size to
     * notice it in either.
     */
    public void incrementCursorStall(CursorLane lane) {
        cursorStalls.get(lane).increment();
    }

    /**
     * Records a work item enqueued before ADAS's evaluator fold — one this build tolerates rather than
     * drops (see {@link PreFoldWorkItem}).
     *
     * <p>Not a failure counter. It says how much work crossed the cut-over, and the lane tag says what
     * became of it: {@code chunk} and {@code upsert} were normalised onto the live generation and run,
     * {@code group} was discarded because the readiness sweep re-derives it. Expected to rise once, in
     * the deploy window, and then stay flat forever — no other surface records the event, because
     * nothing is dead-lettered for it and the DLQ size moves for every real failure.
     *
     * <p>The number worth reading beside it is the dead-letter rate at {@code input_map} on the chunk
     * lane over the same window: an adopted chunk whose old inputs cannot bind the current template fails
     * there, before any DIAL call, and that is the one cost the tolerance can carry.
     */
    public void recordPreFoldWorkItem(PreFoldWorkItem.Lane lane) {
        preFoldWorkItems.get(lane).increment();
    }

    /**
     * Records a pipeline's scan lag (seconds), registering a per-pipeline gauge on first sight.
     *
     * <p>A null is "unknown" and deliberately leaves the last recorded value alone. It is <em>not</em> how a
     * caught-up lane reports itself: a lane with nothing left to read passes {@link LagSignal#CAUGHT_UP}, which
     * is a real value and decays the gauge. Treating an empty page's absent {@code lag_seconds} as null here is
     * what used to latch the gauge at the last backlog's lag forever.
     */
    public void recordLag(String pipelineName, Long lagSeconds) {
        if (lagSeconds == null) {
            return;
        }
        lagByPipeline.put(pipelineName, lagSeconds);
        lagGauges.computeIfAbsent(pipelineName, name ->
                Gauge.builder("runner.scan.lag.seconds", lagByPipeline, map -> map.getOrDefault(name, 0L))
                        .tag("pipeline", name)
                        .register(registry));
    }

    /** Evicts a pipeline's per-pipeline meters/state when it leaves the synced set (bounds metric cardinality). */
    public void evictPipeline(String pipelineName) {
        Gauge gauge = lagGauges.remove(pipelineName);
        if (gauge != null) {
            registry.remove(gauge);
        }
        lagByPipeline.remove(pipelineName);
        Gauge spend = spendGauges.remove(pipelineName);
        if (spend != null) {
            registry.remove(spend);
        }
        spendByPipeline.remove(pipelineName);
        Counter class1Rows = class1RowsByPipeline.remove(pipelineName);
        if (class1Rows != null) {
            registry.remove(class1Rows);
        }
        Counter backfillFailures = backfillBatchFailuresByPipeline.remove(pipelineName);
        if (backfillFailures != null) {
            registry.remove(backfillFailures);
        }
        // Both are tagged by pipeline AND reason, so a pipeline can hold several series in each map; every
        // one of them goes, or the cardinality this eviction bounds would grow by reason as well as by name.
        removeAll(skippedRows.remove(pipelineName));
        removeAll(refusedPages.remove(pipelineName));
    }

    private void removeAll(Map<?, Counter> counters) {
        if (counters != null) {
            counters.values().forEach(registry::remove);
        }
    }

    /**
     * Refreshes every gauge whose value needs a query over a growing table, off the scrape path (every 30s).
     * Running these during a scrape would make scrape latency a function of table size, which is not where an
     * operator should discover that.
     */
    @Scheduled(fixedDelay = 30_000L)
    void refreshQueriedGauges() {
        dlqSize = dlqItemRepository.count();
        activeGroups = groupStateRepository.countActive();
        dirtyGroups = groupStateRepository.countDirty();
        for (String taskName : QUEUE_TASKS) {
            queueDepths.put(taskName, inflightCounter.countByTask(taskName));
        }
    }
}
