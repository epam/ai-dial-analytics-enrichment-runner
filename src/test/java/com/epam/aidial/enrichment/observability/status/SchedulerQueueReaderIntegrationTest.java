package com.epam.aidial.enrichment.observability.status;

import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.epam.aidial.enrichment.scan.PipelineScanTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The heartbeat's {@code scheduled_tasks} and {@code run_history} reads, against PostgreSQL. */
@SpringBootTest
class SchedulerQueueReaderIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final String WORK_TASK = "status-reader-test-task";
    private static final String PIPELINE = "status-reader-test-pipeline";

    @Autowired
    private SchedulerQueueReader reader;

    @Autowired
    private RunHistoryRepository runHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM scheduled_tasks WHERE task_name = ? OR task_instance = ?", WORK_TASK, PIPELINE);
    }

    @Test
    void readsNextFiresDepthOverdueAndLongestExecuting() {
        insert(PipelineScanTask.TASK_NAME, PIPELINE, NOW.plusSeconds(60), false);
        insert(WORK_TASK, "a", NOW.minusSeconds(300), false);
        insert(WORK_TASK, "b", NOW.minusSeconds(3600), true);
        insert(WORK_TASK, "c", NOW.plusSeconds(300), false);

        assertThat(reader.nextFires()).containsEntry(PIPELINE, new SchedulerQueueReader.NextFire(NOW.plusSeconds(60), false));
        assertThat(reader.depthByTask(NOW.minusSeconds(10))).containsEntry(WORK_TASK, new SchedulerQueueReader.TaskDepth(3, 1));
        assertThat(reader.longestExecuting()).contains(new SchedulerQueueReader.Executing(WORK_TASK, NOW.minusSeconds(3600)));
        assertThat(runHistoryRepository.countByState("running")).isGreaterThanOrEqualTo(0);
    }

    private void insert(String taskName, String instance, Instant executionTime, boolean picked) {
        jdbcTemplate.update("INSERT INTO scheduled_tasks (task_name, task_instance, execution_time, picked, version) VALUES (?, ?, ?, ?, 1)",
                taskName, instance, Timestamp.from(executionTime), picked);
    }
}
