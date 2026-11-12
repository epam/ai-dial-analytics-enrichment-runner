package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.adas.dto.OutputDto;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.exception.TransientChunkException;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.support.UpsertDrain;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.upsert.UpsertService;
import com.github.kagkarlsson.scheduler.Scheduler;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

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
 * The row lane calling a DIAL application route ({@code transform.preset: dial_app_route}): the call's path, body
 * and missing chat members, the whole reply as the output or reshaped by {@code response_jsonata}, the refusals that
 * dead-letter at {@code validate} after one call, status classification, spend counted in calls, and the route's own
 * read timeout. DIAL and ADAS are stubbed on one WireMock server; PostgreSQL runs in Testcontainers.
 */
@SpringBootTest
class AppRouteWorkerIntegrationTest {

    private static final WireMockServer BACKEND = AdasWireMock.server();
    private static final String PIPELINE_NAME = "r-route";
    private static final String ROUTE_PATH = "/v1/deployments/insights-app/route/v1/insights";
    private static final String CHAT_PATH = "/openai/deployments/gpt-4o/chat/completions";
    private static final String ROWS_PATH = "/v1/pipelines/" + PIPELINE_NAME + "/rows";
    private static final int ROUTE_READ_TIMEOUT_MS = 700;

    @Autowired
    private WorkerService workerService;

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

    @BeforeEach
    void reset() {
        BACKEND.resetAll();
        jdbcTemplate.execute("TRUNCATE dlq_item, scheduled_tasks, spend_counter");
        BACKEND.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(okJson("{\"inserted\":1}")));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("adas.base-url", BACKEND::baseUrl);
        registry.add("dial.base-url", BACKEND::baseUrl);
        registry.add("dial.route-read-timeout-ms", () -> Integer.toString(ROUTE_READ_TIMEOUT_MS));
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
    }

    @Test
    void wholeRouteReplyIsTheOutput() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, routePipeline(null)));
        BACKEND.stubFor(post(urlPathEqualTo(ROUTE_PATH)).willReturn(okJson("{\"label\":\"positive\"}")));

        process();

        var calls = BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROUTE_PATH)));
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().getQueryParams()).isEmpty();
        Map<String, Object> sent = jsonMapper.readValue(calls.getFirst().getBodyAsString(),
                new TypeReference<Map<String, Object>>() { });
        assertThat(sent).containsEntry("text", "great product").containsEntry("temperature", 0)
                .doesNotContainKey("response_format");
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).isEmpty();
        assertThat(upsertedRow()).containsEntry("sentiment", "positive").containsEntry("model", "insights-app");
    }

    @Test
    void nestedReplyIsFlattenedByTheResponseMappingAndItsExtraMembersNeverReachValidation() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, routePipeline("{\"label\": result.label}")));
        BACKEND.stubFor(post(urlPathEqualTo(ROUTE_PATH)).willReturn(okJson(
                "{\"result\":{\"label\":\"negative\"},\"usage\":{\"cost\":1}}")));

        process();

        assertThat(upsertedRow()).containsEntry("sentiment", "negative");
        assertThat(dlqCount("validate")).isZero();
    }

    @Test
    void invalidRouteReplyIsDeadLetteredAfterOneCallWithTheRawBody() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, routePipeline(null)));
        String raw = "{\"label\":\"positive\",\"usage\":{\"cost\":1}}";
        BACKEND.stubFor(post(urlPathEqualTo(ROUTE_PATH)).willReturn(okJson(raw)));

        process();

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROUTE_PATH)))).hasSize(1);
        assertThat(dlqCount("validate")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT raw_response FROM dlq_item", String.class)).isEqualTo(raw);
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROWS_PATH)))).isEmpty();
    }

    @Test
    void mappingThatYieldsNoObjectIsDeadLetteredWithTheRawBody() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, routePipeline("result.label")));
        String raw = "{\"result\":{\"label\":\"negative\"}}";
        BACKEND.stubFor(post(urlPathEqualTo(ROUTE_PATH)).willReturn(okJson(raw)));

        process();

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROUTE_PATH)))).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT raw_response FROM dlq_item WHERE stage = 'validate'",
                String.class)).isEqualTo(raw);
    }

    @Test
    void nonJsonRouteReplyIsDeadLetteredAtValidate() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, routePipeline(null)));
        BACKEND.stubFor(post(urlPathEqualTo(ROUTE_PATH)).willReturn(aResponse().withStatus(200).withBody("not json")));

        process();

        assertThat(dlqCount("validate")).isEqualTo(1);
    }

    @Test
    void retryableStatusRetriesAndPermanentOneDeadLettersAtTheCall() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, routePipeline(null)));
        BACKEND.stubFor(post(urlPathEqualTo(ROUTE_PATH)).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(this::process).isInstanceOf(TransientChunkException.class);
        assertThat(dlqCount("dial_call")).isZero();

        BACKEND.resetAll();
        BACKEND.stubFor(post(urlPathEqualTo(ROUTE_PATH)).willReturn(aResponse().withStatus(400)));
        process();
        assertThat(dlqCount("dial_call")).isEqualTo(1);
    }

    @Test
    void routeCallsAreCountedAsCallsWithNoTokens() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, routePipeline(null)));
        BACKEND.stubFor(post(urlPathEqualTo(ROUTE_PATH)).willReturn(okJson("{\"label\":\"positive\"}")));

        process();
        process();

        Map<String, Object> totals = jdbcTemplate.queryForMap(
                "SELECT sum(calls) AS calls, sum(usage_amount) AS usage, max(usage_unit) AS unit FROM spend_counter"
                        + " WHERE pipeline_name = ?", PIPELINE_NAME);
        assertThat(((Number) totals.get("calls")).longValue()).isEqualTo(2);
        assertThat(((Number) totals.get("usage")).longValue()).isZero();
        assertThat(totals.get("unit")).isEqualTo("calls");
    }

    @Test
    void routeReadTimeoutAppliesToRouteCallsOnly() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, routePipeline(null)));
        BACKEND.stubFor(post(urlPathEqualTo(ROUTE_PATH)).willReturn(okJson("{\"label\":\"positive\"}")
                .withFixedDelay(ROUTE_READ_TIMEOUT_MS * 2)));
        assertThatThrownBy(this::process).isInstanceOf(TransientChunkException.class);

        // The chat client keeps the default read timeout, so the same delay is answered there.
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, TestPipelines.builder(PIPELINE_NAME)
                .generation(3)
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"{{prompt}}\"}]}")
                .responseSchema(labelSchema())
                .outputs(List.of(new OutputDto("label", "sentiment", null)))
                .build()));
        BACKEND.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(okJson(jsonMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", "{\"label\":\"positive\"}"))),
                "usage", Map.of("total_tokens", 10)))).withFixedDelay(ROUTE_READ_TIMEOUT_MS * 2)));
        process();
        assertThat(upsertedRow()).containsEntry("sentiment", "positive");
    }

    private void process() {
        workerService.process("chunk-1", new ChunkPayload(PIPELINE_NAME, 3,
                List.of(new ChunkRow("e0", Map.of("prompt", "great product")))));
    }

    private static PipelineDto routePipeline(String responseJsonata) {
        return TestPipelines.builder(PIPELINE_NAME)
                .generation(3)
                .model("insights-app")
                .appRoute("v1/insights")
                .responseJsonata(responseJsonata)
                .requestTemplate("{\"text\": {{prompt:json}}}")
                .params(Map.of("temperature", 0))
                .responseSchema(labelSchema())
                .outputs(List.of(new OutputDto("label", "sentiment", null)))
                .build();
    }

    private static Map<String, Object> labelSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("label"),
                "additionalProperties", false,
                "properties", Map.of("label", Map.of("type", "string")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> upsertedRow() {
        assertThat(new UpsertDrain(scheduler, upsertService).drain()).isEqualTo(1);
        var upserts = BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROWS_PATH)));
        Map<String, Object> body = jsonMapper.readValue(upserts.getLast().getBodyAsString(), Map.class);
        return ((List<Map<String, Object>>) body.get("rows")).getFirst();
    }

    private long dlqCount(String stage) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM dlq_item WHERE stage = ?", Long.class, stage);
        return count == null ? 0 : count;
    }
}
