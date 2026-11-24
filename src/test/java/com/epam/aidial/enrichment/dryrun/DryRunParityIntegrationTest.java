package com.epam.aidial.enrichment.dryrun;

import com.epam.aidial.enrichment.adas.dto.OutputDto;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ReadyWhenDto;
import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.adas.dto.VarDto;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.scan.InputMapper;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.support.UpsertDrain;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.ChunkPayload;
import com.epam.aidial.enrichment.worker.ChunkRow;
import com.epam.aidial.enrichment.worker.WorkerService;
import com.epam.aidial.enrichment.worker.group.GroupPayload;
import com.epam.aidial.enrichment.worker.group.GroupWorkerService;
import com.epam.aidial.enrichment.worker.upsert.UpsertService;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dry run predicts the worker: one pipeline and one input, evaluated once by a worker and once by
 * {@code POST /v1/pipelines/{name}/try} against the same stubbed DIAL, send byte-for-byte the same JSON request bodies
 * and arrive at the same outcome — the same mapped output columns on success, the same reply on a validation failure.
 * Row lane (chat completion and application route) and group lane. DIAL and ADAS are stubbed on one WireMock server;
 * PostgreSQL runs in Testcontainers.
 *
 * <p>The worker's chunk carries inputs already mapped at scan time, so the test maps the raw row through the same
 * {@link InputMapper} the scan's chunk builder uses, while the dry run is handed the raw row as ADAS's trial input
 * delivers it. The group worker fetches its members through {@code /group-rows}; the dry run receives the same
 * members through the trial input.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DryRunParityIntegrationTest {

    private static final WireMockServer BACKEND = AdasWireMock.server();
    private static final String PIPELINE = "dr-parity";
    private static final long GENERATION = 3;
    private static final String CHAT_PATH = "/openai/deployments/gpt-4o/chat/completions";
    private static final String ROUTE_PATH = "/v1/deployments/insights-app/route/v1/insights";
    private static final String ROWS_PATH = "/v1/pipelines/" + PIPELINE + "/rows";
    private static final String FETCH_PATH = "/v1/pipelines/" + PIPELINE + "/group-rows";
    private static final String TRIAL_PATH = "/v1/pipelines/" + PIPELINE + "/trial-input";
    private static final String GROUP_KEY = "chat-a";
    private static final long GROUP_VERSION = 1_782_400_795_816L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkerService workerService;

    @Autowired
    private GroupWorkerService groupWorkerService;

    @Autowired
    private InputMapper inputMapper;

    @Autowired
    private PipelineCache pipelineCache;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private UpsertService upsertService;

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
        jdbcTemplate.execute("TRUNCATE dlq_item, scheduled_tasks, spend_counter, group_state");
        BACKEND.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(okJson("{\"inserted\":1}")));
    }

    // --- the cases --------------------------------------------------------------------------------------------------

    @Test
    void rowChatCompletionSendsTheWorkersRequestAndMapsTheWorkersOutputs() throws Exception {
        PipelineDto pipeline = rowChatPipeline();
        Map<String, Object> row = row("00000000-0000-4000-8000-000000000001", "great product, \"quoted\" and\nmultiline");
        stubChat("{\"label\":\"positive\",\"score\":7}");

        Worker worker = runRowWorker(pipeline, row);
        DryRun dryRun = runDryRun(pipeline, List.of(row), null, null);

        assertSameRequests(worker.requests(CHAT_PATH), dryRun.requests(CHAT_PATH), 1);
        assertThat(dryRun.status()).isEqualTo("ok");
        assertThat(worker.upsertedRow()).containsAllEntriesOf(dryRun.outputs());
        assertThat(dryRun.outputs()).containsOnlyKeys("sentiment", "score_band");
    }

    @Test
    void rowAppRouteSendsTheWorkersRequestAndMapsTheWorkersOutputs() throws Exception {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE)
                .generation(GENERATION)
                .model("insights-app")
                .appRoute("v1/insights")
                .requestTemplate("{\"text\": {{prompt:json}}, \"tags\": {{tags:json}}}")
                .params(Map.of("temperature", 0))
                .transformInputs(Map.of(
                        "prompt", new VarDto("body", null),
                        "tags", new VarDto(null, "[$lowercase(deployment), \"fixed\"]")))
                .responseSchema(Map.of("type", "object", "required", List.of("label"), "additionalProperties", false,
                        "properties", Map.of("label", Map.of("type", "string"))))
                .outputs(List.of(new OutputDto("label", "sentiment", null, "result.label")))
                .build();
        Map<String, Object> row = row("00000000-0000-4000-8000-000000000002", "slow delivery");
        BACKEND.stubFor(post(urlPathEqualTo(ROUTE_PATH)).willReturn(okJson("{\"result\":{\"label\":\"negative\"}}")));

        Worker worker = runRowWorker(pipeline, row);
        DryRun dryRun = runDryRun(pipeline, List.of(row), null, null);

        assertSameRequests(worker.requests(ROUTE_PATH), dryRun.requests(ROUTE_PATH), 1);
        assertThat(dryRun.status()).isEqualTo("ok");
        assertThat(worker.upsertedRow()).containsAllEntriesOf(dryRun.outputs());
        assertThat(dryRun.outputs()).containsEntry("sentiment", "negative");
    }

    @Test
    void groupSendsTheWorkersRequestAndMapsTheWorkersOutputs() throws Exception {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE)
                .generation(GENERATION)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .readyWhen(new ReadyWhenDto(null, "10m", null, null))
                .grainKey("chat_id")
                // A budget small enough to cut the oldest members, so the parity covers the trimming and the notice.
                .inputTokenBudget(20)
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":{{members:json}}},"
                        + "{\"role\":\"system\",\"content\":\"{{members_notice}} ({{members_included}} included)\"}]}")
                .transformInputs(Map.of("text", new VarDto("body", null)))
                .responseSchema(Map.of("type", "object", "required", List.of("topic"), "additionalProperties", false,
                        "properties", Map.of("topic", Map.of("type", "string"))))
                .outputs(List.of(new OutputDto("topic", "topic", null)))
                .build();
        List<Map<String, Object>> members = List.of(
                member("first message of the conversation, long enough to be trimmed away by the budget"),
                member("second message"),
                member("third message, the newest"));
        stubChat("{\"topic\":\"billing\"}");

        Worker worker = runGroupWorker(pipeline, members);
        DryRun dryRun = runDryRun(pipeline, members, false, GROUP_KEY);

        assertSameRequests(worker.requests(CHAT_PATH), dryRun.requests(CHAT_PATH), 1);
        assertThat(dryRun.status()).isEqualTo("ok");
        assertThat(worker.upsertedRow()).containsAllEntriesOf(dryRun.outputs());
        assertThat((Integer) JsonPath.read(dryRun.body(), "$.evaluations[0].members_omitted")).isPositive();
    }

    @Test
    void correctiveRetrySendsTheWorkersTwoRequestsAndReportsTheReplyTheWorkerDeadLetters() throws Exception {
        PipelineDto pipeline = rowChatPipeline();
        Map<String, Object> row = row("00000000-0000-4000-8000-000000000003", "hello");
        stubChat("{\"unexpected\":true}");

        Worker worker = runRowWorker(pipeline, row);
        DryRun dryRun = runDryRun(pipeline, List.of(row), null, null);

        assertSameRequests(worker.requests(CHAT_PATH), dryRun.requests(CHAT_PATH), 2);
        String deadLettered = jdbcTemplate.queryForObject(
                "SELECT raw_response FROM dlq_item WHERE stage = 'validate'", String.class);
        assertThat(dryRun.status()).isEqualTo("failed");
        assertThat((String) JsonPath.read(dryRun.body(), "$.evaluations[0].stage")).isEqualTo("validate");
        assertThat((String) JsonPath.read(dryRun.body(), "$.evaluations[0].response")).isEqualTo(deadLettered);
    }

    // --- running each side ----------------------------------------------------------------------------------------

    /** What a worker sent and wrote. */
    private record Worker(List<LoggedRequest> calls, Map<String, Object> upsertedRow) {

        List<LoggedRequest> requests(String path) {
            return calls.stream().filter(call -> call.getUrl().startsWith(path)).toList();
        }
    }

    /** What a dry run sent and answered. */
    private record DryRun(List<LoggedRequest> calls, String body) {

        List<LoggedRequest> requests(String path) {
            return calls.stream().filter(call -> call.getUrl().startsWith(path)).toList();
        }

        String status() {
            return JsonPath.read(body, "$.evaluations[0].status");
        }

        Map<String, Object> outputs() {
            Map<String, Object> outputs = JsonPath.read(body, "$.evaluations[0].outputs");
            return outputs;
        }
    }

    private Worker runRowWorker(PipelineDto pipeline, Map<String, Object> row) {
        pipelineCache.replaceAll(Map.of(PIPELINE, pipeline));
        // What the scan's chunk builder puts on the work item: the row's grain key and its mapped inputs.
        Map<String, Object> inputs = inputMapper.map(pipeline, row);
        workerService.process("chunk-1", new ChunkPayload(PIPELINE, GENERATION,
                List.of(new ChunkRow((String) row.get(pipeline.grainKey()), inputs))));
        return finishWorker();
    }

    private Worker runGroupWorker(PipelineDto pipeline, List<Map<String, Object>> members) {
        pipelineCache.replaceAll(Map.of(PIPELINE, pipeline));
        jdbcTemplate.update("INSERT INTO group_state (pipeline_name, group_key, group_version, last_activity_at) "
                + "VALUES (?, ?, ?, ?)", PIPELINE, GROUP_KEY, GROUP_VERSION, Timestamp.from(Instant.parse("2026-07-01T10:00:00Z")));
        BACKEND.stubFor(post(urlPathEqualTo(FETCH_PATH)).willReturn(okJson(jsonMapper.writeValueAsString(
                Map.of("rows", members, "truncated", false)))));
        groupWorkerService.process("item-1", new GroupPayload(PIPELINE, GENERATION, GROUP_KEY, GROUP_VERSION));
        return finishWorker();
    }

    /** Drains the worker's write-back, if it made one, and takes what it sent; then forgets it for the dry run. */
    @SuppressWarnings("unchecked")
    private Worker finishWorker() {
        Map<String, Object> upserted = null;
        if (new UpsertDrain(scheduler, upsertService).drain() > 0) {
            List<LoggedRequest> writes = BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROWS_PATH)));
            Map<String, Object> body = jsonMapper.readValue(writes.getLast().getBodyAsString(), Map.class);
            upserted = ((List<Map<String, Object>>) body.get("rows")).getFirst();
        }
        List<LoggedRequest> calls = BACKEND.findAll(postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.anyUrl()));
        BACKEND.resetRequests();
        // Both sides spend against the same daily counter; clear the worker's so the dry run's gate reads the same day.
        jdbcTemplate.execute("TRUNCATE spend_counter");
        return new Worker(calls, upserted);
    }

    private DryRun runDryRun(PipelineDto pipeline, List<Map<String, Object>> rows, Boolean truncated, String groupKey)
            throws Exception {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("columns", List.of());
        preview.put("rows", List.of());
        preview.put("notes", List.of());
        if (groupKey != null) {
            preview.put("group_key", groupKey);
        }
        Map<String, Object> trialInput = new LinkedHashMap<>();
        trialInput.put("preview", preview);
        trialInput.put("pipeline", pipeline);
        trialInput.put("input_rows", rows);
        if (truncated != null) {
            trialInput.put("truncated", truncated);
        }
        BACKEND.stubFor(post(urlPathEqualTo(TRIAL_PATH)).willReturn(okJson(jsonMapper.writeValueAsString(trialInput))));

        String body = mockMvc.perform(MockMvcRequestBuilders.post("/v1/pipelines/" + PIPELINE + "/try")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new DryRun(BACKEND.findAll(postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.anyUrl())), body);
    }

    // --- fixtures and assertions ------------------------------------------------------------------------------------

    private static PipelineDto rowChatPipeline() {
        return TestPipelines.builder(PIPELINE)
                .generation(GENERATION)
                .requestTemplate("{\"messages\":[{\"role\":\"system\",\"content\":\"Deployment {{deployment}}\"},"
                        + "{\"role\":\"user\",\"content\":\"{{prompt}}\"}]}")
                .params(Map.of("temperature", 0, "max_tokens", 50))
                .transformInputs(Map.of(
                        "prompt", new VarDto("body", null),
                        "deployment", new VarDto(null, "$uppercase(deployment)")))
                .responseSchema(Map.of("type", "object", "required", List.of("label", "score"),
                        "additionalProperties", false,
                        "properties", Map.of("label", Map.of("type", "string"), "score", Map.of("type", "integer"))))
                .outputs(List.of(new OutputDto("label", "sentiment", null),
                        new OutputDto("score", "score_band", "score > 5 ? \"high\" : \"low\"")))
                .build();
    }

    private static Map<String, Object> row(String eventId, String body) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", eventId);
        row.put("deployment", "gpt-4o-mini");
        row.put("body", body);
        return row;
    }

    private static Map<String, Object> member(String body) {
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("chat_id", GROUP_KEY);
        member.put("body", body);
        return member;
    }

    private void stubChat(String content) {
        BACKEND.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(okJson(jsonMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", content))),
                "usage", Map.of("total_tokens", 9))))));
    }

    /** The same number of DIAL calls, each with the same path, query and JSON body, in the same order. */
    private void assertSameRequests(List<LoggedRequest> worker, List<LoggedRequest> dryRun, int expected) {
        assertThat(worker).hasSize(expected);
        assertThat(dryRun).hasSize(expected);
        for (int i = 0; i < expected; i++) {
            assertThat(dryRun.get(i).getUrl()).isEqualTo(worker.get(i).getUrl());
            assertThat(jsonMapper.readTree(dryRun.get(i).getBodyAsString()))
                    .as("request %d body", i)
                    .isEqualTo(jsonMapper.readTree(worker.get(i).getBodyAsString()));
        }
    }
}
