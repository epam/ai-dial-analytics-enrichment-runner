package com.epam.aidial.enrichment.sync;

import com.epam.aidial.enrichment.adas.AdasCompatibilityState;
import com.epam.aidial.enrichment.adas.AdasContract;
import com.epam.aidial.enrichment.adas.AdasGroupFetchClient;
import com.epam.aidial.enrichment.adas.AdasMaterializeClient;
import com.epam.aidial.enrichment.adas.AdasScanClient;
import com.epam.aidial.enrichment.adas.AdasUpsertClient;
import com.epam.aidial.enrichment.exception.AdasContractMismatchException;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.observability.ReadinessState;
import com.epam.aidial.enrichment.scan.PipelineScanTask;
import com.epam.aidial.enrichment.support.AdasContractEcho;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.CapturingAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract handshake end to end: the header this build declares on every ADAS call, the echo it
 * requires back, and what an ADAS that does not carry it does to the runner.
 *
 * <p><b>Ordered on purpose.</b> {@link ReadinessState} is a latch — set once, never cleared — so "a fresh
 * process against an incompatible ADAS never reports ready" can only be observed before any poll in this
 * context has succeeded. The skew tests therefore run first, and the recovery test is what latches it.
 *
 * <p>Everything here is stubbed and asserted against {@link AdasContract#VERSION} rather than the literal
 * {@code 1}, so raising the constant moves the fixtures with it instead of leaving one pinning the old
 * number.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdasContractIntegrationTest {

    private static final AdasContractEcho ECHO = new AdasContractEcho();
    private static final WireMockServer ADAS = AdasWireMock.server(ECHO);
    private static final String PIPELINE_NAME = "r-contract";

    /** The condition line {@code AdasCompatibilityState} logs once per transition. */
    private static final String CONDITION_LOG = "ADAS is incompatible with this build's contract version";

    /** The "what the poll did about it" line {@code PipelineSyncService} logs once per transition. */
    private static final String SYNC_LOG = "Pipeline sync refused:";

    @Autowired
    private PipelineSyncService syncService;

    @Autowired
    private PipelineCache cache;

    @Autowired
    private ReadinessState readinessState;

    @Autowired
    private AdasCompatibilityState compatibilityState;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdasScanClient scanClient;

    @Autowired
    private AdasUpsertClient upsertClient;

    @Autowired
    private AdasGroupFetchClient groupFetchClient;

    @Autowired
    private AdasMaterializeClient materializeClient;

    private CapturingAppender appender;

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
        ECHO.reset();
        compatibilityState.markCompatible();
        appender = CapturingAppender.attachAtTrace();
    }

    @AfterEach
    void detach() {
        appender.detach(Level.INFO);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("adas.base-url", ADAS::baseUrl);
        registry.add("db-scheduler.auto-start", () -> "false");
        // The background poll must not race the manual syncs, or the ordered readiness assertion is a lottery.
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
    }

    /**
     * An ADAS older than the contract: a well-formed 200 with no echo. It cannot forge the header, which is
     * why absence is a fact rather than an inference — and why the check is on the response and not on the
     * request the runner sends.
     */
    @Test
    @Order(1)
    void feedWithoutTheEchoIsRefusedAndFreshPodNeverReportsReady() {
        ECHO.serve(null);
        stubPipelines(pipelineJson());

        syncService.sync();
        syncService.sync();
        syncService.sync();

        // Nothing was interpreted: no cache entry, no recurring instance, no reconcile.
        assertThat(cache.size()).isZero();
        assertThat(scanTaskCount()).isZero();
        // The load-bearing half: the sync is not marked succeeded, so the startup probe fails and the
        // outgoing pod keeps serving the ADAS it matches.
        assertThat(readinessState.isPipelineSyncSucceeded()).isFalse();
        assertThat(compatibilityGauge()).isZero();
        // Once per transition, not once per poll: a skew meets every call for the length of a deploy.
        assertThat(countLogs(CONDITION_LOG)).isEqualTo(1);
        assertThat(countLogs(SYNC_LOG)).isEqualTo(1);
    }

    /** An ADAS on the other side of a bump. The same verdict, and deliberately not ranked against the other. */
    @Test
    @Order(2)
    void feedEchoingAnotherVersionIsRefusedAndNamesBothVersions() {
        ECHO.serve(Integer.toString(AdasContract.VERSION + 1));
        stubPipelines(pipelineJson());

        syncService.sync();

        assertThat(cache.size()).isZero();
        assertThat(scanTaskCount()).isZero();
        assertThat(readinessState.isPipelineSyncSucceeded()).isFalse();
        assertThat(compatibilityGauge()).isZero();
        assertThat(appender.messages()).anySatisfy(message -> assertThat(message)
                .contains(Integer.toString(AdasContract.VERSION))
                .contains(Integer.toString(AdasContract.VERSION + 1)));
    }

    /** Recovery is automatic: the next matching poll reconciles, with nothing to re-enable by hand. */
    @Test
    @Order(3)
    void matchingEchoRestoresReconciliationWithNoOperatorAction() {
        ECHO.serve(null);
        stubPipelines(pipelineJson());
        syncService.sync();
        assertThat(cache.size()).isZero();

        ECHO.reset();
        syncService.sync();

        assertThat(cache.get(PIPELINE_NAME)).isNotNull();
        assertThat(scanTaskCount()).isEqualTo(1);
        assertThat(readinessState.isPipelineSyncSucceeded()).isTrue();
        assertThat(compatibilityGauge()).isEqualTo(1);
    }

    /**
     * Readiness is deliberately left alone for a process that has already started. Un-latching would pull
     * the pod out of its Service and take its own admin API — DLQ list, requeue, cache read — with it during
     * exactly the failure an operator needs to reach it for, and it would buy nothing the compatibility
     * gauge does not already say. Restarting a runner does not fix a version skew either.
     */
    @Test
    @Order(4)
    void runningPodStaysReadyAndReportsTheSkewThroughTheGauge() {
        stubPipelines(pipelineJson());
        syncService.sync();
        assertThat(readinessState.isPipelineSyncSucceeded()).isTrue();

        ECHO.serve(null);
        syncService.sync();

        // Still ready, and the cache it is serving from is the one it already had.
        assertThat(readinessState.isPipelineSyncSucceeded()).isTrue();
        assertThat(cache.get(PIPELINE_NAME)).isNotNull();
        assertThat(compatibilityGauge()).isZero();
    }

    /**
     * The condition is a property of the pair of deployments, which no pipeline owns — so the series carries
     * no {@code pipeline} tag rather than a placeholder value that a dashboard would have to filter out.
     */
    @Test
    @Order(5)
    void theCompatibilityGaugeIsUntagged() {
        assertThat(meterRegistry.get("runner.adas.contract.compatible").gauge().getId().getTags()).isEmpty();
    }

    /**
     * The header on all five call sites. One interceptor on the one {@code RestClient} is what makes this
     * hold for a sixth client added later, and the assertion is per-site because "five places that must
     * agree" is the failure this replaced.
     */
    @Test
    @Order(6)
    void everyAdasCallSiteDeclaresTheContractVersion() {
        stubPipelines(pipelineJson());
        ADAS.stubFor(post(urlPathEqualTo(path("scan")))
                .willReturn(okJson("{\"rows\":[],\"next_cursor\":\"c1\",\"has_more\":false}")));
        ADAS.stubFor(post(urlPathEqualTo(path("group-rows")))
                .willReturn(okJson("{\"rows\":[],\"truncated\":false}")));
        ADAS.stubFor(post(urlPathEqualTo(path("materialize")))
                .willReturn(okJson("{\"rows_written\":0,\"has_more\":false}")));
        ADAS.stubFor(post(urlPathEqualTo(path("rows"))).willReturn(okJson("{\"inserted\":1}")));

        syncService.sync();
        scanClient.scan(PIPELINE_NAME, null, 10);
        groupFetchClient.fetch(PIPELINE_NAME, "chat-a", 10);
        materializeClient.materialize(PIPELINE_NAME, null, 10);
        upsertClient.upsert(PIPELINE_NAME, List.of(Map.of("event_id", "e1")));

        String declared = Integer.toString(AdasContract.VERSION);
        ADAS.verify(getRequestedFor(urlPathEqualTo("/v1/pipelines"))
                .withHeader(AdasContract.HEADER, equalTo(declared)));
        for (String endpoint : List.of("scan", "group-rows", "materialize", "rows")) {
            ADAS.verify(postRequestedFor(urlPathEqualTo(path(endpoint)))
                    .withHeader(AdasContract.HEADER, equalTo(declared)));
        }
    }

    /**
     * A <em>data-plane</em> call flips the state, not only the poll. This is the whole argument for the
     * interceptor owning the verdict: a skew beginning between polls is met first by whichever call comes
     * first, and that is usually a scan tick or a write-back. Recorded only by the poll, the paid-work gate
     * would read a stale {@code compatible} for up to a poll interval and let work through it.
     */
    @Test
    @Order(7)
    @SuppressWarnings({"ConstantValue", "DataFlowIssue"}) // the IDE cannot see the scan inside the lambda flip the state
    void dataPlaneCallFlipsTheStateSoTheGateCannotReadStaleVerdict() {
        ADAS.stubFor(post(urlPathEqualTo(path("scan")))
                .willReturn(okJson("{\"rows\":[],\"next_cursor\":\"c1\",\"has_more\":false}")));
        ECHO.serve(null);
        assertThat(compatibilityState.isCompatible()).isTrue();

        assertThatThrownBy(() -> scanClient.scan(PIPELINE_NAME, null, 10))
                .isInstanceOf(AdasContractMismatchException.class);

        assertThat(compatibilityState.isCompatible()).isFalse();
        assertThat(compatibilityGauge()).isZero();
    }

    /**
     * An ADAS failure that is <em>not</em> a skew stays one. ADAS sets the echo ahead of its security chain,
     * so a 401 from a wrong API key carries it; the runner checks the echo and not the status, so it
     * inherits that property rather than having to classify.
     */
    @Test
    @Order(8)
    void anAdasErrorCarryingTheEchoStaysAnOrdinaryFailure() {
        ADAS.stubFor(post(urlPathEqualTo(path("scan")))
                .willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> scanClient.scan(PIPELINE_NAME, null, 10))
                .isNotInstanceOf(AdasContractMismatchException.class);

        assertThat(compatibilityState.isCompatible()).isTrue();
        assertThat(compatibilityGauge()).isEqualTo(1);
    }

    private static String path(String endpoint) {
        return "/v1/pipelines/" + PIPELINE_NAME + "/" + endpoint;
    }

    private double compatibilityGauge() {
        return meterRegistry.get("runner.adas.contract.compatible").gauge().value();
    }

    private long countLogs(String text) {
        return appender.messages().stream().filter(m -> m != null && m.contains(text)).count();
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

    private static String pipelineJson() {
        return """
                {
                  "name":"%s","kind":"enrich",
                  "transform":{"type":"llm","preset":"chat_completion","model":"gpt-4o",
                    "request_template":"{\\"messages\\":[]}",
                    "inputs":{"text":{"column":"request_body"}},
                    "outputs":{"label":{"prose":"the label"}}},
                  "response_schema":{"type":"object","additionalProperties":false,
                    "properties":{"label":{"type":"string"}}},
                  "target":"enr_%s","inputs":["dial_usage_log"],"grain_key":"event_id",
                  "version_column":"_ingested_at",
                  "trigger":{"kind":"schedule","cron":"10 * * * * *"},
                  "outputs":[{"name":"label","column":"label"}],
                  "advanced":{"rows_per_scan":1000},
                  "enabled":true,"generation":1,
                  "created_at":"2026-07-01T00:00:00Z","updated_at":"2026-07-01T00:00:00Z"
                }
                """.formatted(PIPELINE_NAME, PIPELINE_NAME);
    }
}
