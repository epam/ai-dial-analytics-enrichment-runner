package com.epam.aidial.enrichment.dryrun;

import com.epam.aidial.enrichment.adas.AdasCompatibilityState;
import com.epam.aidial.enrichment.adas.dto.OutputDto;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.configuration.DialProperties;
import com.epam.aidial.enrichment.dial.DialRateLimiter;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.spend.SpendService;
import com.epam.aidial.enrichment.support.AdasContractEcho;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.CapturingAppender;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.jayway.jsonpath.JsonPath;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /v1/pipelines/{name}/try}: a sampled row or group read through ADAS's trial input, evaluated through the
 * model or application exactly as a worker would, and answered beside ADAS's preview — with every call charged and
 * nothing else changed. DIAL and ADAS are stubbed on one WireMock server; PostgreSQL runs in Testcontainers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DryRunIntegrationTest {

    private static final AdasContractEcho ECHO = new AdasContractEcho();
    private static final WireMockServer BACKEND = AdasWireMock.server(ECHO);
    private static final String PIPELINE = "dr-try";
    private static final String TRIAL_PATH = "/v1/pipelines/" + PIPELINE + "/trial-input";
    private static final String CHAT_PATH = "/openai/deployments/gpt-4o/chat/completions";
    private static final String ROUTE_PATH = "/v1/deployments/insights-app/route/v1/insights";
    private static final String TRY = "/v1/pipelines/" + PIPELINE + "/try";
    private static final String SERVICE_KEY = "service-key";
    private static final int CALL_BUDGET = 10;
    private static final String PREVIEW = "{\"columns\":[],\"rows\":[],\"notes\":[]}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private SpendService spendService;

    @Autowired
    private DialRateLimiter dialRateLimiter;

    @Autowired
    private DialProperties dialProperties;

    @Autowired
    private AdasCompatibilityState compatibilityState;

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
        registry.add("adas.api-key", () -> SERVICE_KEY);
        registry.add("dial.base-url", BACKEND::baseUrl);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("spend.pause.enabled", () -> "true");
        registry.add("spend.budget.daily-calls", () -> Integer.toString(CALL_BUDGET));
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
        registry.add("group.sweep-initial-delay", () -> "3600s");
        registry.add("group.sweep-interval", () -> "3600s");
    }

    @BeforeEach
    void reset() {
        ECHO.reset();
        BACKEND.resetAll();
        dialRateLimiter.evict(PIPELINE);
        jdbcTemplate.execute("TRUNCATE dlq_item, scheduled_tasks, spend_counter, group_state");
    }

    // --- fixtures -------------------------------------------------------------------------------------------------

    private static PipelineDto rowPipeline() {
        return TestPipelines.builder(PIPELINE)
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"{{prompt}}\"}]}")
                .transformInputs(TestPipelines.columnVar("prompt", "body"))
                .build();
    }

    private static PipelineDto groupPipeline() {
        return TestPipelines.builder(PIPELINE)
                .triggerKind(com.epam.aidial.enrichment.adas.dto.TriggerKind.GROUP)
                .groupBy("chat_id")
                .readyWhen(new com.epam.aidial.enrichment.adas.dto.ReadyWhenDto(null, "10m", null, null))
                .grainKey("chat_id")
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":{{members:json}}}]}")
                .transformInputs(TestPipelines.columnVar("prompt", "body"))
                .build();
    }

    private static PipelineDto routePipeline(String source) {
        return TestPipelines.builder(PIPELINE)
                .model("insights-app")
                .appRoute("v1/insights")
                .requestTemplate("{\"text\": {{prompt:json}}}")
                .transformInputs(TestPipelines.columnVar("prompt", "body"))
                .outputs(List.of(new OutputDto("label", "label", null, source)))
                .build();
    }

    private static Map<String, Object> row(String eventId, String body) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", eventId);
        row.put("body", body);
        return row;
    }

    private void stubTrialInput(PipelineDto pipeline, List<Map<String, Object>> rows, Boolean truncated, String groupKey) {
        Map<String, Object> preview = new LinkedHashMap<>(jsonMapper.readValue(PREVIEW, Map.class));
        if (groupKey != null) {
            preview.put("group_key", groupKey);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("preview", preview);
        body.put("pipeline", pipeline);
        body.put("input_rows", rows);
        if (truncated != null) {
            body.put("truncated", truncated);
        }
        BACKEND.stubFor(post(urlPathEqualTo(TRIAL_PATH)).willReturn(okJson(jsonMapper.writeValueAsString(body))));
    }

    private void stubChat(String content, int tokens) {
        BACKEND.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(okJson(chatReply(content, tokens))));
    }

    private String chatReply(String content, int tokens) {
        return jsonMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", content))),
                "usage", Map.of("total_tokens", tokens)));
    }

    private ResultActions dryRun(String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(TRY).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long spend(String column) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(" + column + "), 0) FROM spend_counter WHERE pipeline_name = ?", Long.class, PIPELINE);
        return value == null ? 0 : value;
    }

    private void assertNothingButSpendChanged() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dlq_item", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM scheduled_tasks", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM group_state", Long.class)).isZero();
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo("/v1/pipelines/" + PIPELINE + "/rows")))).isEmpty();
    }

    // --- evaluation -----------------------------------------------------------------------------------------------

    @Test
    void rowGrainDryRunCallsTheModelOnceAndMapsItsReply() throws Exception {
        stubTrialInput(rowPipeline(), List.of(row("e1", "great product")), null, null);
        stubChat("{\"label\":\"positive\"}", 12);

        String body = dryRun("{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.notes").isArray())
                .andExpect(jsonPath("$.evaluations", hasSize(1)))
                .andExpect(jsonPath("$.evaluations[0].grain_key").value("e1"))
                .andExpect(jsonPath("$.evaluations[0].status").value("ok"))
                .andExpect(jsonPath("$.evaluations[0].outputs.label").value("positive"))
                .andExpect(jsonPath("$.evaluations[0].attempts").value(1))
                .andExpect(jsonPath("$.evaluations[0].usage.amount").value(12))
                .andExpect(jsonPath("$.evaluations[0].usage.unit").value("tokens"))
                .andExpect(jsonPath("$.evaluations[0].request_truncated").value(false))
                .andExpect(jsonPath("$.evaluations[0].stage").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String request = JsonPath.read(body, "$.evaluations[0].request");
        assertThat(request).contains("great product").contains("response_format");
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).hasSize(1);
        assertThat(spend("calls")).isEqualTo(1);
        assertThat(spend("usage_amount")).isEqualTo(12);
        assertNothingButSpendChanged();
    }

    @Test
    void limitIsForwardedAndEachRowIsEvaluatedInOrder() throws Exception {
        stubTrialInput(rowPipeline(), List.of(row("e3", "c"), row("e2", "b"), row("e1", "a")), null, null);
        stubChat("{\"label\":\"x\"}", 1);

        String body = dryRun("{\"limit\":3}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat((List<String>) JsonPath.read(body, "$.evaluations[*].grain_key")).containsExactly("e3", "e2", "e1");
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).hasSize(3);
        BACKEND.verify(postRequestedFor(urlPathEqualTo(TRIAL_PATH))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.equalToJson("{\"limit\":3}")));
    }

    @Test
    void groupDryRunEvaluatesTheWholeFetchedGroupOnce() throws Exception {
        List<Map<String, Object>> members = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            members.add(row("e" + i, "turn " + i));
        }
        stubTrialInput(groupPipeline(), members, false, "chat-42");
        stubChat("{\"label\":\"support\"}", 40);

        String body = dryRun("{\"group_key\":\"chat-42\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluations", hasSize(1)))
                .andExpect(jsonPath("$.evaluations[0].group_key").value("chat-42"))
                .andExpect(jsonPath("$.evaluations[0].status").value("ok"))
                .andExpect(jsonPath("$.evaluations[0].members_included").value(12))
                .andExpect(jsonPath("$.evaluations[0].members_omitted").value(0))
                .andExpect(jsonPath("$.evaluations[0].members_truncated").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(body, "$.evaluations[0].request")).contains("turn 1").contains("turn 12");
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).hasSize(1);
        assertNothingButSpendChanged();
    }

    @Test
    void appRouteDryRunCallsTheRouteWithoutResponseFormatOrRetry() throws Exception {
        stubTrialInput(routePipeline("result.label"), List.of(row("e1", "great product")), null, null);
        BACKEND.stubFor(post(urlPathEqualTo(ROUTE_PATH)).willReturn(okJson("{\"result\":{\"label\":\"positive\"}}")));

        dryRun("{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluations[0].status").value("ok"))
                .andExpect(jsonPath("$.evaluations[0].outputs.label").value("positive"))
                .andExpect(jsonPath("$.evaluations[0].usage.unit").value("calls"));

        var calls = BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROUTE_PATH)));
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().getBodyAsString()).contains("great product").doesNotContain("response_format");
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).isEmpty();
    }

    // --- failures are answers ----------------------------------------------------------------------------------------

    @Test
    void missingDeploymentIsFailedEvaluationAtDialCall() throws Exception {
        stubTrialInput(rowPipeline(), List.of(row("e1", "hi")), null, null);
        BACKEND.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"Deployment gpt-4o is not found\"}}")));

        dryRun("{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluations[0].status").value("failed"))
                .andExpect(jsonPath("$.evaluations[0].stage").value("dial_call"))
                .andExpect(jsonPath("$.evaluations[0].dial_status").value(404))
                .andExpect(jsonPath("$.evaluations[0].response").value(org.hamcrest.Matchers.containsString("not found")));

        assertThat(spend("failures")).isEqualTo(1);
        assertNothingButSpendChanged();
    }

    @Test
    void replyMissingSchemaPropertyIsReportedAfterTheCorrectiveRetry() throws Exception {
        stubTrialInput(rowPipeline(), List.of(row("e1", "hi")), null, null);
        stubChat("{\"other\":1}", 5);

        dryRun("{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluations[0].status").value("failed"))
                .andExpect(jsonPath("$.evaluations[0].stage").value("validate"))
                .andExpect(jsonPath("$.evaluations[0].attempts").value(2))
                .andExpect(jsonPath("$.evaluations[0].errors").isNotEmpty())
                .andExpect(jsonPath("$.evaluations[0].response").value(org.hamcrest.Matchers.containsString("other")));

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).hasSize(2);
        assertNothingButSpendChanged();
    }

    @Test
    void unresolvedPlaceholderFailsAtInputMapWithNoCall() throws Exception {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE)
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"{{missing}}\"}]}")
                .build();
        stubTrialInput(pipeline, List.of(row("e1", "hi")), null, null);

        dryRun("{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluations[0].stage").value("input_map"))
                .andExpect(jsonPath("$.evaluations[0].attempts").value(0));

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).isEmpty();
        assertThat(spend("calls")).isZero();
    }

    @Test
    void emptySampleMakesNoCall() throws Exception {
        stubTrialInput(groupPipeline(), List.of(), false, null);

        dryRun("{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluations", hasSize(0)));

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).isEmpty();
    }

    // --- refusals ---------------------------------------------------------------------------------------------------

    @Test
    void adasRefusalIsAnsweredWithAdasCodeAndNoCall() throws Exception {
        BACKEND.stubFor(post(urlPathEqualTo(TRIAL_PATH)).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":403,\"error\":\"sensitive_column_not_entitled\","
                        + "\"message\":\"caller may not read column 'body'\"}")));

        dryRun("{}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("sensitive_column_not_entitled"))
                .andExpect(jsonPath("$.message").value("caller may not read column 'body'"))
                .andExpect(jsonPath("$.path").value(TRY));

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).isEmpty();
    }

    @Test
    void callerCredentialIsForwardedInsteadOfTheServiceKey() throws Exception {
        stubTrialInput(rowPipeline(), List.of(), null, null);

        mockMvc.perform(MockMvcRequestBuilders.post(TRY).contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer author-token"))
                .andExpect(status().isOk());

        BACKEND.verify(postRequestedFor(urlPathEqualTo(TRIAL_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer author-token"))
                .withHeader("Api-Key", absent()));
    }

    @Test
    void withNoCallerCredentialTheServiceKeyIsSent() throws Exception {
        stubTrialInput(rowPipeline(), List.of(), null, null);

        dryRun("{}").andExpect(status().isOk());

        BACKEND.verify(postRequestedFor(urlPathEqualTo(TRIAL_PATH))
                .withHeader("Api-Key", equalTo(SERVICE_KEY))
                .withHeader(HttpHeaders.AUTHORIZATION, absent()));
    }

    @Test
    void declarationTheSyncWouldRefuseIsNotCalled() throws Exception {
        stubTrialInput(routePipeline("result.("), List.of(row("e1", "hi")), null, null);

        dryRun("{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_not_runnable"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("label")));

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROUTE_PATH)))).isEmpty();
    }

    @Test
    void pipelineOverItsDailyBudgetIsNotCalled() throws Exception {
        for (int i = 0; i < CALL_BUDGET; i++) {
            spendService.record(PIPELINE, 1, 1, false);
        }
        stubTrialInput(rowPipeline(), List.of(row("e1", "hi")), null, null);
        stubChat("{\"label\":\"x\"}", 1);

        dryRun("{}")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("spend_budget_exhausted"));

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).isEmpty();
        // Refused before the read: nothing is pulled out of ADAS for a dry run that cannot spend.
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(TRIAL_PATH)))).isEmpty();
    }

    @Test
    void exhaustedRateLimiterRefusesRatherThanQueues() throws Exception {
        PipelineDto pipeline = rowPipeline();
        while (dialRateLimiter.tryAcquire(PIPELINE, pipeline.rateRpm())) {
            // drain the pipeline's bucket
        }
        stubTrialInput(pipeline, List.of(row("e1", "hi")), null, null);
        stubChat("{\"label\":\"x\"}", 1);

        dryRun("{}")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("dial_rate_limited"));

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).isEmpty();
    }

    /**
     * The limiter runs dry between the first call and its corrective retry: the paid-for first reply is answered as a
     * failed evaluation at {@code dial_call}, not discarded behind a 429.
     */
    @Test
    void rateLimiterRefusingTheRetryKeepsTheReplyAlreadyPaidFor() throws Exception {
        PipelineDto pipeline = rowPipeline();
        for (int i = 0; i < dialProperties.defaultRpm() - 1; i++) {
            assertThat(dialRateLimiter.tryAcquire(PIPELINE, pipeline.rateRpm())).isTrue();
        }
        stubTrialInput(pipeline, List.of(row("e1", "hi")), null, null);
        stubChat("{\"other\":1}", 5);

        dryRun("{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluations[0].status").value("failed"))
                .andExpect(jsonPath("$.evaluations[0].stage").value("dial_call"))
                .andExpect(jsonPath("$.evaluations[0].attempts").value(1))
                .andExpect(jsonPath("$.evaluations[0].message").value(org.hamcrest.Matchers.containsString("rate-limit")))
                .andExpect(jsonPath("$.evaluations[0].response").value(org.hamcrest.Matchers.containsString("other")));

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).hasSize(1);
        assertThat(spend("calls")).isEqualTo(1);
    }

    @Test
    void secondDryRunOfTheSamePipelineIsRefusedWhileTheFirstRuns() throws Exception {
        stubTrialInput(rowPipeline(), List.of(row("e1", "hi")), null, null);
        BACKEND.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(okJson(chatReply("{\"label\":\"x\"}", 1))
                .withFixedDelay(1500)));

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> {
            try {
                return dryRun("{}").andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(500);
        dryRun("{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("dry_run_in_progress"));

        assertThat(first.get()).isEqualTo(200);
    }

    @Test
    void unreachableAdasIsServiceUnavailable() throws Exception {
        BACKEND.stubFor(post(urlPathEqualTo(TRIAL_PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        boolean compatibleBefore = compatibilityState.isCompatible();

        dryRun("{}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("adas_unavailable"));

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).isEmpty();
        // A transport failure is no verdict on the contract.
        assertThat(compatibilityState.isCompatible()).isEqualTo(compatibleBefore);
    }

    @Test
    void adasOnAnotherContractVersionIsServiceUnavailable() throws Exception {
        stubTrialInput(rowPipeline(), List.of(row("e1", "hi")), null, null);
        ECHO.serve("6");
        try {
            dryRun("{}")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("adas_contract_mismatch"));

            assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH)))).isEmpty();
        } finally {
            ECHO.reset();
            // A later compatible call restores the recorded verdict, as it does for any other ADAS call.
            stubTrialInput(rowPipeline(), List.of(), null, null);
            dryRun("{}").andExpect(status().isOk());
        }
    }

    @Test
    void outputExpressionFailureIsReportedAtOutputMap() throws Exception {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE)
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"{{prompt}}\"}]}")
                .transformInputs(TestPipelines.columnVar("prompt", "body"))
                .outputs(List.of(new OutputDto("label", "label", "$error('cannot map')")))
                .build();
        stubTrialInput(pipeline, List.of(row("e1", "hi")), null, null);
        stubChat("{\"label\":\"x\"}", 3);

        dryRun("{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluations[0].status").value("failed"))
                .andExpect(jsonPath("$.evaluations[0].stage").value("output_map"))
                .andExpect(jsonPath("$.evaluations[0].attempts").value(1));

        assertThat(spend("calls")).isEqualTo(1);
        assertNothingButSpendChanged();
    }

    @Test
    void unknownBodyMemberIsRefused() throws Exception {
        dryRun("{\"cursor\":\"abc\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(TRIAL_PATH)))).isEmpty();
    }

    @Test
    void dryRunContentIsNeverLoggedAtAnyLevel() throws Exception {
        String secret = "member-content-that-must-not-be-logged";
        String reply = "reply-content-that-must-not-be-logged";
        stubTrialInput(groupPipeline(), List.of(row("e1", secret)), false, "chat-42");
        stubChat("{\"label\":\"" + reply + "\"}", 1);

        // The rendered request, the members and the reply are never logged at ANY level, so the capture lowers the
        // root logger to TRACE.
        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            dryRun("{}").andExpect(status().isOk());

            assertThat(appender.containsText("dry run pipeline=" + PIPELINE)).isTrue();
            assertThat(appender.containsText(secret))
                    .as("member content must not appear in any log record: %s", leaking(appender, secret)).isFalse();
            assertThat(appender.containsText(reply))
                    .as("reply content must not appear in any log record: %s", leaking(appender, reply)).isFalse();
        } finally {
            appender.detach(Level.INFO);
        }
    }

    private static List<String> leaking(CapturingAppender appender, String text) {
        return appender.messages().stream().filter(message -> message.contains(text)).toList();
    }
}
