package com.epam.aidial.enrichment.worker.group;

import com.epam.aidial.enrichment.adas.dto.OutputDto;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ReadyWhenDto;
import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.persistence.GroupStateWriter;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.CapturingAppender;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.support.UpsertDrain;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.github.tomakehurst.wiremock.WireMockServer;
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
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A2 exit-criteria test: a group work item assembles its members through ADAS, makes one model call, writes
 * one enrichment row keyed by the group key with the full provenance set, and stamps the group clean. Also
 * covers each failure branch. ADAS and DIAL are stubbed on one WireMock server (different paths); the
 * runner's PostgreSQL runs in Testcontainers.
 */
@SpringBootTest
class GroupWorkerIntegrationTest {

    private static final WireMockServer BACKEND = AdasWireMock.server();
    private static final String PIPELINE_NAME = "r-groupworker";
    private static final String GROUP_KEY = "chat-a";
    private static final long GROUP_VERSION = 1_782_400_795_816L;
    private static final String FETCH_PATH = "/v1/pipelines/" + PIPELINE_NAME + "/group-rows";
    private static final String ROWS_PATH = "/v1/pipelines/" + PIPELINE_NAME + "/rows";
    private static final String CHAT_PATH = "/openai/deployments/gpt-4o/chat/completions";

    @Autowired
    private GroupWorkerService worker;

    @Autowired
    private PipelineCache pipelineCache;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GroupStateWriter groupStateWriter;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private com.epam.aidial.enrichment.sweep.GroupReadinessSweep sweep;

    @Autowired
    private com.github.kagkarlsson.scheduler.Scheduler scheduler;

    @Autowired
    private com.epam.aidial.enrichment.worker.upsert.UpsertService upsertService;

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
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
        registry.add("group.sweep-initial-delay", () -> "3600s");
        registry.add("group.sweep-interval", () -> "3600s");
    }

    @BeforeEach
    void reset() {
        BACKEND.resetAll();
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, group_state, dlq_item, spend_counter");
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, groupPipeline()));
        seedDirtyGroup();
    }

    @Test
    void groupBecomesOneRowWithProvenanceAndIsStampedClean() {
        stubMembers(false, "hi there", "any update?");
        stubDial("{\"topic\":\"billing\"}");
        stubRows();

        worker.process("item-1", payload());
        drainWrites();

        Map<String, Object> row = upsertedRow();
        assertThat(row).containsEntry("chat_id", GROUP_KEY);
        assertThat(row).containsEntry("topic", "billing");
        assertThat(row).containsEntry("pipeline_generation", 3);
        assertThat(row).containsEntry("model", "gpt-4o");
        assertThat(row).containsKey("enriched_at");
        assertThat(row).containsEntry("group_version", GROUP_VERSION);
        assertThat(row).containsEntry("truncated", false);

        // Exactly one model call for the whole group, and the group is now clean.
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).hasSize(1);
        assertThat(computedVersion()).isEqualTo(GROUP_VERSION);
        assertThat(computedTruncated()).isFalse();
    }

    @Test
    void membersReachThePromptAsOneJsonArray() {
        stubMembers(false, "hi there", "any update?");
        stubDial("{\"topic\":\"billing\"}");
        stubRows();

        worker.process("item-1", payload());

        String prompt = BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH))).getFirst()
                .getBodyAsString();
        // Both members, correlated with their own fields, in one interpolated array.
        assertThat(prompt).contains("\\\"text\\\":\\\"hi there\\\"");
        assertThat(prompt).contains("\\\"text\\\":\\\"any update?\\\"");
    }

    @Test
    void adasTruncationIsRecordedOnTheRowAndInState() {
        stubMembers(true, "hi there");
        stubDial("{\"topic\":\"billing\"}");
        stubRows();

        worker.process("item-1", payload());
        drainWrites();

        assertThat(upsertedRow()).containsEntry("truncated", true);
        assertThat(computedTruncated()).isTrue();
    }

    /**
     * The whole point of the notice: a trimmed group's prompt says it is trimmed. Without it the model
     * summarizes a suffix of the conversation as if it were the whole thing, and the row's {@code truncated}
     * provenance only tells a later reader that — after the judgement was already formed.
     */
    @Test
    void truncationIsAnnouncedInThePrompt() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, withNoticeInTemplate()));
        stubMembers(true, "hi there");
        stubDial("{\"topic\":\"billing\"}");
        stubRows();

        worker.process("item-1", payload());

        String prompt = BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH))).getFirst()
                .getBodyAsString();
        assertThat(prompt).contains("partial view of the group");
    }

    @Test
    void completeGroupsPromptCarriesNoCaveat() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, withNoticeInTemplate()));
        stubMembers(false, "hi there");
        stubDial("{\"topic\":\"billing\"}");
        stubRows();

        worker.process("item-1", payload());

        String prompt = BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH))).getFirst()
                .getBodyAsString();
        assertThat(prompt).doesNotContain("partial view");
    }

    /**
     * The out-of-order completion race: two versions of one group can be in flight at once (the sweep
     * enqueues a new version while the previous item is still running), and if they complete out of order the
     * older one's stamp is refused. Its model call was still spent, so the charge must land anyway — otherwise
     * the ceiling under-counts exactly the double spend it exists to bound.
     */
    @Test
    void chargeSurvivesAnOutOfOrderCompletion() {
        Instant now = Instant.parse("2026-07-01T12:00:00Z");
        LocalDate today = LocalDate.parse("2026-07-01");
        long newer = GROUP_VERSION + 1_000;

        GroupStateWriter.ChargeOutcome first =
                groupStateWriter.stampComputedAndCharge(PIPELINE_NAME, GROUP_KEY, newer, now, false, today);
        GroupStateWriter.ChargeOutcome late =
                groupStateWriter.stampComputedAndCharge(PIPELINE_NAME, GROUP_KEY, GROUP_VERSION, now, false, today);

        assertThat(first.stamped()).isTrue();
        assertThat(late.stamped()).isFalse();
        assertThat(late.superseded()).isTrue();
        assertThat(late.charged()).isTrue();
        // The newer label stands...
        assertThat(computedVersion()).isEqualTo(newer);
        // ...and both model calls are charged.
        assertThat(evaluations()).isEqualTo(2);
    }

    /**
     * Re-evaluating an <em>unchanged</em> group is not the race above, and must not be reported as one. The
     * per-group requeue override builds its item at the group's current version, which for a clean group is
     * exactly {@code computed_version} — so this is the ordinary shape of an operator re-drive, not two
     * versions completing out of order.
     *
     * <p>What the stamp has to do here is re-apply: {@code computed_at} and {@code computed_truncated} describe
     * the evaluation that just ran, and {@code ready_when.max_staleness} is measured against
     * {@code computed_at}, so refusing the stamp left a freshly-recomputed group looking as stale as before.
     */
    @Test
    void reEvaluationAtTheSameVersionRestampsAndIsNotSuperseded() {
        LocalDate today = LocalDate.parse("2026-07-01");
        Instant first = Instant.parse("2026-07-01T12:00:00Z");
        Instant redrive = Instant.parse("2026-07-01T12:30:00Z");

        groupStateWriter.stampComputedAndCharge(PIPELINE_NAME, GROUP_KEY, GROUP_VERSION, first, true, today);
        GroupStateWriter.ChargeOutcome again = groupStateWriter.stampComputedAndCharge(
                PIPELINE_NAME, GROUP_KEY, GROUP_VERSION, redrive, false, today);

        assertThat(again.stamped()).isTrue();
        assertThat(again.superseded()).isFalse();
        assertThat(again.charged()).isTrue();
        // The re-drive's own outcome is what the state describes now — not the previous evaluation's.
        assertThat(computedAt()).isEqualTo(redrive);
        assertThat(computedTruncated()).isFalse();
        assertThat(computedVersion()).isEqualTo(GROUP_VERSION);
        // Still charged like any other evaluation, so the ceiling keeps bounding a re-driven group.
        assertThat(evaluations()).isEqualTo(2);
    }

    /**
     * A stamp for a group whose state row was evicted between the evaluation and the write is not superseded
     * either: nothing newer exists to have superseded it. The charge is what tells the two apart, since it
     * carries no version guard and so lands iff the row is there.
     */
    @Test
    void stampForAnEvictedGroupIsNotReportedAsSuperseded() {
        jdbcTemplate.update("DELETE FROM group_state WHERE pipeline_name = ? AND group_key = ?", PIPELINE_NAME, GROUP_KEY);

        GroupStateWriter.ChargeOutcome outcome = groupStateWriter.stampComputedAndCharge(PIPELINE_NAME, GROUP_KEY,
                GROUP_VERSION, Instant.parse("2026-07-01T12:00:00Z"), false, LocalDate.parse("2026-07-01"));

        assertThat(outcome.charged()).isFalse();
        assertThat(outcome.stamped()).isFalse();
        assertThat(outcome.superseded()).isFalse();
    }

    @Test
    void emptyGroupWritesNothingAndStampsClean() {
        BACKEND.stubFor(post(urlPathEqualTo(FETCH_PATH))
                .willReturn(okJson("{\"rows\":[],\"truncated\":false}")));

        worker.process("item-1", payload());

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROWS_PATH)))).isEmpty();
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).isEmpty();
        assertThat(dlqCount()).isZero();
        assertThat(computedVersion()).isEqualTo(GROUP_VERSION);
    }

    @Test
    void transientFetchFailureRetriesWithStateUntouched() {
        BACKEND.stubFor(post(urlPathEqualTo(FETCH_PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> worker.process("item-1", payload())).isInstanceOf(RuntimeException.class);

        assertThat(dlqCount()).isZero();
        assertThat(computedVersion()).isNull();
    }

    @Test
    void nonRetryableFetchRejectionDeadLettersAtGroupFetch() {
        BACKEND.stubFor(post(urlPathEqualTo(FETCH_PATH)).willReturn(aResponse().withStatus(422)));

        worker.process("item-1", payload());

        assertThat(dlqStage()).isEqualTo(DlqStage.GROUP_FETCH.dbValue());
        // Stamped so the sweep stops re-enqueuing a group ADAS will keep rejecting.
        assertThat(computedVersion()).isEqualTo(GROUP_VERSION);
        // Labelled with the task it came from rather than defaulting to enrich-chunk, which is what a requeue
        // would otherwise deserialize it as.
        assertThat(dlqTaskName()).isEqualTo(GroupChunkTask.TASK_NAME);
    }

    @Test
    void groupDeadLetteredTwiceKeepsOneRow() {
        BACKEND.stubFor(post(urlPathEqualTo(FETCH_PATH)).willReturn(aResponse().withStatus(422)));

        worker.process("item-1", payload());
        // A group can reach the same deterministic failure again — after a new member re-dirties it, say. The
        // second dead-letter collides with dlq_item's partial unique key, and as a plain insert it threw.
        worker.process("item-1", payload());

        assertThat(dlqCount()).isEqualTo(1);
    }

    @Test
    void schemaInvalidResponseDeadLettersAtValidate() {
        stubMembers(false, "hi there");
        stubDial("{\"wrong_field\":1}");
        stubRows();

        worker.process("item-1", payload());

        assertThat(dlqStage()).isEqualTo(DlqStage.VALIDATE.dbValue());
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROWS_PATH)))).isEmpty();
    }

    @Test
    void provenanceIsSentEvenThoughAdasMayDropIt() {
        // ADAS silently drops a provenance column the target lacks, so the runner always sends all five.
        stubMembers(false, "hi there");
        stubDial("{\"topic\":\"billing\"}");
        stubRows();

        worker.process("item-1", payload());
        drainWrites();

        assertThat(upsertedRow()).containsKeys("pipeline_generation", "model", "enriched_at",
                "group_version", "truncated");

        // And the retired spelling on none of them. The group lane has its own payload builder, so the row
        // lane's assertion does not cover it — and one missed builder fails every write it makes, because
        // ADAS refuses a row naming `evaluator_version` with HTTP 422 rather than dropping it.
        assertThat(BACKEND.getAllServeEvents().stream()
                .map(event -> event.getRequest().getBodyAsString())
                .filter(body -> body != null && !body.isBlank())
                .toList())
                .noneMatch(body -> body.contains("evaluator_version"));
    }

    @Test
    void evaluatingUpToTheCeilingStopsTheGroupBeingSweptWhileOthersContinue() {
        // The round trip the ceiling exists for: the worker charges an evaluation, and the next sweep pass
        // declines that group while the pipeline's other groups are still enqueued.
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, withCeilingOfOne()));
        seedDirtyGroup("chat-b");
        stubMembers(false, "hi there");
        stubDial("{\"topic\":\"billing\"}");
        stubRows();

        worker.process("item-1", payload());
        drainWrites();

        sweep.sweep();

        // chat-a is charged to its ceiling of 1; chat-b has never been evaluated.
        assertThat(enqueuedGroupInstanceIds()).containsExactly(
                DeterministicId.groupInstanceId(PIPELINE_NAME, 3, "chat-b", GROUP_VERSION));
    }

    /** Ready after {@code 1s} idle, with a daily evaluation ceiling of 1. */
    private static PipelineDto withCeilingOfOne() {
        return TestPipelines.builder(PIPELINE_NAME)
                .generation(3)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .grainKey("chat_id")
                .readyWhen(new ReadyWhenDto(null, "1s", null, 1))
                .model("gpt-4o")
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"Summarize: {{members}}\"}]}")
                .responseSchema(Map.of(
                        "type", "object",
                        "required", List.of("topic"),
                        "properties", Map.of("topic", Map.of("type", "string"))))
                .transformInputs(TestPipelines.columnVar("text", "request_body"))
                .outputs(List.of(new OutputDto("topic", "topic", null)))
                .build();
    }

    private List<String> enqueuedGroupInstanceIds() {
        return jdbcTemplate.queryForList(
                "SELECT task_instance FROM scheduled_tasks WHERE task_name = ?", String.class,
                GroupChunkTask.TASK_NAME);
    }

    @Test
    void memberContentNeverReachesTheLogsOnTheSuccessPath() {
        // A group's members are the largest and most sensitive payload the runner handles. The pipeline is that
        // they are never logged at ANY level, so the capture lowers the root logger to TRACE.
        String secret = "patient-record-4417-confidential";
        stubMembers(false, secret);
        stubDial("{\"topic\":\"billing\"}");
        stubRows();

        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            worker.process("item-1", payload());
            assertThat(appender.containsText(secret))
                    .as("member content must not appear in any log record: %s", appender.messages())
                    .isFalse();
        } finally {
            appender.detach(Level.INFO);
        }
    }

    @Test
    void memberContentNeverReachesTheLogsOnTheFailurePath() {
        // The dead-letter path is where a payload is most tempting to log "for diagnosis".
        String secret = "patient-record-9911-confidential";
        stubMembers(false, secret);
        stubDial("{\"wrong_field\":1}");   // fails its schema -> dead-lettered at validate
        stubRows();

        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            worker.process("item-1", payload());
            assertThat(dlqStage()).isEqualTo(DlqStage.VALIDATE.dbValue());
            assertThat(appender.containsText(secret))
                    .as("member content must not appear in any log record: %s", appender.messages())
                    .isFalse();
        } finally {
            appender.detach(Level.INFO);
        }
    }

    @Test
    void serializedGroupPayloadCarriesNoMemberContent() {
        // The payload is keys-only so member bodies never reach scheduled_tasks, which has no retention bound.
        String serialized = jsonMapper.writeValueAsString(payload());

        assertThat(serialized).contains(GROUP_KEY).contains(PIPELINE_NAME);
        assertThat(serialized).doesNotContain("request_body").doesNotContain("members");
    }

    /**
     * The same pipeline with the truncation caveat interpolated into its prompt. {@code members_notice} is empty
     * when the input is complete, which is what lets a template include it unconditionally — the renderer has
     * no conditionals.
     */
    private static PipelineDto withNoticeInTemplate() {
        return TestPipelines.builder(PIPELINE_NAME)
                .generation(3)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .grainKey("chat_id")
                .readyWhen(new ReadyWhenDto(null, "10m", null, null))
                .model("gpt-4o")
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":"
                        + "\"{{members_notice}} Summarize: {{members}}\"}]}")
                .responseSchema(Map.of(
                        "type", "object",
                        "required", List.of("topic"),
                        "properties", Map.of("topic", Map.of("type", "string"))))
                .transformInputs(TestPipelines.columnVar("text", "request_body"))
                .outputs(List.of(new OutputDto("topic", "topic", null)))
                .build();
    }

    private static PipelineDto groupPipeline() {
        return TestPipelines.builder(PIPELINE_NAME)
                .generation(3)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .grainKey("chat_id")
                .readyWhen(new ReadyWhenDto(null, "10m", null, null))
                .model("gpt-4o")
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"Summarize: {{members}}\"}]}")
                .responseSchema(Map.of(
                        "type", "object",
                        "required", List.of("topic"),
                        "properties", Map.of("topic", Map.of("type", "string"))))
                .transformInputs(TestPipelines.columnVar("text", "request_body"))
                .outputs(List.of(new OutputDto("topic", "topic", null)))
                .build();
    }

    private void seedDirtyGroup() {
        seedDirtyGroup(GROUP_KEY);
    }

    private void seedDirtyGroup(String groupKey) {
        jdbcTemplate.update("""
                INSERT INTO group_state (pipeline_name, group_key, group_version, last_activity_at)
                VALUES (?, ?, ?, ?)
                """, PIPELINE_NAME, groupKey, GROUP_VERSION, Timestamp.from(Instant.parse("2026-07-01T10:00:00Z")));
    }

    private static GroupPayload payload() {
        return new GroupPayload(PIPELINE_NAME, 3, GROUP_KEY, GROUP_VERSION);
    }

    private void drainWrites() {
        new UpsertDrain(scheduler, upsertService).drain();
    }

    private void stubMembers(boolean truncated, String... bodies) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < bodies.length; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("{\"chat_id\":\"%s\",\"request_body\":\"%s\"}".formatted(GROUP_KEY, bodies[i]));
        }
        BACKEND.stubFor(post(urlPathEqualTo(FETCH_PATH)).willReturn(okJson(
                "{\"rows\":[%s],\"truncated\":%s}".formatted(rows, truncated))));
    }

    private void stubDial(String contentJson) {
        String body = jsonMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", contentJson))),
                "usage", Map.of("total_tokens", 12)));
        BACKEND.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(okJson(body)));
    }

    private void stubRows() {
        BACKEND.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(okJson("{\"inserted\":1}")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> upsertedRow() {
        var requests = BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROWS_PATH)));
        assertThat(requests).hasSize(1);
        Map<String, Object> body = jsonMapper.readValue(requests.getFirst().getBodyAsString(), Map.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private Long computedVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT computed_version FROM group_state WHERE pipeline_name = ? AND group_key = ?",
                Long.class, PIPELINE_NAME, GROUP_KEY);
    }

    private int evaluations() {
        Integer evaluations = jdbcTemplate.queryForObject(
                "SELECT evaluations FROM group_state WHERE pipeline_name = ? AND group_key = ?",
                Integer.class, PIPELINE_NAME, GROUP_KEY);
        return evaluations == null ? 0 : evaluations;
    }

    private Instant computedAt() {
        Timestamp computedAt = jdbcTemplate.queryForObject(
                "SELECT computed_at FROM group_state WHERE pipeline_name = ? AND group_key = ?",
                Timestamp.class, PIPELINE_NAME, GROUP_KEY);
        return computedAt == null ? null : computedAt.toInstant();
    }

    private boolean computedTruncated() {
        Boolean truncated = jdbcTemplate.queryForObject(
                "SELECT computed_truncated FROM group_state WHERE pipeline_name = ? AND group_key = ?",
                Boolean.class, PIPELINE_NAME, GROUP_KEY);
        return Boolean.TRUE.equals(truncated);
    }

    private String dlqStage() {
        return jdbcTemplate.queryForObject("SELECT stage FROM dlq_item WHERE pipeline_name = ?", String.class,
                PIPELINE_NAME);
    }

    private String dlqTaskName() {
        return jdbcTemplate.queryForObject("SELECT task_name FROM dlq_item WHERE pipeline_name = ?", String.class,
                PIPELINE_NAME);
    }

    private long dlqCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM dlq_item", Long.class);
        return count == null ? 0 : count;
    }
}
