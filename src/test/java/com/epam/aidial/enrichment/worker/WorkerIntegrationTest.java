package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.adas.dto.OutputDto;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.support.UpsertDrain;
import com.epam.aidial.enrichment.sync.PipelineCache;
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
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 end-to-end test: an enrich-chunk row is rendered, sent to DIAL, validated, output-mapped, and
 * upserted through ADAS with provenance; a schema-invalid response is dead-lettered at stage
 * {@code validate}; and a stale chunk is dropped without calling DIAL. DIAL and ADAS are stubbed on a
 * single WireMock server (different paths); PostgreSQL runs in Testcontainers.
 */
@SpringBootTest
class WorkerIntegrationTest {

    private static final WireMockServer BACKEND = AdasWireMock.server();
    private static final String PIPELINE_NAME = "r-worker";
    private static final String CHAT_PATH = "/openai/deployments/gpt-4o/chat/completions";
    private static final String ROWS_PATH = "/v1/pipelines/" + PIPELINE_NAME + "/rows";

    @Autowired
    private WorkerService workerService;

    @Autowired
    private PipelineCache pipelineCache;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

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

    @BeforeEach
    void reset() {
        BACKEND.resetAll();
        jdbcTemplate.execute("TRUNCATE dlq_item, scheduled_tasks");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("adas.base-url", BACKEND::baseUrl);
        registry.add("dial.base-url", BACKEND::baseUrl);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
    }

    @Test
    void enrichesRowAndUpsertsItWithProvenance() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, sentimentPipeline(3)));
        BACKEND.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(okJson(dialResponse("{\"label\":\"positive\"}"))));
        BACKEND.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(okJson("{\"inserted\":1}")));

        workerService.process("chunk-1", new ChunkPayload(PIPELINE_NAME, 3,
                List.of(new ChunkRow("e0", Map.of("prompt", "great product")))));
        // Compute queues the write; the write is its own execution since H1.
        assertThat(new UpsertDrain(scheduler, upsertService).drain()).isEqualTo(1);

        // DIAL Core forwards the query string verbatim to the upstream adapter, and the
        // Azure-OpenAI-compatible ones answer 400 "api-version is a required query parameter" without
        // it — a permanent failure that would dead-letter every row. Asserted here because nothing
        // else fails if the parameter silently disappears.
        var chatCalls = BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)));
        assertThat(chatCalls).hasSize(1);
        assertThat(chatCalls.getFirst().queryParameter("api-version").firstValue())
                .isEqualTo("2025-01-01-preview");

        var upserts = BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROWS_PATH)));
        assertThat(upserts).hasSize(1);

        Map<String, Object> row = firstUpsertedRow(upserts.getFirst().getBodyAsString());
        assertThat(row).containsEntry("event_id", "e0");
        assertThat(row).containsEntry("sentiment", "positive");
        assertThat(row).containsEntry("pipeline_generation", 3);
        assertThat(row).containsEntry("model", "gpt-4o");
        assertThat(row).containsKey("enriched_at");

        // The retired provenance spelling, asserted by its absence across EVERY body this run sent — not
        // just the write. ADAS refuses a row naming `evaluator_version` with HTTP 422 whether or not
        // `pipeline_generation` is beside it, so sending both as a hedge fails the batch rather than covering
        // both cases. One missed payload builder would fail every write it makes.
        assertThat(everyRequestBody()).noneMatch(body -> body.contains("evaluator_version"));
    }

    /** Every request body this service sent to the stubbed backend during the current test. */
    private static List<String> everyRequestBody() {
        return BACKEND.getAllServeEvents().stream()
                .map(event -> event.getRequest().getBodyAsString())
                .filter(body -> body != null && !body.isBlank())
                .toList();
    }

    @Test
    void schemaInvalidResponseIsDeadLettered() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, sentimentPipeline(3)));
        BACKEND.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(okJson(dialResponse("{\"wrong\":1}"))));

        workerService.process("chunk-1", new ChunkPayload(PIPELINE_NAME, 3,
                List.of(new ChunkRow("e0", Map.of("prompt", "great product")))));

        assertThat(validateDlqCount()).isEqualTo(1);
        // Nothing was computed, so nothing was queued for writing and no write can have happened.
        assertThat(new UpsertDrain(scheduler, upsertService).drain()).isZero();
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROWS_PATH)))).isEmpty();
    }

    @Test
    void staleChunkIsDroppedWithoutCallingDial() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, sentimentPipeline(4))); // pinned to v4
        BACKEND.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(okJson(dialResponse("{\"label\":\"positive\"}"))));

        workerService.process("chunk-1", new ChunkPayload(PIPELINE_NAME, 3, // chunk built against v3
                List.of(new ChunkRow("e0", Map.of("prompt", "great product")))));

        assertThat(BACKEND.findAll(postRequestedFor(urlPathMatching("/openai/deployments/.*/chat/completions")))).isEmpty();
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROWS_PATH)))).isEmpty();
    }

    private static PipelineDto sentimentPipeline(long generation) {
        return TestPipelines.builder(PIPELINE_NAME)
                .generation(generation)
                .model("gpt-4o")
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"Classify: {{prompt}}\"}]}")
                .params(Map.of("temperature", 0))
                .responseSchema(Map.of(
                        "type", "object",
                        "required", List.of("label"),
                        "properties", Map.of("label", Map.of("type", "string"))))
                // Response property and physical column deliberately differ: the value is read from the
                // model's JSON under `label` and written to the column `sentiment`.
                .outputs(List.of(new OutputDto("label", "sentiment", null)))
                .build();
    }

    private String dialResponse(String contentJson) {
        return jsonMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", contentJson))),
                "usage", Map.of("total_tokens", 10)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstUpsertedRow(String requestBody) {
        Map<String, Object> body = jsonMapper.readValue(requestBody, Map.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        return rows.getFirst();
    }

    private long validateDlqCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dlq_item WHERE stage = 'validate'", Long.class);
        return count == null ? 0 : count;
    }
}
