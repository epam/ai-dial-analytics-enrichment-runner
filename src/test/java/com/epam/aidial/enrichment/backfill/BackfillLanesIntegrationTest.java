package com.epam.aidial.enrichment.backfill;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ReadyWhenDto;
import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.exception.ScanPageColumnException;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.persistence.RunHistoryEntity;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
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

import java.time.Instant;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2's exit criteria: a backfill may be bounded to a version window, and it drives whichever read its pipeline's kind
 * requires. Before this milestone backfill knew only the row-and-Class-2 path, so pointing it at a group or a
 * Class-1 pipeline did the wrong thing rather than nothing.
 */
@SpringBootTest
class BackfillLanesIntegrationTest {

    private static final WireMockServer ADAS = AdasWireMock.server();
    private static final Instant FROM = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-01T00:00:00Z");

    @Autowired
    private BackfillService backfillService;

    @Autowired
    private BackfillExecutor executor;

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
        registry.add("group.sweep-initial-delay", () -> "3600s");
        registry.add("group.sweep-interval", () -> "3600s");
    }

    @BeforeEach
    void reset() {
        ADAS.resetAll();
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, run_history, dlq_item, group_state, pipeline_cursor");
    }

    @Test
    void boundedRowBackfillSendsTheWindowOnEveryPage() {
        String pipelineName = "bf-row";
        pipelineCache.replaceAll(Map.of(pipelineName, rowPipeline(pipelineName)));
        // Two pages, so "every page" is actually tested rather than just the first.
        ADAS.stubFor(post(urlPathEqualTo(scanPath(pipelineName))).inScenario("pages")
                .whenScenarioStateIs("Started")
                .willReturn(okJson(page("c1", true))).willSetStateTo("second"));
        ADAS.stubFor(post(urlPathEqualTo(scanPath(pipelineName))).inScenario("pages")
                .whenScenarioStateIs("second")
                .willReturn(okJson(page("c2", false))));

        RunHistoryEntity run = backfillService.start(pipelineName, BackfillWindow.of(FROM, TO));
        executor.run(run.getId());

        var requests = ADAS.findAll(postRequestedFor(urlPathEqualTo(scanPath(pipelineName))));
        assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(r -> assertThat(r.getBodyAsString())
                .contains("\"from\":\"2026-07-01T00:00:00Z\"")
                .contains("\"to\":\"2026-08-01T00:00:00Z\""));
    }

    @Test
    void unboundedRowBackfillSendsNoWindow() {
        String pipelineName = "bf-unbounded";
        pipelineCache.replaceAll(Map.of(pipelineName, rowPipeline(pipelineName)));
        ADAS.stubFor(post(urlPathEqualTo(scanPath(pipelineName))).willReturn(okJson(page("c1", false))));

        RunHistoryEntity run = backfillService.start(pipelineName);
        executor.run(run.getId());

        String body = ADAS.findAll(postRequestedFor(urlPathEqualTo(scanPath(pipelineName)))).getFirst()
                .getBodyAsString();
        assertThat(body).doesNotContain("\"from\"").doesNotContain("\"to\"");
    }

    /**
     * The backfill applies the live scan's missing-column rule to its own cursor, and this is where the
     * consequence is strictly worse. A live scan that burns a page loses one tick's rows and keeps meeting
     * new ones; a run exists to re-drive a <em>history</em>, drains batch by batch, and marks itself
     * complete when its cursor reaches the end of the window — after which nothing says the window was not
     * covered, because "already enriched" is not a predicate anywhere and the run row says done.
     *
     * <p>The builders are shared with the live lanes, so this needs no new refusal logic; what it needs is
     * the run left {@code RUNNING} with its cursor untouched, which {@code BackfillTask}'s
     * {@code BackfillBatchFailureHandler} then re-drives from.
     */
    @Test
    void backfillPageMissingTheGrainColumnLeavesTheRunRunningAtItsCursor() {
        String pipelineName = "bf-no-grain";
        pipelineCache.replaceAll(Map.of(pipelineName, rowPipeline(pipelineName)));
        ADAS.stubFor(post(urlPathEqualTo(scanPath(pipelineName)))
                .willReturn(okJson(pageWithoutGrainColumn())));

        RunHistoryEntity run = backfillService.start(pipelineName);
        assertThatThrownBy(() -> executor.run(run.getId()))
                .isInstanceOf(ScanPageColumnException.class)
                .hasMessageContaining("event_id");

        RunHistoryEntity after = runHistoryRepository.findById(run.getId()).orElseThrow();
        assertThat(after.getCursor()).isNull();
        assertThat(after.getRowsDone()).isZero();
        // Not complete, and no dead-letter flood: the page is the defect, not its rows.
        assertThat(after.getState()).isEqualTo(RunState.RUNNING.dbValue());
        assertThat(after.getFinishedAt()).isNull();
        assertThat(dlqCount()).isZero();
        assertThat(taskCount(EnrichChunkTask.TASK_NAME)).isZero();
    }

    @Test
    void groupBackfillMarksStateAndEnqueuesNoGroupItems() {
        // Marking is the whole job: the readiness sweep decides when a marked group is evaluated, so the pipeline's
        // idle, staleness and cost-ceiling gates still apply. Enqueueing here would bypass all three.
        String pipelineName = "bf-group";
        pipelineCache.replaceAll(Map.of(pipelineName, groupPipeline(pipelineName)));
        ADAS.stubFor(post(urlPathEqualTo(scanPath(pipelineName)))
                .willReturn(okJson(groupPage())));

        RunHistoryEntity run = backfillService.start(pipelineName, BackfillWindow.of(FROM, TO));
        executor.run(run.getId());

        assertThat(rowCount("group_state WHERE pipeline_name = '" + pipelineName + "'")).isEqualTo(1);
        assertThat(taskCount(GroupChunkTask.TASK_NAME)).isZero();
        assertThat(taskCount(EnrichChunkTask.TASK_NAME)).isZero();
        assertThat(backfillService.get(run.getId()).getState()).isEqualTo("done");
    }

    @Test
    void class1BackfillMaterializesTheWindowAndEnqueuesNothing() {
        String pipelineName = "bf-class1";
        pipelineCache.replaceAll(Map.of(pipelineName, TestPipelines.class1(pipelineName, 1, TestPipelines.EVERY_5_MINUTES)));
        ADAS.stubFor(post(urlPathEqualTo("/v1/pipelines/" + pipelineName + "/materialize"))
                .willReturn(okJson("{\"rows_written\":42,\"next_cursor\":\"c1\",\"has_more\":false}")));

        RunHistoryEntity run = backfillService.start(pipelineName, BackfillWindow.of(FROM, TO));
        executor.run(run.getId());

        var requests = ADAS.findAll(postRequestedFor(urlPathEqualTo("/v1/pipelines/" + pipelineName + "/materialize")));
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().getBodyAsString()).contains("\"from\"").contains("\"to\"");
        assertThat(ADAS.findAll(postRequestedFor(urlPathEqualTo(scanPath(pipelineName))))).isEmpty();
        assertThat(taskCount(EnrichChunkTask.TASK_NAME)).isZero();
        // rows_done accumulates what ADAS reported writing.
        assertThat(backfillService.get(run.getId()).getRowsDone()).isEqualTo(42);
    }

    @Test
    void invertedWindowIsRejectedBeforeCreatingRun() {
        String pipelineName = "bf-bad";
        pipelineCache.replaceAll(Map.of(pipelineName, rowPipeline(pipelineName)));

        assertThatThrownBy(() -> backfillService.start(pipelineName, BackfillWindow.of(TO, FROM)))
                .isInstanceOf(com.epam.aidial.enrichment.exception.BackfillWindowException.class);

        assertThat(rowCount("run_history")).isZero();
    }

    @Test
    void runReportsAndRetainsWindowAcrossPauseAndResume() {
        String pipelineName = "bf-window";
        pipelineCache.replaceAll(Map.of(pipelineName, rowPipeline(pipelineName)));

        RunHistoryEntity run = backfillService.start(pipelineName, BackfillWindow.of(FROM, TO));
        assertThat(run.getWindowFrom()).isEqualTo(FROM);
        assertThat(run.getWindowTo()).isEqualTo(TO);

        backfillService.pause(run.getId());
        RunHistoryEntity resumed = backfillService.resume(run.getId());

        // The window lives on run_history, so resume retains it without carrying it through the request.
        assertThat(resumed.getWindowFrom()).isEqualTo(FROM);
        assertThat(resumed.getWindowTo()).isEqualTo(TO);
    }

    private static String scanPath(String pipelineName) {
        return "/v1/pipelines/" + pipelineName + "/scan";
    }

    private static PipelineDto rowPipeline(String pipelineName) {
        return TestPipelines.builder(pipelineName)
                .transformInputs(TestPipelines.columnVar("prompt", "request_body"))
                .build();
    }

    private static PipelineDto groupPipeline(String pipelineName) {
        return TestPipelines.builder(pipelineName)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .grainKey("chat_id")
                .readyWhen(new ReadyWhenDto(null, "10m", null, null))
                .build();
    }

    /** Real rows carrying no {@code event_id} at all — the authored-projection shape, not a null value. */
    private static String pageWithoutGrainColumn() {
        return """
                {"rows":[{"request_body":"hi","_ingested_at":"2026-07-15T10:00:00Z"}],
                 "next_cursor":"c1","has_more":true,"lag_seconds":3}
                """;
    }

    private static String page(String nextCursor, boolean hasMore) {
        return """
                {"rows":[{"event_id":"e0","request_body":"hi","_ingested_at":"2026-07-15T10:00:00Z"}],
                 "next_cursor":"%s","has_more":%s,"lag_seconds":3}
                """.formatted(nextCursor, hasMore);
    }

    /** A single, final page of one group-lane row. */
    private static String groupPage() {
        return """
                {"rows":[{"chat_id":"chat-a","event_id":"e0","_ingested_at":"2026-07-15T10:00:00Z"}],
                 "next_cursor":"c1","has_more":false,"lag_seconds":3}
                """;
    }

    private long dlqCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM dlq_item", Long.class);
        return count == null ? 0 : count;
    }

    private long taskCount(String taskName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", Long.class, taskName);
        return count == null ? 0 : count;
    }

    private long rowCount(String fromClause) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + fromClause, Long.class);
        return count == null ? 0 : count;
    }
}
