package com.epam.aidial.enrichment.sync;

import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.observability.ReadinessState;
import com.epam.aidial.enrichment.scan.PipelineScanTask;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciling to empty requires a confirming poll — and the first, unconfirmed one still marks the sync
 * succeeded.
 *
 * <p><b>Ordered on purpose, and for the assertion that is easiest to get wrong.</b> The readiness
 * contributor answers "has this process ever read the registry", not "does the registry have contents", so
 * a legitimately empty registry must still bring a fresh pod into service. Withholding readiness for it
 * would keep the pod out — and, with a poll interval longer than the startup budget, restart it forever
 * over a state that is not an error. {@link ReadinessState} is a latch, so that can only be observed on a
 * context whose first poll is the empty one.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmptyFeedConfirmationIntegrationTest {

    private static final WireMockServer ADAS = AdasWireMock.server();

    @Autowired
    private PipelineSyncService syncService;

    @Autowired
    private PipelineCache cache;

    @Autowired
    private ReadinessState readinessState;

    @Autowired
    private MeterRegistry meterRegistry;

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

    @BeforeEach
    void resetStubs() {
        ADAS.resetAll();
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, pipeline_cursor");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("adas.base-url", ADAS::baseUrl);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
    }

    /**
     * The poll <em>did</em> succeed. A registry with no enabled pipelines is not an error state, and the
     * confirmation governs only whether tasks are cancelled — never whether the pod comes into service.
     */
    @Test
    @Order(1)
    void firstEmptyFeedStillMarksTheSyncSucceededAndIsCounted() {
        assertThat(readinessState.isPipelineSyncSucceeded()).isFalse();
        double before = emptyFeeds();
        stubPipelines("");

        syncService.sync();

        assertThat(readinessState.isPipelineSyncSucceeded()).isTrue();
        assertThat(emptyFeeds()).isEqualTo(before + 1);
    }

    /** One empty poll changes nothing but the signals: the cache and the recurring tasks stay in place. */
    @Test
    @Order(2)
    void oneEmptyFeedLeavesTheCacheAndTasksAlone() {
        stubPipelines(onlyPipeline());
        syncService.sync();
        assertThat(cache.size()).isEqualTo(1);
        assertThat(scanTaskCount()).isEqualTo(1);
        double before = emptyFeeds();

        stubPipelines("");
        syncService.sync();

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("only")).isNotNull();
        assertThat(scanTaskCount()).isEqualTo(1);
        assertThat(emptyFeeds()).isEqualTo(before + 1);
    }

    /** An empty feed stays authoritative — it just takes a second, consecutive poll to act on. */
    @Test
    @Order(3)
    void twoConsecutiveEmptyFeedsReconcileToEmpty() {
        stubPipelines(onlyPipeline());
        syncService.sync();

        stubPipelines("");
        syncService.sync();
        syncService.sync();

        assertThat(cache.size()).isZero();
        assertThat(scanTaskCount()).isZero();
    }

    /** A non-empty poll in between resets the count, so two empties an hour apart cancel nothing. */
    @Test
    @Order(4)
    void nonEmptyPollBetweenTwoEmptyOnesResetsTheConfirmation() {
        stubPipelines(onlyPipeline());
        syncService.sync();

        stubPipelines("");
        syncService.sync();

        stubPipelines(onlyPipeline());
        syncService.sync();

        stubPipelines("");
        syncService.sync();

        // The second empty feed is the first of a new run, so it is unconfirmed and cancels nothing.
        assertThat(cache.size()).isEqualTo(1);
        assertThat(scanTaskCount()).isEqualTo(1);
    }

    private double emptyFeeds() {
        return meterRegistry.get("runner.sync.empty.feeds").counter().count();
    }

    private long scanTaskCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM scheduled_tasks WHERE task_name = ?",
                Long.class, PipelineScanTask.TASK_NAME);
        return count == null ? 0 : count;
    }

    private void stubPipelines(String pipelineObjects) {
        ADAS.stubFor(get(urlPathEqualTo("/v1/pipelines"))
                .withQueryParam("kind", equalTo("enrich"))
                .withQueryParam("enabled", equalTo("true"))
                .withQueryParam("view", equalTo("compiled"))
                .willReturn(okJson("{\"pipelines\":[" + pipelineObjects + "]}")));
    }

    /** The feed's single pipeline, {@code only}, at generation 1. */
    private static String onlyPipeline() {
        return """
                {
                  "name":"only","kind":"enrich",
                  "transform":{"type":"llm","preset":"chat_completion","model":"gpt-4o",
                    "request_template":"{\\"messages\\":[]}",
                    "inputs":{"text":{"column":"request_body"}},
                    "outputs":{"label":{"prose":"the label"}}},
                  "response_schema":{"type":"object","additionalProperties":false,
                    "properties":{"label":{"type":"string"}}},
                  "target":"enr_only","inputs":["dial_usage_log"],"grain_key":"event_id",
                  "version_column":"_ingested_at",
                  "trigger":{"kind":"schedule","cron":"10 * * * * *"},
                  "outputs":[{"name":"label","column":"label"}],
                  "advanced":{"rows_per_scan":1000},
                  "enabled":true,"generation":1,
                  "created_at":"2026-07-01T00:00:00Z","updated_at":"2026-07-01T00:00:00Z"
                }
                """;
    }
}
