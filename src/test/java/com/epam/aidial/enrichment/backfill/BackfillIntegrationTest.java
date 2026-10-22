package com.epam.aidial.enrichment.backfill;

import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.persistence.RunHistoryEntity;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.epam.aidial.enrichment.worker.WorkPriority;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6 backfill: starting a backfill drains the pipeline's history from the source start, enqueues chunks
 * at backfill priority, advances the run's own cursor, and completes; a paused run does not drain.
 * ADAS scan is stubbed with WireMock; PostgreSQL runs in Testcontainers (scheduler not started, so
 * the executor is invoked directly).
 */
@SpringBootTest
class BackfillIntegrationTest {

    private static final WireMockServer ADAS = AdasWireMock.server();
    private static final String PIPELINE_NAME = "r-bf";

    @Autowired
    private BackfillService backfillService;

    @Autowired
    private BackfillExecutor backfillExecutor;

    @Autowired
    private PipelineCache pipelineCache;

    @Autowired
    private RunHistoryRepository runHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startAdas() {
        ADAS.start();
    }

    @AfterAll
    static void stopAdas() {
        ADAS.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("adas.base-url", ADAS::baseUrl);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
    }

    @BeforeEach
    void reset() {
        ADAS.resetAll();
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, run_history, dlq_item");
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, TestPipelines.scanPipeline(PIPELINE_NAME, 2,
                TestPipelines.columnVar("prompt", "request_body"))));
    }

    @Test
    void backfillDrainsHistoryEnqueuesBackfillPriorityChunksAndCompletes() {
        stubScan();

        RunHistoryEntity run = backfillService.start(PIPELINE_NAME);
        backfillExecutor.run(run.getId());

        // 3 rows, chunk size 2 -> 2 enrich-chunk instances, all at backfill priority (1).
        assertThat(enrichChunkCount()).isEqualTo(2);
        assertThat(minEnrichChunkPriority()).isEqualTo(WorkPriority.BACKFILL);

        RunHistoryEntity done = runHistoryRepository.findById(run.getId()).orElseThrow();
        assertThat(done.getState()).isEqualTo("done");
        assertThat(done.getCursor()).isEqualTo("bf1");
        assertThat(done.getRowsDone()).isEqualTo(3);
    }

    @Test
    void pausedBackfillDoesNotDrain() {
        stubScan();

        RunHistoryEntity run = backfillService.start(PIPELINE_NAME);
        backfillService.pause(run.getId());
        backfillExecutor.run(run.getId());

        assertThat(enrichChunkCount()).isZero();
        assertThat(runHistoryRepository.findById(run.getId()).orElseThrow().getState()).isEqualTo("paused");
    }

    private long enrichChunkCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", Long.class, EnrichChunkTask.TASK_NAME);
        return count == null ? 0 : count;
    }

    private int minEnrichChunkPriority() {
        Integer priority = jdbcTemplate.queryForObject(
                "SELECT min(priority) FROM scheduled_tasks WHERE task_name = ?", Integer.class, EnrichChunkTask.TASK_NAME);
        return priority == null ? -1 : priority;
    }

    private void stubScan() {
        ADAS.stubFor(post(urlPathEqualTo("/v1/pipelines/" + PIPELINE_NAME + "/scan")).willReturn(okJson("""
                {
                  "rows":[
                    {"event_id":"e0","request_body":"body-0"},
                    {"event_id":"e1","request_body":"body-1"},
                    {"event_id":"e2","request_body":"body-2"}
                  ],
                  "next_cursor":"bf1","has_more":false,"lag_seconds":2
                }
                """)));
    }
}
