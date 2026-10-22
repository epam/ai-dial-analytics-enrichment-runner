package com.epam.aidial.enrichment.runtime;

import com.epam.aidial.enrichment.adas.AdasCompatibilityState;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry.Pause;
import com.epam.aidial.enrichment.scan.BackpressureGate;
import com.epam.aidial.enrichment.scan.ScheduleFactory;
import com.epam.aidial.enrichment.spend.SpendPauseGate;
import com.epam.aidial.enrichment.sync.PipelineRouting;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The one answer to "what state is this pipeline in?", shared by the status heartbeat and the runtime view so the
 * two can never disagree about one pipeline at one instant.
 *
 * <p>Precedence: paused, then held (ADAS on the other side of a contract bump), then over its daily spend budget,
 * then backpressured, then active. A paused pipeline reads no gate at all, as the heartbeat always did. A Class-1
 * pipeline is never over budget ({@link SpendPauseGate#isOverBudget(PipelineDto)} excludes it) and never
 * backpressured, because it queues no work items for the gate to count.
 *
 * <p><b>Stalled</b> is orthogonal to the state: lag above zero and nothing written for more than {@code stallAfter} —
 * {@value #STALL_CADENCES} cadences, or for a group pipeline at least its effective idle window plus the sweep interval,
 * because a group writes nothing until it has been quiet that long. Write activity is held in process memory, so the
 * condition is never reported within {@code stallAfter} of startup, and never while paused.
 *
 * <p>The gates log their own transitions, so whichever caller observes a change first logs it — still one line per
 * change.
 */
@Component
public class PipelineStatusResolver {

    public static final int STALL_CADENCES = 3;

    private final PipelinePauseRegistry pauseRegistry;
    private final AdasCompatibilityState compatibilityState;
    private final SpendPauseGate spendPauseGate;
    private final BackpressureGate backpressureGate;
    private final ScheduleFactory scheduleFactory;
    private final GroupProperties groupProperties;
    private final Duration sweepInterval;
    private final Instant startedAt;

    public PipelineStatusResolver(Clock clock, PipelinePauseRegistry pauseRegistry, AdasCompatibilityState compatibilityState,
                                  SpendPauseGate spendPauseGate, BackpressureGate backpressureGate, ScheduleFactory scheduleFactory,
                                  GroupProperties groupProperties, @Value("${group.sweep-interval:30s}") Duration sweepInterval) {
        this.pauseRegistry = pauseRegistry;
        this.compatibilityState = compatibilityState;
        this.spendPauseGate = spendPauseGate;
        this.backpressureGate = backpressureGate;
        this.scheduleFactory = scheduleFactory;
        this.groupProperties = groupProperties;
        this.sweepInterval = sweepInterval;
        this.startedAt = clock.instant();
    }

    /** A pipeline's runtime state. Written lowercase on the wire. */
    public enum State {
        ACTIVE, PAUSED, HELD, OVER_BUDGET, BACKPRESSURED
    }

    /**
     * What {@link #resolve} decided.
     *
     * @param state      the state, by the precedence above
     * @param stalled    whether the pipeline is lagging and has written nothing for more than {@code stallAfter}
     * @param stallAfter {@value #STALL_CADENCES}× the cadence (for a group pipeline, no less than its effective idle
     *                   window plus the sweep interval), or null while the startup grace lasts or when the cadence is
     *                   unknown; null never reads as stalled
     * @param pause      the pause in force, present exactly when {@code state} is {@link State#PAUSED}
     */
    public record Status(State state, boolean stalled, Duration stallAfter, Pause pause) {
    }

    /**
     * Resolves one pipeline's status.
     *
     * @param lastWrite  when the pipeline last wrote rows, or null if it has not since startup
     * @param lagSeconds its last recorded lag, or null when unknown
     */
    public Status resolve(PipelineDto pipeline, Instant lastWrite, Long lagSeconds, Instant now) {
        Pause pause = pauseRegistry.current(pipeline.name()).orElse(null);
        if (pause != null) {
            return new Status(State.PAUSED, false, null, pause);
        }
        Duration stallAfter = stallAfter(pipeline, now);
        return new Status(state(pipeline), isStalled(lastWrite, lagSeconds, stallAfter, now), stallAfter, null);
    }

    private State state(PipelineDto pipeline) {
        if (!compatibilityState.isCompatible()) {
            return State.HELD;
        }
        if (spendPauseGate.isOverBudget(pipeline)) {
            return State.OVER_BUDGET;
        }
        // A Class-1 pipeline queues no work items, so there is nothing for backpressure to count.
        if (!PipelineRouting.isClass1(pipeline) && backpressureGate.isBackpressured(pipeline.name())) {
            return State.BACKPRESSURED;
        }
        return State.ACTIVE;
    }

    /**
     * {@value #STALL_CADENCES}× the cadence, or {@code null} while the startup grace lasts or when the cadence is
     * unknown. A null answer never reads as stalled.
     *
     * <p>A group pipeline's cadence is its mark scan's, which says nothing about when it writes: a dirty group is
     * evaluated only once it has been quiet for the effective idle window and a sweep has then run. So its threshold
     * is at least that window plus the sweep interval. {@code max_staleness} and a signal only make a group ready
     * sooner, so they cannot lengthen the wait and are not added.
     */
    private Duration stallAfter(PipelineDto pipeline, Instant now) {
        Duration stallAfter;
        try {
            Duration cadence = scheduleFactory.cadence(pipeline, now);
            if (cadence == null || cadence.isZero() || cadence.isNegative()) {
                return null;
            }
            stallAfter = cadence.multipliedBy(STALL_CADENCES);
            if (PipelineRouting.isGroup(pipeline)) {
                Duration groupWait = PipelineRouting.idle(pipeline, groupProperties.defaultIdle()).plus(sweepInterval);
                stallAfter = groupWait.compareTo(stallAfter) > 0 ? groupWait : stallAfter;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return now.isBefore(startedAt.plus(stallAfter)) ? null : stallAfter;
    }

    private static boolean isStalled(Instant lastWrite, Long lagSeconds, Duration stallAfter, Instant now) {
        if (stallAfter == null || lagSeconds == null || lagSeconds <= 0) {
            return false;
        }
        return lastWrite == null || lastWrite.plus(stallAfter).isBefore(now);
    }
}
