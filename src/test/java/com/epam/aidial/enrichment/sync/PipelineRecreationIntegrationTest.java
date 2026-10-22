package com.epam.aidial.enrichment.sync;

import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.PipelineRecreatedException;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.pause.PauseOrigin;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.GroupStateEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorWriter;
import com.epam.aidial.enrichment.scan.ChunkBatch;
import com.epam.aidial.enrichment.scan.ScanPageCommitter;
import com.epam.aidial.enrichment.scan.group.GroupDirtyMarkCommitter;
import com.epam.aidial.enrichment.scan.group.GroupMarkBatch;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.CapturingAppender;
import com.epam.aidial.enrichment.support.TestPages;
import com.epam.aidial.enrichment.worker.ChunkPayload;
import com.epam.aidial.enrichment.worker.ChunkRow;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.upsert.UpsertRowsTask;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A pipeline deleted and recreated under the same name starts from empty runner state. ADAS's {@code created_at} is
 * what tells the two apart: the sync records it on the cursor row, adopts it where none is recorded, and resets the
 * name's cursor, groups, queued work and active backfills when the feed reports a different one. The cursor commit
 * refuses a page scanned for the pipeline that was reset.
 */
@SpringBootTest
class PipelineRecreationIntegrationTest {

    private static final WireMockServer ADAS = AdasWireMock.server();

    private static final String NAME = "p";
    private static final Instant FIRST = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-10-06T09:30:00.123456Z");

    @Autowired
    private PipelineSyncService syncService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PipelinePauseRegistry pauseRegistry;

    @Autowired
    private ScanPageCommitter scanPageCommitter;

    @Autowired
    private PipelineCursorWriter cursorWriter;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private GroupDirtyMarkCommitter groupDirtyMarkCommitter;

    @Autowired
    private PipelineStateReset stateReset;

    @Autowired
    private PipelineCache cache;

    @BeforeAll
    static void startAdas() {
        ADAS.start();
    }

    @AfterAll
    static void stopAdas() {
        ADAS.stop();
    }

    @BeforeEach
    void reset() {
        ADAS.resetAll();
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, pipeline_cursor, group_state, run_history, dlq_item");
        pauseRegistry.resume(NAME);
        // The cache outlives the context's tests: without this, a test's first sync compares against whichever
        // created_at the previous test left cached, and takes the in-memory reset branch depending on test order.
        cache.replaceAll(Map.of());
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("adas.base-url", ADAS::baseUrl);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
    }

    @Test
    void firstSightingRecordsThePipelineWithoutCursor() {
        stubFeed(pipeline(NAME, 1, FIRST));

        syncService.sync();

        assertThat(storedCreatedAt()).isEqualTo(FIRST);
        assertThat(storedCursor()).isNull();
    }

    @Test
    void rowFromBeforeTheColumnIsAdoptedAndKeepsItsCursor() {
        jdbcTemplate.update("INSERT INTO pipeline_cursor (pipeline_name, cursor, updated_at) VALUES (?, 'c5', now())", NAME);
        stubFeed(pipeline(NAME, 4, FIRST));

        syncService.sync();

        assertThat(storedCreatedAt()).isEqualTo(FIRST);
        assertThat(storedCursor()).isEqualTo("c5");
    }

    @Test
    void unchangedPipelineKeepsItsStateAcrossPollsAndReEnable() {
        stubFeed(pipeline(NAME, 2, FIRST));
        syncService.sync();
        setCursor("c5");
        group();

        // Disabled: the pipeline leaves the feed. Re-enabled: it comes back at a higher generation, same created_at.
        stubFeed(pipeline("other", 1, FIRST));
        syncService.sync();
        stubFeed(pipeline(NAME, 4, FIRST));
        syncService.sync();

        assertThat(storedCursor()).isEqualTo("c5");
        assertThat(groupCount()).isEqualTo(1);
    }

    @Test
    void pipelineRecreatedAcrossPollsStartsFromEmptyState() {
        stubFeed(pipeline(NAME, 2, FIRST));
        syncService.sync();
        seedOldState();
        pauseRegistry.pause(NAME, PauseOrigin.BREAKER, "dead-letter rate");
        double before = resets();

        stubFeed(pipeline("other", 1, FIRST));
        syncService.sync();
        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            stubFeed(pipeline(NAME, 2, SECOND));
            syncService.sync();

            assertThat(appender.messages()).anyMatch(m -> m != null && m.contains("Pipeline p was recreated")
                    && m.contains("1 group(s) deleted") && m.contains("4 queued work item(s) deleted")
                    && m.contains("2 backfill run(s) cancelled"));
        } finally {
            appender.detach(Level.INFO);
        }

        assertResetToSecond();
        assertThat(pauseRegistry.isPaused(NAME)).isFalse();
        assertThat(resets()).isEqualTo(before + 1);
    }

    /** The generation coincides — the case `generation` alone can never catch — and the reset still happens. */
    @Test
    void pipelineRecreatedWithinOnePollAtTheSameGenerationStartsFromEmptyState() {
        stubFeed(pipeline(NAME, 2, FIRST));
        syncService.sync();
        seedOldState();
        double before = resets();

        stubFeed(pipeline(NAME, 2, SECOND));
        syncService.sync();

        assertResetToSecond();
        assertThat(resets()).isEqualTo(before + 1);
    }

    @Test
    void operatorPauseSurvivesTheReset() {
        stubFeed(pipeline(NAME, 2, FIRST));
        syncService.sync();
        pauseRegistry.pause(NAME, PauseOrigin.OPERATOR, "investigating");

        stubFeed(pipeline(NAME, 2, SECOND));
        syncService.sync();

        assertThat(pauseRegistry.current(NAME)).get().extracting(PipelinePauseRegistry.Pause::origin)
                .isEqualTo(PauseOrigin.OPERATOR);
    }

    @Test
    void commitForTheOldPipelineIsRefusedAfterTheResetAndEnqueuesNothing() {
        stubFeed(pipeline(NAME, 2, FIRST));
        syncService.sync();
        stubFeed(pipeline(NAME, 2, SECOND));
        syncService.sync();

        assertThatThrownBy(() -> scanPageCommitter.commit(NAME, 2, FIRST, null, "c9", 1L,
                new ChunkBatch(List.of(chunk()), List.of(), TestPages.allWork(1))))
                .isInstanceOf(PipelineRecreatedException.class);

        assertThat(storedCursor()).isNull();
        assertThat(count("SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", EnrichChunkTask.TASK_NAME)).isZero();
    }

    @Test
    void commitAdvancesTheCursorWithoutTouchingTheRecordedPipeline() {
        stubFeed(pipeline(NAME, 2, FIRST));
        syncService.sync();

        cursorWriter.lockAndAdvance(NAME, FIRST, "c1", 3L);
        // A commit that runs under no known created_at still advances, and still leaves the recorded value alone.
        cursorWriter.lockAndAdvance(NAME, null, "c2", 3L);

        assertThat(storedCursor()).isEqualTo("c2");
        assertThat(storedCreatedAt()).isEqualTo(FIRST);
    }

    @Test
    void commitForPipelineNeverSyncedRecordsItsCreatedAt() {
        cursorWriter.lockAndAdvance(NAME, FIRST, "c1", 3L);

        assertThat(storedCursor()).isEqualTo("c1");
        assertThat(storedCreatedAt()).isEqualTo(FIRST);
    }

    /** The lock is only meaningful inside the commit's own transaction, so taking it outside one is a bug. */
    @Test
    void lockingTheCursorOutsideTransactionIsRefused() {
        assertThatThrownBy(() -> cursorWriter.lockOwned(NAME, FIRST)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void lockAndAdvanceForTheOldPipelineIsRefusedAfterTheReset() {
        stubFeed(pipeline(NAME, 2, FIRST));
        syncService.sync();
        stubFeed(pipeline(NAME, 2, SECOND));
        syncService.sync();

        assertThatThrownBy(() -> cursorWriter.lockAndAdvance(NAME, FIRST, "c9", 1L)).isInstanceOf(PipelineRecreatedException.class);

        assertThat(storedCursor()).isNull();
        assertThat(storedCreatedAt()).isEqualTo(SECOND);
    }

    @Test
    void groupCommitForTheOldPipelineIsRefusedAfterTheResetAndPersistsNothing() {
        stubFeed(pipeline(NAME, 2, FIRST));
        syncService.sync();
        stubFeed(pipeline(NAME, 2, SECOND));
        syncService.sync();
        GroupStateEntity mark = GroupStateEntity.builder()
                .pipelineName(NAME)
                .groupKey("chat-a")
                .groupVersion(100L)
                .lastActivityAt(Instant.parse("2026-07-01T10:00:00Z"))
                .build();
        DlqItemEntity deadLetter = DlqItemEntity.builder()
                .pipelineName(NAME)
                .grainKey("chat-bad")
                .pipelineGeneration(2L)
                .stage(DlqStage.INPUT_MAP.dbValue())
                .error("unparseable version")
                .build();

        assertThatThrownBy(() -> groupDirtyMarkCommitter.commit(NAME, FIRST, "c9", 1L,
                new GroupMarkBatch(List.of(mark), List.of(deadLetter), TestPages.workAndDeadLetters(1, 1))))
                .isInstanceOf(PipelineRecreatedException.class);

        assertThat(groupCount()).isZero();
        assertThat(count("SELECT count(*) FROM dlq_item")).isZero();
        assertThat(storedCursor()).isNull();
        assertThat(storedCreatedAt()).isEqualTo(SECOND);
    }

    /**
     * Two pods that read the same old created_at both try to reset; the second finds the row already handed over and
     * must not discard what the new pipeline has done since.
     */
    @Test
    void secondResetFromTheSameOldValueChangesNothing() {
        stubFeed(pipeline(NAME, 2, FIRST));
        syncService.sync();
        stubFeed(pipeline(NAME, 2, SECOND));
        syncService.sync();
        setCursor("c7");
        group();

        assertThat(stateReset.reset(NAME, FIRST, SECOND)).isEmpty();

        assertThat(storedCursor()).isEqualTo("c7");
        assertThat(groupCount()).isEqualTo(1);
        assertThat(storedCreatedAt()).isEqualTo(SECOND);
    }

    /**
     * Another pod reset the persisted state first (simulated by writing the new created_at straight onto the row):
     * this pod resets only what it holds in memory, and leaves the new pipeline's progress alone.
     */
    @Test
    void persistedStateResetElsewhereResetsOnlyTheInMemoryState() {
        stubFeed(pipeline(NAME, 2, FIRST));
        syncService.sync();
        pauseRegistry.pause(NAME, PauseOrigin.BREAKER, "dead-letter rate");
        jdbcTemplate.update("UPDATE pipeline_cursor SET pipeline_created_at = ?, cursor = 'c7' WHERE pipeline_name = ?",
                Timestamp.from(SECOND), NAME);
        group();
        double before = resets();

        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            stubFeed(pipeline(NAME, 2, SECOND));
            syncService.sync();

            assertThat(appender.messages()).anyMatch(m -> m != null && m.contains("Pipeline p was recreated")
                    && m.contains("persisted state already reset"));
        } finally {
            appender.detach(Level.INFO);
        }

        assertThat(storedCursor()).isEqualTo("c7");
        assertThat(groupCount()).isEqualTo(1);
        assertThat(resets()).isEqualTo(before);
        assertThat(pauseRegistry.isPaused(NAME)).isFalse();
    }

    /**
     * A row from before the column, last written before the pipeline now under its name existed: it is a deleted
     * predecessor's, left unadopted because it was not in the feed at the first sync after deploy, and is reset.
     */
    @Test
    void rowFromBeforeTheColumnThatPredatesThePipelineIsReset() {
        jdbcTemplate.update("INSERT INTO pipeline_cursor (pipeline_name, cursor, updated_at) VALUES (?, 'c5', ?)", NAME,
                Timestamp.from(SECOND.minus(Duration.ofDays(1))));
        group();
        double before = resets();
        stubFeed(pipeline(NAME, 2, SECOND));

        syncService.sync();

        assertThat(storedCursor()).isNull();
        assertThat(groupCount()).isZero();
        assertThat(storedCreatedAt()).isEqualTo(SECOND);
        assertThat(resets()).isEqualTo(before + 1);
    }

    /** ADAS may serve nanoseconds; the stored microsecond value must still compare equal on every later poll. */
    @Test
    void createdAtWithNanosecondsDoesNotResetOnTheNextPoll() {
        Instant nanos = Instant.parse("2026-10-06T09:30:00.123456789Z");
        double before = resets();
        stubFeed(pipeline(NAME, 2, nanos));
        syncService.sync();
        setCursor("c5");
        group();

        syncService.sync();
        syncService.sync();

        assertThat(storedCreatedAt()).isEqualTo(Instant.parse("2026-10-06T09:30:00.123456Z"));
        assertThat(storedCursor()).isEqualTo("c5");
        assertThat(groupCount()).isEqualTo(1);
        assertThat(resets()).isEqualTo(before);
    }

    /**
     * Seeds what the deleted pipeline left: a cursor, a computed group, four unpicked work items (a chunk, a group
     * item, a write and a backfill chunk of one of its runs), one picked item, a running and a paused run, and another
     * pipeline's queued item that must survive. Out of scope and kept: a finished run of the deleted pipeline, and
     * another pipeline's running backfill with a queued chunk.
     */
    private void seedOldState() {
        setCursor("c5");
        group();
        run(NAME, "run-running", "running");
        run(NAME, "run-paused", "paused");
        run(NAME, "run-done", "done");
        run("other", "other-run", "running");
        task(EnrichChunkTask.TASK_NAME, NAME + "#aaa#c0", false);
        task(GroupChunkTask.TASK_NAME, NAME + "#g#bbb", false);
        task(UpsertRowsTask.TASK_NAME, NAME + "#aaa#c1#w", false);
        task(EnrichChunkTask.TASK_NAME, "#bf#run-paused#ccc#c0", false);
        task(EnrichChunkTask.TASK_NAME, NAME + "#aaa#c2", true);
        task(EnrichChunkTask.TASK_NAME, "other#ddd#c0", false);
        task(EnrichChunkTask.TASK_NAME, "#bf#other-run#eee#c0", false);
    }

    /** The state reset to the recreated pipeline, created at {@link #SECOND}. */
    private void assertResetToSecond() {
        assertThat(storedCreatedAt()).isEqualTo(SECOND);
        assertThat(storedCursor()).isNull();
        assertThat(groupCount()).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT task_instance FROM scheduled_tasks WHERE task_name IN (?, ?, ?)",
                String.class, EnrichChunkTask.TASK_NAME, GroupChunkTask.TASK_NAME, UpsertRowsTask.TASK_NAME))
                .containsExactlyInAnyOrder(NAME + "#aaa#c2", "other#ddd#c0", "#bf#other-run#eee#c0");
        assertThat(jdbcTemplate.queryForList("SELECT state FROM run_history WHERE pipeline_name = ?", String.class, NAME))
                .containsExactlyInAnyOrder("cancelled", "cancelled", "done");
        assertThat(jdbcTemplate.queryForList("SELECT state FROM run_history WHERE pipeline_name = ?", String.class, "other"))
                .containsExactly("running");
    }

    private void stubFeed(String pipelineObjects) {
        ADAS.stubFor(get(urlPathEqualTo("/v1/pipelines"))
                .withQueryParam("kind", equalTo("enrich"))
                .withQueryParam("enabled", equalTo("true"))
                .withQueryParam("view", equalTo("compiled"))
                .willReturn(okJson("{\"pipelines\":[" + pipelineObjects + "]}")));
    }

    private static String pipeline(String name, long generation, Instant createdAt) {
        return """
                {
                  "name":"%s","kind":"enrich",
                  "transform":{"type":"llm","preset":"chat_completion","model":"gpt-4o",
                    "request_template":"{\\"messages\\":[{\\"role\\":\\"user\\",\\"content\\":\\"{{prompt}}\\"}]}",
                    "inputs":{"prompt":{"column":"request_body"}}},
                  "response_schema":{"type":"object","additionalProperties":false,"properties":{"label":{"type":"string"}}},
                  "target":"enr_%s","inputs":["dial_usage_log"],"grain_key":"event_id",
                  "trigger":{"kind":"schedule","cron":"10 * * * * *"},
                  "outputs":[{"name":"label","column":"label"}],
                  "enabled":true,"generation":%d,
                  "created_at":"%s","updated_at":"%s"
                }
                """.formatted(name, name, generation, createdAt, createdAt);
    }

    private static ChunkPayload chunk() {
        return new ChunkPayload(NAME, 2, List.of(new ChunkRow("e0", Map.of("prompt", "hi"))));
    }

    private void setCursor(String cursor) {
        jdbcTemplate.update("UPDATE pipeline_cursor SET cursor = ? WHERE pipeline_name = ?", cursor, NAME);
    }

    private void group() {
        jdbcTemplate.update("""
                INSERT INTO group_state (pipeline_name, group_key, group_version, last_activity_at, computed_version, computed_at)
                VALUES (?, 'g1', 1, now(), 1, now())
                """, NAME);
    }

    private void run(String pipelineName, String id, String state) {
        jdbcTemplate.update("""
                        INSERT INTO run_history (id, pipeline_name, kind, state, started_at, paused_reason, paused_at)
                        VALUES (?, ?, 'backfill', ?, now(), ?, ?)
                        """, id, pipelineName, state, "paused".equals(state) ? "pipeline_absent" : null,
                "paused".equals(state) ? Timestamp.from(Instant.now()) : null);
    }

    private void task(String taskName, String instance, boolean picked) {
        jdbcTemplate.update("""
                INSERT INTO scheduled_tasks (task_name, task_instance, execution_time, picked, version)
                VALUES (?, ?, now(), ?, 1)
                """, taskName, instance, picked);
    }

    private Instant storedCreatedAt() {
        Timestamp value = jdbcTemplate.queryForObject(
                "SELECT pipeline_created_at FROM pipeline_cursor WHERE pipeline_name = ?", Timestamp.class, NAME);
        return value == null ? null : value.toInstant();
    }

    private String storedCursor() {
        return jdbcTemplate.queryForObject("SELECT cursor FROM pipeline_cursor WHERE pipeline_name = ?", String.class, NAME);
    }

    private long groupCount() {
        return count("SELECT count(*) FROM group_state WHERE pipeline_name = ?", NAME);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private double resets() {
        return meterRegistry.get("runner.pipeline.state.resets").counter().count();
    }
}
