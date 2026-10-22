package com.epam.aidial.enrichment.worker.upsert;

import com.epam.aidial.enrichment.dlq.DlqRequeueService;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemRepository;
import com.epam.aidial.enrichment.worker.WorkPriority;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The phase boundary must not be able to drop a computed result, and must not be able to <em>queue</em> one
 * behind work that will never let it run. Without the shared transaction, a crash between "compute
 * succeeded" and "write item enqueued" loses the rows entirely: the compute item is gone, nothing holds the
 * labels, and the row is never written.
 *
 * <p>The priority half is the same concern one layer up. db-scheduler orders due executions
 * {@code priority desc, execution_time asc}; a write is younger than the compute that created it and
 * younger than every compute enqueued alongside it, so at equal priority it can never reach the front while
 * compute remains — a pipeline that computes labels, pays for every model call, and writes nothing. Those
 * assertions are pinned on the ordering db-scheduler actually polls with, because a timing test here would
 * pass by being slow.
 */
@SpringBootTest
class UpsertEnqueuerIntegrationTest {

    private static final String PIPELINE_NAME = "r-enqueuer";
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

    /** db-scheduler's own polling order when priority is enabled; see JdbcTaskRepository. */
    private static final String NEXT_DUE = """
            SELECT task_name FROM scheduled_tasks
            WHERE picked = false
            ORDER BY priority DESC, execution_time ASC
            LIMIT 1
            """;

    @Autowired
    private UpsertEnqueuer enqueuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DlqRequeueService requeueService;

    @Autowired
    private DlqItemRepository dlqItemRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
        registry.add("group.sweep-initial-delay", () -> "3600s");
        registry.add("group.sweep-interval", () -> "3600s");
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, dlq_item");
    }

    @Test
    void writeOutranksAnOlderComputeBacklog() {
        // The starvation shape: a deep compute backlog, every item of it older than the write it
        // eventually produced.
        for (int i = 0; i < 20; i++) {
            insertCompute("g" + i, NOW.minus(Duration.ofMinutes(10)));
        }
        enqueuer.commit(PIPELINE_NAME + "#g0", write(), List.of());

        assertThat(nextDue()).isEqualTo(UpsertRowsTask.TASK_NAME);
    }

    @Test
    void writeLaneOutranksBothComputeLanes() {
        enqueuer.commit(PIPELINE_NAME + "#g0", write(), List.of());

        assertThat(upsertPriority())
                .isEqualTo(WorkPriority.WRITE_BACK)
                .isGreaterThan(WorkPriority.LIVE)
                .isGreaterThan(WorkPriority.BACKFILL);
    }

    @Test
    void requeuedWriteIsNotTheLowestPriorityItem() {
        // An unset priority is 0 — below backfill compute — so a requeue an operator asked for by hand used
        // to land under every automatically enqueued item.
        insertCompute("g0", NOW.minus(Duration.ofMinutes(10)));
        DlqItemEntity archived = dlqItemRepository.save(DlqItemEntity.builder()
                .pipelineName(PIPELINE_NAME)
                .grainKey("g-archived")
                .pipelineGeneration(3L)
                .stage("upsert")
                .error("ADAS was down")
                .payload(jsonMapper.writeValueAsString(write()))
                .taskName(UpsertRowsTask.TASK_NAME)
                .build());

        requeueService.requeue(archived.getId());

        assertThat(upsertPriority()).isEqualTo(WorkPriority.WRITE_BACK);
        assertThat(nextDue()).isEqualTo(UpsertRowsTask.TASK_NAME);
    }

    /** A queued group compute item at live priority. */
    private void insertCompute(String suffix, Instant executionTime) {
        jdbcTemplate.update("""
                        INSERT INTO scheduled_tasks
                            (task_name, task_instance, execution_time, picked, version, priority)
                        VALUES (?, ?, ?, false, 1, ?)
                        """,
                GroupChunkTask.TASK_NAME, PIPELINE_NAME + "#" + suffix, Timestamp.from(executionTime), WorkPriority.LIVE);
    }

    private String nextDue() {
        return jdbcTemplate.queryForObject(NEXT_DUE, String.class);
    }

    @Test
    void enqueueAndDeadLettersCommitTogether() {
        enqueuer.commit("chunk-1", write(), List.of(dlqItem("input_map")));

        assertThat(queuedWrites()).isEqualTo(1);
        assertThat(dlqCount()).isEqualTo(1);
    }

    @Test
    void failedCommitRollsBackBoth() {
        // Forced by an invalid stage, which the dlq_item CHECK constraint rejects — the same trick the scan's
        // atomicity test uses.
        assertThatThrownBy(() -> enqueuer.commit("chunk-1", write(), List.of(dlqItem("not_a_valid_stage"))))
                .isInstanceOf(RuntimeException.class);

        assertThat(queuedWrites()).isZero();
        assertThat(dlqCount()).isZero();
    }

    @Test
    void computeWithNoRowsQueuesNoWrite() {
        enqueuer.commit("chunk-1", null, List.of(dlqItem("input_map")));

        assertThat(queuedWrites()).isZero();
        assertThat(dlqCount()).isEqualTo(1);
    }

    private static UpsertPayload write() {
        return new UpsertPayload(PIPELINE_NAME, 3, List.of(Map.of("event_id", "e0", "sentiment", "positive")));
    }

    private static DlqItemEntity dlqItem(String stage) {
        return DlqItemEntity.builder().pipelineName(PIPELINE_NAME).grainKey("e1").pipelineGeneration(3L).stage(stage).build();
    }

    private long queuedWrites() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", Long.class,
                UpsertRowsTask.TASK_NAME);
        return count == null ? 0 : count;
    }

    private long dlqCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM dlq_item", Long.class);
        return count == null ? 0 : count;
    }

    private int upsertPriority() {
        Integer priority = jdbcTemplate.queryForObject(
                "SELECT priority FROM scheduled_tasks WHERE task_name = ?", Integer.class, UpsertRowsTask.TASK_NAME);
        return priority == null ? -1 : priority;
    }
}
