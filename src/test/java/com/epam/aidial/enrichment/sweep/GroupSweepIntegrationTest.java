package com.epam.aidial.enrichment.sweep;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ReadyWhenDto;
import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.support.SettableClock;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.upsert.UpsertRowsTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A1 exit-criteria test for the readiness half, driven by a controllable clock: an idle group is enqueued
 * exactly once per version, a still-active group is not, a stale computed group re-fires regardless of
 * activity, a signalled group fires without waiting out its idle window, a clean group never fires, a pass
 * stops at its batch size, and eviction spares dirty groups.
 */
@SpringBootTest
class GroupSweepIntegrationTest {

    private static final String PIPELINE_NAME = "r-sweep";
    private static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-07-01");
    /**
     * The generation every fixture in this class builds, and therefore the version component of every
     * instance id asserted below. It is passed to the fixtures explicitly rather than left to the builder's
     * default, so a change to that default cannot silently make these assertions pin ids nothing enqueues.
     */
    private static final long GENERATION = 3;

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        SettableClock testClock() {
            return new SettableClock(NOW);
        }
    }

    @Autowired
    private GroupReadinessSweep sweep;

    @Autowired
    private GroupStateEvictor evictor;

    @Autowired
    private PipelineCache pipelineCache;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private SettableClock testClock() {
        return (SettableClock) clock;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
        // The sweep is driven manually; keep its schedule from firing underneath the assertions.
        registry.add("group.sweep-initial-delay", () -> "3600s");
        registry.add("group.sweep-interval", () -> "3600s");
        registry.add("group.eviction-cron", () -> "0 0 5 1 1 *");
        registry.add("group.sweep-batch", () -> "2");
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, group_state");
        testClock().setTo(NOW);
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, idlePipeline()));
    }

    @Test
    void idleGroupIsEnqueuedExactlyOncePerVersion() {
        // Quiet for 30 minutes, never computed -> dirty and idle-ready.
        insertGroup("chat-a", 100L, NOW.minus(Duration.ofMinutes(30)), null, null);

        sweep.sweep();
        sweep.sweep();

        assertThat(groupTaskCount()).isEqualTo(1);
    }

    @Test
    void stillActiveGroupIsNotEnqueued() {
        insertGroup("chat-a", 100L, NOW.minus(Duration.ofMinutes(1)), null, null);

        sweep.sweep();

        assertThat(groupTaskCount()).isZero();
        // Crossing the idle threshold is all it takes.
        testClock().advance(Duration.ofMinutes(15));
        sweep.sweep();
        assertThat(groupTaskCount()).isEqualTo(1);
    }

    @Test
    void cleanGroupIsNeverEnqueued() {
        insertGroup("chat-a", 100L, NOW.minus(Duration.ofHours(5)), 100L, NOW.minus(Duration.ofHours(4)));

        sweep.sweep();

        assertThat(groupTaskCount()).isZero();
    }

    @Test
    void changedGroupReFiresAtItsNewVersion() {
        insertGroup("chat-a", 100L, NOW.minus(Duration.ofMinutes(30)), null, null);
        sweep.sweep();
        assertThat(groupTaskCount()).isEqualTo(1);

        // A late member raises the version; the group is dirty again and gets a distinct work item.
        jdbcTemplate.update("UPDATE group_state SET group_version = 200 WHERE pipeline_name = ? AND group_key = ?",
                PIPELINE_NAME, "chat-a");
        sweep.sweep();

        assertThat(groupTaskCount()).isEqualTo(2);
    }

    @Test
    void staleComputedGroupReFiresRegardlessOfActivity() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, readyWhen("1h")));
        // Dirty, but far too recently active for a 48h idle window; only staleness can make it ready.
        insertGroup("chat-a", 200L, NOW.minus(Duration.ofMinutes(1)), 100L, NOW.minus(Duration.ofHours(3)));

        sweep.sweep();

        assertThat(groupTaskCount()).isEqualTo(1);
    }

    @Test
    void neverComputedGroupCannotBecomeReadyThroughStalenessAlone() {
        // Staleness compares against a previous computation; without one, only the idle window applies —
        // otherwise a group would be evaluated the instant its first member landed.
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, readyWhen("1h")));
        insertGroup("chat-a", 100L, NOW.minus(Duration.ofMinutes(1)), null, null);

        sweep.sweep();

        assertThat(groupTaskCount()).isZero();
    }

    @Test
    void signalledGroupIsEnqueuedBeforeItsIdleWindow() {
        // The point of a signal: a group declared complete does not wait out the idle window. Active one
        // minute ago against a 48h idle window, so nothing else here could make it ready.
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, withSignal()));
        insertGroup("chat-a", 100L, NOW.minus(Duration.ofMinutes(1)), null, null);
        markSignalled();

        sweep.sweep();

        assertThat(groupTaskCount()).isEqualTo(1);
    }

    @Test
    void signalIsIgnoredForPipelineThatDoesNotDeclareOne() {
        // The recorded verdict is only a readiness branch for a pipeline whose ready_when declares a signal;
        // otherwise the group waits for its idle window like any other.
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, readyWhen(null)));
        insertGroup("chat-a", 100L, NOW.minus(Duration.ofMinutes(1)), null, null);
        markSignalled();

        sweep.sweep();

        assertThat(groupTaskCount()).isZero();
    }

    @Test
    void signalledGroupAtItsCeilingStaysBlocked() {
        // A signal says "evaluate now", not "evaluate outside the budget".
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, withSignalAndCeiling()));
        insertGroupEvaluatedToday("chat-a", 200L, NOW.minus(Duration.ofMinutes(1)), 100L, NOW.minus(Duration.ofHours(3)), 1);
        markSignalled();

        sweep.sweep();

        assertThat(groupTaskCount()).isZero();
    }

    @Test
    void signalledButCleanGroupIsNotEnqueued() {
        // Readiness never overrides dirtiness: a group whose label is already at its current version has
        // nothing to re-evaluate, however emphatically it signalled.
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, withSignal()));
        insertGroup("chat-a", 100L, NOW.minus(Duration.ofMinutes(1)), 100L, NOW.minus(Duration.ofMinutes(1)));
        markSignalled();

        sweep.sweep();

        assertThat(groupTaskCount()).isZero();
    }

    @Test
    void passStopsAtTheBatchSize() {
        for (int i = 0; i < 5; i++) {
            insertGroup("chat-" + i, 100L + i, NOW.minus(Duration.ofMinutes(30 + i)), null, null);
        }

        sweep.sweep();

        // group.sweep-batch is 2 for this test.
        assertThat(groupTaskCount()).isEqualTo(2);
    }

    @Test
    void evictionRemovesQuietCleanGroupsAndSparesDirtyOnes() {
        // Retention is max(idle, staleness) + margin; the default margin is 7d.
        Instant longAgo = NOW.minus(Duration.ofDays(30));
        insertGroup("clean-old", 100L, longAgo, 100L, longAgo);
        insertGroup("dirty-old", 200L, longAgo, 100L, longAgo);
        insertGroup("clean-recent", 100L, NOW.minus(Duration.ofMinutes(30)), 100L, NOW);

        evictor.evict();

        assertThat(groupKeys()).containsExactlyInAnyOrder("dirty-old", "clean-recent");
    }

    @Test
    void groupAtItsCeilingIsNotEnqueued() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, withCeiling(3)));
        insertGroupEvaluatedToday("chat-a", 100L, NOW.minus(Duration.ofMinutes(30)), 50L, NOW.minus(Duration.ofHours(1)), 3);

        sweep.sweep();

        assertThat(groupTaskCount()).isZero();
    }

    @Test
    void groupBelowItsCeilingIsEnqueued() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, withCeiling(3)));
        insertGroupEvaluatedToday("chat-a", 100L, NOW.minus(Duration.ofMinutes(30)), 50L, NOW.minus(Duration.ofHours(1)), 2);

        sweep.sweep();

        assertThat(groupTaskCount()).isEqualTo(1);
    }

    @Test
    void pipelineWithoutCeilingIsNeverBlocked() {
        // The default for every pipeline today: no ceiling declared means unlimited.
        insertGroupEvaluatedToday("chat-a", 100L, NOW.minus(Duration.ofMinutes(30)), 50L, NOW.minus(Duration.ofHours(1)), 9999);

        sweep.sweep();

        assertThat(groupTaskCount()).isEqualTo(1);
    }

    @Test
    void staleGroupAtItsCeilingStaysBlocked() {
        // Staleness does not override the budget — that is the case the ceiling exists to stop re-firing.
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, withCeilingAndStaleness()));
        insertGroupEvaluatedToday("chat-a", 200L, NOW.minus(Duration.ofMinutes(1)), 100L, NOW.minus(Duration.ofHours(3)), 3);

        sweep.sweep();

        assertThat(groupTaskCount()).isZero();
    }

    @Test
    void dayRolloverUnblocksGroupAtItsCeiling() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, withCeiling(3)));
        insertGroupEvaluatedToday("chat-a", 100L, NOW.minus(Duration.ofMinutes(30)), 50L, NOW.minus(Duration.ofHours(1)), 3);
        sweep.sweep();
        assertThat(groupTaskCount()).isZero();

        // Cross UTC midnight: yesterday's count is not today's count, so the group is eligible again.
        testClock().advance(Duration.ofDays(1));
        sweep.sweep();

        assertThat(groupTaskCount()).isEqualTo(1);
    }

    @Test
    void blockedGroupsDoNotConsumeThePassBudget() {
        // group.sweep-batch is 2. The blocked groups have the oldest activity, so they sort first — if the
        // ceiling were applied after the LIMIT they would crowd out the eligible ones entirely.
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, withCeiling(1)));
        insertGroupEvaluatedToday("blocked-1", 100L, NOW.minus(Duration.ofHours(5)), 50L, NOW.minus(Duration.ofHours(5)), 1);
        insertGroupEvaluatedToday("blocked-2", 100L, NOW.minus(Duration.ofHours(4)), 50L, NOW.minus(Duration.ofHours(4)), 1);
        insertGroupEvaluatedToday("eligible-1", 100L, NOW.minus(Duration.ofHours(3)), 50L, NOW.minus(Duration.ofHours(3)), 0);
        insertGroupEvaluatedToday("eligible-2", 100L, NOW.minus(Duration.ofHours(2)), 50L, NOW.minus(Duration.ofHours(2)), 0);

        sweep.sweep();

        // Asserting *which* groups, not how many: without the predicate inside the query the LIMIT would
        // return the two blocked groups (they sort first) and this would still be a count of two.
        assertThat(enqueuedInstanceIds()).containsExactlyInAnyOrder(
                DeterministicId.groupInstanceId(PIPELINE_NAME, GENERATION, "eligible-1", 100L),
                DeterministicId.groupInstanceId(PIPELINE_NAME, GENERATION, "eligible-2", 100L));
    }

    @Test
    void groupWhoseWriteIsInFlightIsNotReEnqueued() {
        // The window the fix closes: compute has finished (its item is gone) but the write has not landed,
        // so the group is still dirty with nothing left for scheduleIfNotExists to collide with.
        insertGroup("chat-a", 100L, NOW.minus(Duration.ofMinutes(30)), null, null);
        insertWriteInFlight();
        double before = suppressedCount();

        sweep.sweep();

        assertThat(groupTaskCount()).isZero();
        assertThat(suppressedCount() - before).isEqualTo(1.0);
    }

    @Test
    void groupIsEnqueuedOnceItsWriteCompletesAndItIsStillDirty() {
        // Suppression must not be terminal: a write that lands without stamping (an archived or superseded
        // one) leaves the group dirty, and the next pass has to pick it up.
        insertGroup("chat-a", 100L, NOW.minus(Duration.ofMinutes(30)), null, null);
        insertWriteInFlight();
        sweep.sweep();
        assertThat(groupTaskCount()).isZero();

        jdbcTemplate.update("DELETE FROM scheduled_tasks WHERE task_name = ?", UpsertRowsTask.TASK_NAME);
        sweep.sweep();

        assertThat(groupTaskCount()).isEqualTo(1);
    }

    @Test
    void groupWhoseVersionAdvancedWhileAnOlderWriteIsPendingIsEnqueued() {
        // The regression this must not introduce. The ids are version-keyed, so a pending write for version
        // 100 says nothing about version 200 — which carries members that version's label never saw.
        insertGroup("chat-a", 200L, NOW.minus(Duration.ofMinutes(30)), null, null);
        insertWriteInFlight();
        double before = suppressedCount();

        sweep.sweep();

        assertThat(enqueuedInstanceIds()).containsExactly(
                DeterministicId.groupInstanceId(PIPELINE_NAME, GENERATION, "chat-a", 200L));
        assertThat(suppressedCount() - before).isZero();
    }

    @Test
    void groupWhoseComputeIsStillQueuedIsNotReEnqueued() {
        // The half scheduleIfNotExists already covered; asserted here so the sweep-side check is known to
        // subsume it rather than to depend on it.
        insertGroup("chat-a", 100L, NOW.minus(Duration.ofMinutes(30)), null, null);
        insertComputeInFlight();
        double before = suppressedCount();

        sweep.sweep();

        assertThat(groupTaskCount()).isEqualTo(1);
        assertThat(suppressedCount() - before).isEqualTo(1.0);
    }

    @Test
    void blockedGroupsAreReported() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, withCeiling(1)));
        insertGroupEvaluatedToday("blocked-1", 100L, NOW.minus(Duration.ofHours(5)), 50L, NOW.minus(Duration.ofHours(5)), 1);
        insertGroupEvaluatedToday("blocked-2", 100L, NOW.minus(Duration.ofHours(4)), 50L, NOW.minus(Duration.ofHours(4)), 1);

        sweep.sweep();

        assertThat(meterRegistry.get("runner.group.ceiling.blocked").gauge().value()).isEqualTo(2.0);
    }

    /** Ready after {@code 10m} idle, with a daily evaluation ceiling. */
    private static PipelineDto withCeiling(int ceiling) {
        return TestPipelines.builder(PIPELINE_NAME)
                .generation(GENERATION)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .grainKey("chat_id")
                .readyWhen(new ReadyWhenDto(null, "10m", null, ceiling))
                .build();
    }

    /** Ready after {@code 48h} idle or {@code 1h} staleness, with a daily evaluation ceiling of 3. */
    private static PipelineDto withCeilingAndStaleness() {
        return TestPipelines.builder(PIPELINE_NAME)
                .generation(GENERATION)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .grainKey("chat_id")
                .readyWhen(new ReadyWhenDto(null, "48h", "1h", 3))
                .build();
    }

    /** Ready on the {@code conversation_end} signal, or after {@code 48h} idle. */
    private static PipelineDto withSignal() {
        return TestPipelines.builder(PIPELINE_NAME)
                .generation(GENERATION)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .grainKey("chat_id")
                .readyWhen(new ReadyWhenDto("event_kind = 'conversation_end'", "48h", null, null))
                .build();
    }

    /** Ready on the {@code conversation_end} signal, or after {@code 48h} idle, with a daily evaluation ceiling of 1. */
    private static PipelineDto withSignalAndCeiling() {
        return TestPipelines.builder(PIPELINE_NAME)
                .generation(GENERATION)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .grainKey("chat_id")
                .readyWhen(new ReadyWhenDto("event_kind = 'conversation_end'", "48h", null, 1))
                .build();
    }

    /** Ready after {@code 10m} idle. */
    private static PipelineDto idlePipeline() {
        return TestPipelines.group(PIPELINE_NAME, GENERATION, "chat_id", "10m");
    }

    /** Ready after {@code 48h} idle or, when {@code maxStaleness} is set, once that stale. */
    private static PipelineDto readyWhen(String maxStaleness) {
        return TestPipelines.builder(PIPELINE_NAME)
                .generation(GENERATION)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .grainKey("chat_id")
                .readyWhen(new ReadyWhenDto(null, "48h", maxStaleness, null))
                .build();
    }

    private void insertGroupEvaluatedToday(String groupKey, long groupVersion, Instant lastActivityAt,
                                           Long computedVersion, Instant computedAt, int evaluations) {
        insertGroup(groupKey, groupVersion, lastActivityAt, computedVersion, computedAt);
        jdbcTemplate.update("UPDATE group_state SET evaluations_day = ?, evaluations = ? "
                        + "WHERE pipeline_name = ? AND group_key = ?",
                TODAY, evaluations, PIPELINE_NAME, groupKey);
    }

    private void insertGroup(String groupKey, long groupVersion, Instant lastActivityAt,
                             Long computedVersion, Instant computedAt) {
        jdbcTemplate.update("""
                        INSERT INTO group_state
                            (pipeline_name, group_key, group_version, last_activity_at, computed_version, computed_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                PIPELINE_NAME, groupKey, groupVersion, Timestamp.from(lastActivityAt), computedVersion,
                computedAt == null ? null : Timestamp.from(computedAt));
    }

    /** A queued write for the compute of {@code chat-a} at version 100, as the compute enqueues it. */
    private void insertWriteInFlight() {
        insertTask(UpsertRowsTask.TASK_NAME, DeterministicId.upsertInstanceId(
                DeterministicId.groupInstanceId(PIPELINE_NAME, GENERATION, "chat-a", 100L)));
    }

    /** A compute item still queued for {@code chat-a} at version 100. */
    private void insertComputeInFlight() {
        insertTask(GroupChunkTask.TASK_NAME,
                DeterministicId.groupInstanceId(PIPELINE_NAME, GENERATION, "chat-a", 100L));
    }

    private void insertTask(String taskName, String instanceId) {
        jdbcTemplate.update("""
                        INSERT INTO scheduled_tasks
                            (task_name, task_instance, execution_time, picked, version)
                        VALUES (?, ?, ?, false, 1)
                        """,
                taskName, instanceId, Timestamp.from(NOW));
    }

    /** What the dirty-mark does when ADAS's {@code __signal} verdict is true for one of {@code chat-a}'s rows. */
    private void markSignalled() {
        jdbcTemplate.update("UPDATE group_state SET signalled = true WHERE pipeline_name = ? AND group_key = ?",
                PIPELINE_NAME, "chat-a");
    }

    private List<String> enqueuedInstanceIds() {
        return jdbcTemplate.queryForList(
                "SELECT task_instance FROM scheduled_tasks WHERE task_name = ?", String.class,
                GroupChunkTask.TASK_NAME);
    }

    /** The counter is registry-wide and the context is shared, so tests assert on its delta. */
    private double suppressedCount() {
        return meterRegistry.get("runner.group.sweep.suppressed").counter().count();
    }

    private long groupTaskCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", Long.class,
                GroupChunkTask.TASK_NAME);
        return count == null ? 0 : count;
    }

    private List<String> groupKeys() {
        return jdbcTemplate.queryForList("SELECT group_key FROM group_state ORDER BY group_key",
                String.class);
    }
}
