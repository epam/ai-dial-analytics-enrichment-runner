package com.epam.aidial.enrichment.scan.group;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.exception.ScanPageColumnException;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.GroupStateEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.scan.PipelineScanExecutor;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.TestPages;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A1 exit-criteria test for the dirty-mark half: a group pipeline's scan page becomes per-group state rather
 * than chunk work items, the state upsert and the cursor advance commit together (or not at all), a
 * repeated tick is a genuine no-op, and an older window cannot move a group backwards.
 */
@SpringBootTest
class GroupDirtyMarkIntegrationTest {

    private static final WireMockServer ADAS = AdasWireMock.server();
    private static final String PIPELINE_NAME = "r-group";
    private static final String SCAN_PATH = "/v1/pipelines/" + PIPELINE_NAME + "/scan";

    @Autowired
    private PipelineScanExecutor executor;

    @Autowired
    private GroupDirtyMarkCommitter committer;

    @Autowired
    private PipelineCache pipelineCache;

    @Autowired
    private PipelineCursorRepository cursorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PipelinePauseRegistry pauseRegistry;

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
        // Keep the background sweep from racing the manual ticks.
        registry.add("group.sweep-initial-delay", () -> "3600s");
        registry.add("group.sweep-interval", () -> "3600s");
    }

    @BeforeEach
    void reset() {
        ADAS.resetAll();
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, pipeline_cursor, dlq_item, group_state");
        // The pause registry is in-memory and outlives a test method, and a page that dead-letters a large
        // enough share of its rows auto-pauses the pipeline — which would silently skip the next test's scan.
        pauseRegistry.resume(PIPELINE_NAME);
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, groupPipeline()));
    }

    @Test
    void groupPageBecomesGroupStateAndNotChunks() {
        stubScan(page(row("chat-a", "2026-07-01T10:00:00Z"), row("chat-a", "2026-07-01T10:00:05Z"),
                row("chat-b", "2026-07-01T10:00:02Z")));

        executor.execute(PIPELINE_NAME);

        assertThat(groupVersion("chat-a")).isEqualTo(Instant.parse("2026-07-01T10:00:05Z").toEpochMilli());
        assertThat(groupVersion("chat-b")).isEqualTo(Instant.parse("2026-07-01T10:00:02Z").toEpochMilli());
        assertThat(groupStateCount()).isEqualTo(2);
        // Nothing is enqueued by the dirty-mark: deciding what to evaluate is the sweep's job.
        assertThat(chunkCount()).isZero();
        assertThat(cursorRepository.findById(PIPELINE_NAME)).get()
                .extracting(PipelineCursorEntity::getCursor).isEqualTo("c1");
    }

    @Test
    void repeatedTickCreatesNoDuplicateState() {
        stubScan(page(row("chat-a", "2026-07-01T10:00:00Z")));
        executor.execute(PIPELINE_NAME);
        long versionAfterFirst = groupVersion("chat-a");

        // Simulate a crash before the cursor persisted: the same range is scanned again.
        cursorRepository.deleteById(PIPELINE_NAME);
        executor.execute(PIPELINE_NAME);

        assertThat(groupStateCount()).isEqualTo(1);
        assertThat(groupVersion("chat-a")).isEqualTo(versionAfterFirst);
    }

    @Test
    void olderWindowDoesNotMoveGroupBackwards() {
        stubScan(page(row("chat-a", "2026-07-01T12:00:00Z")));
        executor.execute(PIPELINE_NAME);
        long newest = groupVersion("chat-a");

        // A backfill re-driving an older window must not regress the group and re-fire it.
        cursorRepository.deleteById(PIPELINE_NAME);
        ADAS.resetAll();
        stubScan(page(row("chat-a", "2026-07-01T09:00:00Z")));
        executor.execute(PIPELINE_NAME);

        assertThat(groupVersion("chat-a")).isEqualTo(newest);
    }

    @Test
    void commitRollsBackStateAndCursorTogetherOnFailure() {
        GroupStateEntity mark = GroupStateEntity.builder()
                .pipelineName(PIPELINE_NAME)
                .groupKey("chat-a")
                .groupVersion(100L)
                .lastActivityAt(Instant.parse("2026-07-01T10:00:00Z"))
                .build();
        DlqItemEntity invalid = DlqItemEntity.builder()
                .pipelineName(PIPELINE_NAME)
                .stage("not_a_valid_stage") // violates the dlq_item.stage CHECK constraint
                .build();

        assertThatThrownBy(() -> committer.commit(PIPELINE_NAME, TestPipelines.CREATED_AT, "c1", 1L,
                new GroupMarkBatch(List.of(mark), List.of(invalid), TestPages.workAndDeadLetters(1, 1))))
                .isInstanceOf(RuntimeException.class);

        assertThat(groupStateCount()).isZero();
        assertThat(cursorRepository.findById(PIPELINE_NAME)).isEmpty();
    }

    @Test
    void unparseableVersionDeadLettersAndTheCursorStillAdvances() {
        stubScan(page(row("chat-bad", "nonsense"), row("chat-a", "2026-07-01T10:00:00Z")));

        executor.execute(PIPELINE_NAME);

        assertThat(groupStateCount()).isEqualTo(1);
        assertThat(dlqCount()).isEqualTo(1);
        assertThat(cursorRepository.findById(PIPELINE_NAME)).get()
                .extracting(PipelineCursorEntity::getCursor).isEqualTo("c1");
    }

    @Test
    void pageWithoutNextCursorLeavesTheStoredCursorIntact() {
        // First tick establishes a real position.
        stubScan(page(row("chat-a", "2026-07-01T10:00:00Z")));
        executor.execute(PIPELINE_NAME);
        assertThat(cursorRepository.findById(PIPELINE_NAME)).get()
                .extracting(PipelineCursorEntity::getCursor).isEqualTo("c1");

        // Then ADAS answers with has_more and no next_cursor. Writing that null through would set the cursor
        // to "the source start", so the next fire would re-scan the pipeline's whole history — and re-derive every
        // group's label with it.
        ADAS.resetAll();
        stubScan("{\"rows\":[" + row("chat-a", "2026-07-01T11:00:00Z") + "],\"has_more\":true}");
        executor.execute(PIPELINE_NAME);

        assertThat(cursorRepository.findById(PIPELINE_NAME)).get()
                .extracting(PipelineCursorEntity::getCursor).isEqualTo("c1");
        // The page's own work still landed — only the cursor is held back.
        assertThat(groupVersion("chat-a")).isEqualTo(Instant.parse("2026-07-01T11:00:00Z").toEpochMilli());
    }

    @Test
    void repeatedlyDeadLetteredGroupDoesNotBreakThePageCommit() {
        // One bad row among good ones, so the page stays under the breaker's fraction and the pipeline keeps
        // scanning — the case where the same grain key really does get dead-lettered by page after page.
        stubScan(page(row("chat-bad", "nonsense"), row("chat-a", "2026-07-01T10:00:00Z"),
                row("chat-b", "2026-07-01T10:00:01Z")));
        executor.execute(PIPELINE_NAME);

        // The same range again (a crash before the cursor persisted). An insert would collide with dlq_item's
        // partial unique key and abort the whole commit — taking the cursor advance down with it, so the pipeline
        // would stall on this page forever.
        cursorRepository.deleteById(PIPELINE_NAME);
        executor.execute(PIPELINE_NAME);

        assertThat(dlqCount()).isEqualTo(1);
        assertThat(cursorRepository.findById(PIPELINE_NAME)).get()
                .extracting(PipelineCursorEntity::getCursor).isEqualTo("c1");
    }

    /** The group lane's group-by column, read structurally for the same reason the row lane's grain is. */
    @Test
    void pageWithoutTheGroupByColumnHoldsTheCursorAndUpsertsNoState() {
        stubScan(page("{\"_ingested_at\":\"2026-07-01T10:00:00Z\",\"event_id\":\"e-1\"}"));

        assertThatThrownBy(() -> executor.execute(PIPELINE_NAME))
                .isInstanceOf(ScanPageColumnException.class)
                .hasMessageContaining("chat_id");

        assertThat(groupStateCount()).isZero();
        assertThat(cursorRepository.findById(PIPELINE_NAME)).isEmpty();
        assertThat(dlqCount()).isZero();
    }

    /**
     * The behaviour change, and the sharpest case in this change. An absent version <em>key</em> used to be
     * an error that dead-lettered the row — so a page whose version column was simply not in the projection
     * dead-lettered every row of every page: at 1000 rows a page and 50 pages a fire, tens of thousands of
     * items that are not row defects, recoverable only by bulk requeue. The assertion that matters here is
     * the DLQ being <b>empty</b>.
     */
    @Test
    void pageWithoutTheVersionColumnHoldsTheCursorAndWritesNoDeadLetter() {
        stubScan(page("{\"chat_id\":\"chat-a\",\"event_id\":\"e-1\"}",
                "{\"chat_id\":\"chat-b\",\"event_id\":\"e-2\"}"));

        assertThatThrownBy(() -> executor.execute(PIPELINE_NAME))
                .isInstanceOf(ScanPageColumnException.class)
                .hasMessageContaining("_ingested_at");

        assertThat(dlqCount()).isZero();
        assertThat(groupStateCount()).isZero();
        assertThat(cursorRepository.findById(PIPELINE_NAME)).isEmpty();
    }

    private static PipelineDto groupPipeline() {
        return TestPipelines.group(PIPELINE_NAME, 1, "chat_id", "10m");
    }

    private void stubScan(String body) {
        ADAS.stubFor(post(urlPathEqualTo(SCAN_PATH)).willReturn(okJson(body)));
    }

    private static String page(String... rows) {
        return "{\"rows\":[%s],\"next_cursor\":\"c1\",\"has_more\":false,\"lag_seconds\":3}"
                .formatted(String.join(",", rows));
    }

    private static String row(String chatId, String ingestedAt) {
        return "{\"chat_id\":\"%s\",\"_ingested_at\":\"%s\",\"event_id\":\"e-%s\"}"
                .formatted(chatId, ingestedAt, chatId);
    }

    private long groupVersion(String groupKey) {
        Long version = jdbcTemplate.queryForObject(
                "SELECT group_version FROM group_state WHERE pipeline_name = ? AND group_key = ?",
                Long.class, PIPELINE_NAME, groupKey);
        return version == null ? -1 : version;
    }

    private long groupStateCount() {
        return count("group_state");
    }

    private long dlqCount() {
        return count("dlq_item");
    }

    private long chunkCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", Long.class,
                EnrichChunkTask.TASK_NAME);
        return count == null ? 0 : count;
    }

    private long count(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
