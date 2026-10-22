package com.epam.aidial.enrichment.class1;

import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.persistence.PipelineCursorEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B1 exit-criteria test: a Class-1 ({@code transform.type=sql}) pipeline drains materialize windows and
 * advances its cursor only on a 2xx, an empty window never resets that cursor, a failed window leaves it
 * unmoved, and the whole lane runs with zero DIAL traffic, zero queue enqueues, zero spend rows and zero
 * dead-letter rows. ADAS and DIAL are stubbed on one WireMock server (different paths); the runner's
 * PostgreSQL runs in Testcontainers.
 */
@SpringBootTest
class Class1MaterializeIntegrationTest {

    private static final WireMockServer BACKEND = AdasWireMock.server();
    private static final String PIPELINE_NAME = "r-class1";
    private static final String MATERIALIZE_PATH = "/v1/pipelines/" + PIPELINE_NAME + "/materialize";
    private static final String SCAN_PATH = "/v1/pipelines/" + PIPELINE_NAME + "/scan";
    private static final String ROWS_PATH = "/v1/pipelines/" + PIPELINE_NAME + "/rows";
    private static final String PRIOR_CURSOR = "c-before";

    @Autowired
    private Class1MaterializeExecutor executor;

    @Autowired
    private PipelineCache pipelineCache;

    @Autowired
    private PipelineCursorRepository cursorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startBackend() {
        BACKEND.start();
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("adas.base-url", BACKEND::baseUrl);
        registry.add("dial.base-url", BACKEND::baseUrl);
        // Keep the scheduler from firing and the background poll from racing the manual ticks.
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
        // Bound the drain so a has_more=true stub cannot loop 50 times.
        registry.add("scan.max-pages-per-fire", () -> "3");
    }

    @BeforeEach
    void reset() {
        BACKEND.resetAll();
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, pipeline_cursor, dlq_item, spend_counter");
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, TestPipelines.class1(PIPELINE_NAME, 1, TestPipelines.EVERY_5_MINUTES)));
    }

    @Test
    void drainsWindowsAndAdvancesTheCursorOnSuccess() {
        stubMaterialize(window(500, "c1", true), window(120, "c2", false));

        executor.execute(PIPELINE_NAME);

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(MATERIALIZE_PATH)))).hasSize(2);
        assertThat(storedCursor()).isEqualTo("c2");
    }

    @Test
    void materializeFailureLeavesTheCursorUnmoved() {
        seedPriorCursor();
        BACKEND.stubFor(post(urlPathEqualTo(MATERIALIZE_PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> executor.execute(PIPELINE_NAME)).isInstanceOf(RuntimeException.class);

        assertThat(storedCursor()).isEqualTo(PRIOR_CURSOR);
    }

    @Test
    void emptyWindowDoesNotResetTheCursor() {
        seedPriorCursor();
        // ADAS omits next_cursor entirely on an empty first window; persisting that null would restart
        // the pipeline from the source start on the next fire.
        BACKEND.stubFor(post(urlPathEqualTo(MATERIALIZE_PATH))
                .willReturn(okJson("{\"rows_written\":0,\"has_more\":false}")));

        executor.execute(PIPELINE_NAME);

        assertThat(storedCursor()).isEqualTo(PRIOR_CURSOR);
    }

    @Test
    void drainStopsAtThePerFireBudget() {
        stubMaterialize(window(1, "c1", true), window(1, "c2", true), window(1, "c3", true),
                window(1, "c4", true));

        executor.execute(PIPELINE_NAME);

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(MATERIALIZE_PATH)))).hasSize(3);
        assertThat(storedCursor()).isEqualTo("c3");
    }

    @Test
    void class1LaneTouchesNoDialNoQueueNoSpendAndNoDlq() {
        stubMaterialize(window(42, "c1", false));

        executor.execute(PIPELINE_NAME);

        assertThat(BACKEND.findAll(postRequestedFor(urlPathMatching("/openai/deployments/.*")))).isEmpty();
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(SCAN_PATH)))).isEmpty();
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROWS_PATH)))).isEmpty();
        assertThat(rowCount("scheduled_tasks WHERE task_name = '" + EnrichChunkTask.TASK_NAME + "'")).isZero();
        assertThat(rowCount("spend_counter")).isZero();
        assertThat(rowCount("dlq_item")).isZero();
    }

    /** Stubs one response per successive materialize call in a drain, via a WireMock scenario. */
    private void stubMaterialize(String... bodies) {
        if (bodies.length == 1) {
            BACKEND.stubFor(post(urlPathEqualTo(MATERIALIZE_PATH)).willReturn(okJson(bodies[0])));
            return;
        }
        String scenario = "materialize";
        for (int i = 0; i < bodies.length; i++) {
            BACKEND.stubFor(post(urlPathEqualTo(MATERIALIZE_PATH))
                    .inScenario(scenario)
                    .whenScenarioStateIs(i == 0 ? "Started" : "window" + i)
                    .willReturn(okJson(bodies[i]))
                    .willSetStateTo("window" + (i + 1)));
        }
    }

    private static String window(long rowsWritten, String nextCursor, boolean hasMore) {
        return "{\"rows_written\":%d,\"next_cursor\":\"%s\",\"has_more\":%s,\"lag_seconds\":5}"
                .formatted(rowsWritten, nextCursor, hasMore);
    }

    private void seedPriorCursor() {
        PipelineCursorEntity entity = new PipelineCursorEntity(PIPELINE_NAME);
        entity.setCursor(PRIOR_CURSOR);
        cursorRepository.save(entity);
    }

    private String storedCursor() {
        return cursorRepository.findById(PIPELINE_NAME).map(PipelineCursorEntity::getCursor).orElse(null);
    }

    private long rowCount(String fromClause) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + fromClause, Long.class);
        return count == null ? 0 : count;
    }
}
