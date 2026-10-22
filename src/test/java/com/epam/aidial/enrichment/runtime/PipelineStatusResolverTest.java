package com.epam.aidial.enrichment.runtime;

import com.epam.aidial.enrichment.adas.AdasCompatibilityState;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.epam.aidial.enrichment.pause.PauseOrigin;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry.Pause;
import com.epam.aidial.enrichment.runtime.PipelineStatusResolver.State;
import com.epam.aidial.enrichment.runtime.PipelineStatusResolver.Status;
import com.epam.aidial.enrichment.scan.BackpressureGate;
import com.epam.aidial.enrichment.scan.ScheduleFactory;
import com.epam.aidial.enrichment.spend.SpendPauseGate;
import com.epam.aidial.enrichment.support.TestPipelines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PipelineStatusResolverTest {

    private static final Instant STARTED = Instant.parse("2026-10-05T12:00:00Z");
    private static final Duration CADENCE = Duration.ofMinutes(1);
    private static final Duration DEFAULT_IDLE = Duration.ofMinutes(10);
    private static final Duration SWEEP_INTERVAL = Duration.ofSeconds(30);
    /** Past the startup grace of three cadences. */
    private static final Instant NOW = STARTED.plus(Duration.ofHours(1));

    private final PipelinePauseRegistry pauseRegistry = mock(PipelinePauseRegistry.class);
    private final AdasCompatibilityState compatibilityState = mock(AdasCompatibilityState.class);
    private final SpendPauseGate spendPauseGate = mock(SpendPauseGate.class);
    private final BackpressureGate backpressureGate = mock(BackpressureGate.class);
    private final ScheduleFactory scheduleFactory = mock(ScheduleFactory.class);

    private final PipelineStatusResolver resolver = resolverStartedAt(STARTED);

    private final PipelineDto pipeline = TestPipelines.schedule("r1", 1, TestPipelines.EVERY_MINUTE);

    @BeforeEach
    void setUp() {
        when(pauseRegistry.current(anyString())).thenReturn(Optional.empty());
        when(compatibilityState.isCompatible()).thenReturn(true);
        when(scheduleFactory.cadence(any(), any())).thenReturn(CADENCE);
    }

    @Test
    void pausedWinsAndReadsNoGate() {
        Pause pause = new Pause(PauseOrigin.OPERATOR, "maintenance", STARTED, null);
        when(pauseRegistry.current("r1")).thenReturn(Optional.of(pause));
        when(compatibilityState.isCompatible()).thenReturn(false);

        Status status = resolver.resolve(pipeline, null, 600L, NOW);

        assertThat(status).isEqualTo(new Status(State.PAUSED, false, null, pause));
        verifyNoInteractions(compatibilityState, spendPauseGate, backpressureGate, scheduleFactory);
    }

    @Test
    void heldOutranksOverBudgetAndBackpressure() {
        when(compatibilityState.isCompatible()).thenReturn(false);
        when(spendPauseGate.isOverBudget(pipeline)).thenReturn(true);
        when(backpressureGate.isBackpressured("r1")).thenReturn(true);

        assertThat(resolver.resolve(pipeline, NOW, 0L, NOW).state()).isEqualTo(State.HELD);
    }

    @Test
    void overBudgetOutranksBackpressure() {
        when(spendPauseGate.isOverBudget(pipeline)).thenReturn(true);
        when(backpressureGate.isBackpressured("r1")).thenReturn(true);

        assertThat(resolver.resolve(pipeline, NOW, 0L, NOW).state()).isEqualTo(State.OVER_BUDGET);
    }

    @Test
    void backpressuredOutranksActive() {
        when(backpressureGate.isBackpressured("r1")).thenReturn(true);

        assertThat(resolver.resolve(pipeline, NOW, 0L, NOW).state()).isEqualTo(State.BACKPRESSURED);
    }

    @Test
    void noGateTrippedIsActive() {
        Status status = resolver.resolve(pipeline, NOW, 0L, NOW);

        assertThat(status.state()).isEqualTo(State.ACTIVE);
        assertThat(status.pause()).isNull();
    }

    /** A Class-1 pipeline queues no work items, so the backpressure gate is never asked about it. */
    @Test
    void class1IsNeverBackpressured() {
        PipelineDto class1 = TestPipelines.class1("r-sql", 1, TestPipelines.EVERY_MINUTE);
        when(backpressureGate.isBackpressured(anyString())).thenReturn(true);

        assertThat(resolver.resolve(class1, NOW, 0L, NOW).state()).isEqualTo(State.ACTIVE);
        verify(backpressureGate, never()).isBackpressured(anyString());
    }

    @Test
    void stallAfterIsThreeCadences() {
        assertThat(resolver.resolve(pipeline, NOW, 0L, NOW).stallAfter()).isEqualTo(CADENCE.multipliedBy(3));
    }

    /** A group pipeline writes only after its idle window and a sweep, so three mark-scan cadences would cry stall early. */
    @Test
    void groupStallAfterCoversTheIdleWindowPlusSweep() {
        PipelineDto group = TestPipelines.group("g1", 1, "session_id", "30m");

        Status status = resolver.resolve(group, NOW.minus(Duration.ofMinutes(20)), 30L, NOW);

        assertThat(status.stallAfter()).isEqualTo(Duration.ofMinutes(30).plus(SWEEP_INTERVAL));
        assertThat(status.stalled()).isFalse();
    }

    @Test
    void groupWithoutDeclaredIdleUsesTheDefaultIdle() {
        PipelineDto group = TestPipelines.group("g1", 1, "session_id", null);

        assertThat(resolver.resolve(group, NOW, 0L, NOW).stallAfter()).isEqualTo(DEFAULT_IDLE.plus(SWEEP_INTERVAL));
    }

    /** A short idle window never shortens the threshold below three cadences. */
    @Test
    void groupStallAfterIsNeverBelowThreeCadences() {
        PipelineDto group = TestPipelines.group("g1", 1, "session_id", "30s");

        assertThat(resolver.resolve(group, NOW, 0L, NOW).stallAfter()).isEqualTo(CADENCE.multipliedBy(3));
    }

    @Test
    void lagWithNoWriteForMoreThanThreeCadencesIsStalled() {
        Instant lastWrite = NOW.minus(CADENCE.multipliedBy(3)).minusSeconds(1);

        assertThat(resolver.resolve(pipeline, lastWrite, 30L, NOW).stalled()).isTrue();
    }

    @Test
    void lagWithNoWriteSinceStartupIsStalledOnceTheGraceEnds() {
        assertThat(resolver.resolve(pipeline, null, 30L, NOW).stalled()).isTrue();
    }

    @Test
    void recentWriteIsNotStalled() {
        Instant lastWrite = NOW.minus(CADENCE.multipliedBy(3)).plusSeconds(1);

        assertThat(resolver.resolve(pipeline, lastWrite, 30L, NOW).stalled()).isFalse();
    }

    /** Write activity lives in memory, so within three cadences of startup nothing can be called stalled. */
    @Test
    void notStalledDuringTheStartupGrace() {
        PipelineStatusResolver fresh = resolverStartedAt(NOW);

        Status status = fresh.resolve(pipeline, null, 30L, NOW.plus(CADENCE.multipliedBy(3)).minusSeconds(1));

        assertThat(status.stalled()).isFalse();
        assertThat(status.stallAfter()).isNull();
    }

    @Test
    void notStalledWhenTheCadenceIsUnknown() {
        when(scheduleFactory.cadence(any(), any())).thenThrow(new IllegalArgumentException("no trigger.cron"));

        Status status = resolver.resolve(pipeline, null, 30L, NOW);

        assertThat(status.stalled()).isFalse();
        assertThat(status.stallAfter()).isNull();
        assertThat(status.state()).isEqualTo(State.ACTIVE);
    }

    @Test
    void notStalledWithoutLag() {
        assertThat(resolver.resolve(pipeline, null, 0L, NOW).stalled()).isFalse();
        assertThat(resolver.resolve(pipeline, null, null, NOW).stalled()).isFalse();
    }

    private PipelineStatusResolver resolverStartedAt(Instant startedAt) {
        return new PipelineStatusResolver(Clock.fixed(startedAt, ZoneOffset.UTC), pauseRegistry, compatibilityState,
                spendPauseGate, backpressureGate, scheduleFactory,
                new GroupProperties(500, 0, Duration.ofDays(7), DEFAULT_IDLE, 200, 60_000, CADENCE), SWEEP_INTERVAL);
    }
}
