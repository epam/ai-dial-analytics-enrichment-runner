package com.epam.aidial.enrichment.sweep;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.configuration.DbSchedulerProperties;
import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.RunnerTracer;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.GroupStateWriter;
import com.epam.aidial.enrichment.scan.BackpressureGate;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.scan.InflightCounter;
import com.epam.aidial.enrichment.spend.SpendPauseGate;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.group.GroupFailureHandler;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweep's second bound: how <em>deep</em> a group pipeline's queue may get, as opposed to how fast one
 * pass may fill it.
 *
 * <p>The case this covers is a per-pass batch far larger than the worker pool. Nothing is broken by that on
 * its own — the pool drains at the rate the pool drains — but it puts hundreds of items of work in
 * {@code scheduled_tasks}, where a restart has to hold them and where each pins a {@code group_version}
 * snapshot stale by the time it runs, rather than in {@code group_state}, where waiting is one durable row
 * that costs nothing and is re-selected for free.
 *
 * <p>A unit test rather than an integration one: the behaviour is arithmetic over the in-flight set, and a
 * {@code @SpringBootTest} with its own property set would cost a whole additional application context for
 * two numbers.
 */
class GroupReadinessSweepDepthTest {

    private static final String PIPELINE_NAME = "r-depth";
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final int THREADS = 5;
    private static final int CEILING = 3;
    /**
     * The generation the fixture builds, and therefore the version component of every instance id asserted
     * below — passed to the fixture explicitly so the two cannot drift apart.
     */
    private static final long GENERATION = 3;

    private final PipelineCache pipelineCache = mock(PipelineCache.class);
    private final GroupStateWriter groupStateWriter = mock(GroupStateWriter.class);
    private final Scheduler scheduler = mock(Scheduler.class);
    private final InflightCounter inflightCounter = mock(InflightCounter.class);
    private final BackpressureGate backpressureGate = mock(BackpressureGate.class);
    private final SpendPauseGate spendPauseGate = mock(SpendPauseGate.class);
    private final RunnerMetrics runnerMetrics = mock(RunnerMetrics.class);

    @SuppressWarnings("unchecked")
    private final GroupChunkTask groupChunkTask =
            new GroupChunkTask(mock(ObjectProvider.class), mock(GroupFailureHandler.class));

    private final GroupReadinessSweep sweep = new GroupReadinessSweep(
            pipelineCache, groupStateWriter, groupChunkTask, scheduler,
            new GroupProperties(500, CEILING, Duration.ofDays(7), Duration.ofMinutes(10), 200, 60_000, Duration.ofMinutes(1)),
            new DbSchedulerProperties(Duration.ofSeconds(45), Duration.ofSeconds(10), THREADS, false),
            inflightCounter, backpressureGate, new PipelinePauseRegistry(Clock.fixed(NOW, ZoneOffset.UTC)),
            spendPauseGate, runnerMetrics, new RunnerTracer(OpenTelemetry.noop()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    /** A sanity check on the derivation, so the shipped default moves with {@code DB_SCHEDULER_THREADS}. */
    @Test
    void ceilingIsDerivedFromTheThreadCountWhenUnset() {
        GroupProperties unset = new GroupProperties(500, 0, Duration.ofDays(7), Duration.ofMinutes(10), 200, 0, Duration.ofMinutes(1));

        assertThat(unset.resolveInflightCeiling(THREADS)).isEqualTo(50);
        assertThat(unset.resolveInflightCeiling(1)).isEqualTo(10);
    }

    @Test
    void queueIsNotFilledPastTheDepthCeiling() {
        givenReadyGroups(10);
        givenInFlight();

        sweep.sweep();

        assertThat(enqueuedInstanceIds()).hasSize(CEILING);
    }

    @Test
    void deferredGroupsAreCountedSeparatelyFromSuppressedOnes() {
        givenReadyGroups(10);
        givenInFlight();

        sweep.sweep();

        // Seven of the ten had nowhere to go, and none of them was suppressed: none had work in flight.
        // Summing the two would make the pass's outcome unreadable — suppressed is the number an operator
        // drives to zero, deferred is ordinary while a backlog drains.
        verify(runnerMetrics).recordGroupSweep(eq(CEILING), eq(0), eq(7), any(Duration.class));
    }

    @Test
    void pendingWriteBackOccupiesCeilingSpace() {
        // A pipeline whose writes are backed up must not have fresh compute stacked on top of them: the
        // depth that matters is all of its pending work, not one task name's share of it.
        givenReadyGroups(10);
        givenInFlight(DeterministicId.upsertInstanceId(instanceId("chat-8")),
                DeterministicId.upsertInstanceId(instanceId("chat-9")));

        sweep.sweep();

        assertThat(enqueuedInstanceIds()).hasSize(1);
    }

    @Test
    void deferredGroupsAreEnqueuedOnceCapacityFrees() {
        // They were deferred, not dropped: still dirty, still at the head of the oldest-activity ordering,
        // and taken by the pass that finds room for them.
        givenReadyGroups(10);
        givenInFlight(instanceId("chat-0"), instanceId("chat-1"), instanceId("chat-2"));

        sweep.sweep();
        assertThat(enqueuedInstanceIds()).isEmpty();

        givenInFlight();
        sweep.sweep();

        assertThat(enqueuedInstanceIds()).containsExactly(
                instanceId("chat-0"), instanceId("chat-1"), instanceId("chat-2"));
    }

    @Test
    void nothingIsDeferredWhenTheBacklogFitsUnderTheCeiling() {
        givenReadyGroups(2);
        givenInFlight();

        sweep.sweep();

        assertThat(enqueuedInstanceIds()).hasSize(2);
        verify(runnerMetrics).recordGroupSweep(eq(2), eq(0), eq(0), any(Duration.class));
    }

    private void givenReadyGroups(int n) {
        PipelineDto pipeline = TestPipelines.group(PIPELINE_NAME, GENERATION, "chat_id", "10m");
        when(pipelineCache.getAll()).thenReturn(List.of(pipeline));
        when(scheduler.scheduleIfNotExists(any(), any(Instant.class))).thenReturn(true);
        when(groupStateWriter.findDirtyAndReady(anyString(), any(), any(LocalDate.class), any(), anyInt()))
                .thenReturn(IntStream.range(0, n)
                        .mapToObj(i -> new GroupStateWriter.DirtyGroup("chat-" + i, 100L))
                        .toList());
    }

    private void givenInFlight(String... instanceIds) {
        when(inflightCounter.instanceIdsByTasksAndPrefix(any(), anyString())).thenReturn(Set.of(instanceIds));
    }

    private static String instanceId(String groupKey) {
        return DeterministicId.groupInstanceId(PIPELINE_NAME, GENERATION, groupKey, 100L);
    }

    @SuppressWarnings("unchecked")
    private List<String> enqueuedInstanceIds() {
        ArgumentCaptor<TaskInstance<?>> captor = ArgumentCaptor.forClass(TaskInstance.class);
        verify(scheduler, atLeast(0))
                .scheduleIfNotExists(captor.capture(), any(Instant.class));
        List<String> ids = new ArrayList<>();
        captor.getAllValues().forEach(instance -> ids.add(instance.getId()));
        return ids;
    }
}
