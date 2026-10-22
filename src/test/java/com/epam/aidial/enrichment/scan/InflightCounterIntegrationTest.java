package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.worker.WorkTasks;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.upsert.UpsertRowsTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The identity side of the in-flight query, which the readiness sweep uses to tell a group whose work is
 * already queued from one that needs enqueuing. Asserts the two properties the count side already relies on
 * — every pipeline-scoped task is covered by one prefix, and a pipeline name carrying LIKE metacharacters cannot
 * broaden the match — because reading ids and counting them must agree about what "this pipeline's work" means.
 */
@SpringBootTest
class InflightCounterIntegrationTest {

    @Autowired
    private InflightCounter inflightCounter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
        registry.add("group.sweep-initial-delay", () -> "3600s");
        registry.add("group.sweep-interval", () -> "3600s");
        registry.add("group.eviction-cron", () -> "0 0 5 1 1 *");
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE scheduled_tasks");
    }

    @Test
    void idsSpanEveryPipelineScopedTaskUnderOnePrefix() {
        insert(GroupChunkTask.TASK_NAME, "r-1#g#aaa");
        insert(UpsertRowsTask.TASK_NAME, "r-1#g#aaa#w");
        insert(GroupChunkTask.TASK_NAME, "r-2#g#bbb");

        Set<String> ids = inflightCounter.instanceIdsByTasksAndPrefix(
                WorkTasks.PIPELINE_SCOPED, DeterministicId.pipelinePrefix("r-1"));

        // A compute and its write are one pipeline's work; another pipeline's item is not.
        assertThat(ids).containsExactlyInAnyOrder("r-1#g#aaa", "r-1#g#aaa#w");
    }

    @Test
    void likeMetacharactersInThePipelineNameDoNotBroadenTheMatch() {
        // Without ESCAPE, '_' matches any character and '%' matches anything at all, so a pipeline named
        // "a_b" would claim "axb"'s work and a pipeline named "%" would claim everyone's.
        insert(GroupChunkTask.TASK_NAME, "a_b%c!d#g#aaa");
        insert(GroupChunkTask.TASK_NAME, "axbyczd#g#bbb");
        insert(GroupChunkTask.TASK_NAME, "%#g#ccc");

        assertThat(inflightCounter.instanceIdsByTasksAndPrefix(
                WorkTasks.PIPELINE_SCOPED, DeterministicId.pipelinePrefix("a_b%c!d")))
                .containsExactly("a_b%c!d#g#aaa");
        assertThat(inflightCounter.instanceIdsByTasksAndPrefix(
                WorkTasks.PIPELINE_SCOPED, DeterministicId.pipelinePrefix("%")))
                .containsExactly("%#g#ccc");
    }

    /**
     * The count side of the same property, and the one the identity change makes worth pinning. A UUID could
     * carry none of LIKE's metacharacters; a pipeline name can carry {@code _}, and {@code conversations_rollup}
     * is the shape of name this runner now sees. Depth drives backpressure, so a broadened match would throttle
     * a pipeline on another's queue — silently, and only for the pair of names that happen to collide.
     */
    @Test
    void anUnderscoreInThePipelineNameDoesNotBroadenTheDepthCount() {
        insert(GroupChunkTask.TASK_NAME, "a_b#g#aaa");
        insert(GroupChunkTask.TASK_NAME, "axb#g#bbb");
        insert(UpsertRowsTask.TASK_NAME, "axb#g#bbb#w");

        assertThat(inflightCounter.countByTasksAndInstancePrefix(
                WorkTasks.PIPELINE_SCOPED, DeterministicId.pipelinePrefix("a_b"))).isEqualTo(1);
        assertThat(inflightCounter.countByTasksAndInstancePrefix(
                WorkTasks.PIPELINE_SCOPED, DeterministicId.pipelinePrefix("axb"))).isEqualTo(2);
    }

    @Test
    void noTaskNamesMatchesNothingRatherThanEverything() {
        insert(GroupChunkTask.TASK_NAME, "r-1#g#aaa");

        assertThat(inflightCounter.instanceIdsByTasksAndPrefix(Set.of(), "r-1#")).isEmpty();
    }

    private void insert(String taskName, String instanceId) {
        jdbcTemplate.update("""
                        INSERT INTO scheduled_tasks
                            (task_name, task_instance, execution_time, picked, version)
                        VALUES (?, ?, ?, false, 1)
                        """,
                taskName, instanceId, Timestamp.from(Instant.parse("2026-07-01T12:00:00Z")));
    }
}
