package com.epam.aidial.enrichment.worker.upsert;

import com.epam.aidial.enrichment.adas.dto.OutputDto;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ReadyWhenDto;
import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.dlq.DlqRequeueService;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.support.UpsertDrain;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.ChunkPayload;
import com.epam.aidial.enrichment.worker.ChunkRow;
import com.epam.aidial.enrichment.worker.WorkerService;
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
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H1's exit criterion, and the test that must fail if the phases are ever re-merged: an upsert retry re-attempts
 * the write from the rows it already holds and makes <strong>no</strong> further model call.
 *
 * <p>Under the previous single-phase worker an ADAS 5xx re-ran the whole item, so a flaky write cost one model
 * call per attempt — five, at the default retry budget, to land one row.
 */
@SpringBootTest
class TwoPhaseUpsertIntegrationTest {

    private static final WireMockServer BACKEND = AdasWireMock.server();
    private static final String PIPELINE_NAME = "r-twophase";
    private static final String ROWS_PATH = "/v1/pipelines/" + PIPELINE_NAME + "/rows";
    private static final String CHAT_PATH = "/openai/deployments/gpt-4o/chat/completions";
    private static final String GROUP_KEY = "chat-a";
    private static final long GROUP_VERSION = 1_700_000_000_000L;

    @Autowired
    private WorkerService workerService;

    @Autowired
    private UpsertService upsertService;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private PipelineCache pipelineCache;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private PipelinePauseRegistry pauseRegistry;

    @Autowired
    private DlqRequeueService dlqRequeueService;

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
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, dlq_item, spend_counter, group_state");
        pauseRegistry.resume(PIPELINE_NAME);
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, pipeline()));
    }

    @Test
    void upsertRetryDoesNotReCallTheModel() {
        stubDial();
        // The write fails, then succeeds — exactly the flaky-write case that used to cost a second model call.
        BACKEND.stubFor(post(urlPathEqualTo(ROWS_PATH)).inScenario("flaky")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        BACKEND.stubFor(post(urlPathEqualTo(ROWS_PATH)).inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson("{\"inserted\":1}")));

        workerService.process("chunk-1", chunk());
        int dialCallsAfterCompute = dialCalls();
        assertThat(dialCallsAfterCompute).isEqualTo(1);

        // First write attempt fails transiently.
        assertThatThrownBy(this::drain).isInstanceOf(RuntimeException.class);
        // Second attempt succeeds — from the payload, with no model involvement.
        drain();

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROWS_PATH)))).hasSize(2);
        assertThat(dialCalls())
                .as("a write retry must not re-call the model")
                .isEqualTo(dialCallsAfterCompute);
    }

    @Test
    void computeQueuesExactlyOneWritePerChunkEvenIfRepeated() {
        stubDial();
        stubRows();

        workerService.process("chunk-1", chunk());
        workerService.process("chunk-1", chunk());   // a compute retry before the write ran

        assertThat(queuedWrites()).isEqualTo(1);
    }

    @Test
    void nonRetryableRejectionDeadLettersWriteWithPayload() {
        stubDial();
        BACKEND.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(aResponse().withStatus(422)));

        workerService.process("chunk-1", chunk());
        drain();

        // Retained with its payload, so it is requeueable once the pipeline is fixed — without recomputing.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stage FROM dlq_item WHERE pipeline_name = ?", String.class, PIPELINE_NAME)).isEqualTo("upsert");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT task_name FROM dlq_item WHERE pipeline_name = ?", String.class, PIPELINE_NAME))
                .isEqualTo(UpsertRowsTask.TASK_NAME);
    }

    @Test
    void rejectedGroupWriteStampsAndChargesInsteadOfLoopingForever() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, groupPipeline()));
        seedDirtyGroup();
        BACKEND.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(aResponse().withStatus(422)));

        upsertService.write("w-1", groupWrite());

        // Left dirty, the sweep would re-enqueue this group every pass, re-assemble it and call the model again
        // to reach the same rejection — a spend loop invisible in the DLQ, because the row collapses on its
        // identity key. Stamping it bounds the retry to "when the group actually changes".
        assertThat(computedVersion()).isEqualTo(GROUP_VERSION);
        assertThat(evaluationsCharged()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT task_name FROM dlq_item WHERE pipeline_name = ?", String.class, PIPELINE_NAME))
                .isEqualTo(UpsertRowsTask.TASK_NAME);
        // One group's rejection must not punish every other group on the pipeline: routed through the fraction
        // lever it would read as 100% failure and pause it outright.
        assertThat(pauseRegistry.isPaused(PIPELINE_NAME)).isFalse();
    }

    @Test
    void repeatedGroupWriteRejectionUpsertsItsDeadLetterRatherThanFailing() {
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, groupPipeline()));
        seedDirtyGroup();
        BACKEND.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(aResponse().withStatus(422)));

        upsertService.write("w-1", groupWrite());
        // A second rejection for the same group at the same pipeline generation collides with dlq_item's partial
        // unique key; as a plain insert it threw, which turned a permanent rejection into an endless retry.
        upsertService.write("w-1", groupWrite());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dlq_item WHERE pipeline_name = ?", Long.class, PIPELINE_NAME)).isEqualTo(1);
    }

    @Test
    void absentPipelineArchivesTheComputedWriteInsteadOfDroppingIt() {
        // The pipeline is disabled in ADAS between the compute and the write. Its sync feed omits a disabled pipeline
        // exactly as it omits a deleted one, so the runner evicts it and this lookup misses — while the label
        // in hand has already been paid for and exists nowhere else.
        pipelineCache.replaceAll(Map.of());
        seedDirtyGroup();

        upsertService.write("w-1", groupWrite());

        // Kept with its payload and its task name, so a requeue re-attempts the write once the pipeline is back
        // and recomputes nothing. Dropping it instead destroyed the label outright.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stage FROM dlq_item WHERE pipeline_name = ?", String.class, PIPELINE_NAME)).isEqualTo("upsert");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT task_name FROM dlq_item WHERE pipeline_name = ?", String.class, PIPELINE_NAME))
                .isEqualTo(UpsertRowsTask.TASK_NAME);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload FROM dlq_item WHERE pipeline_name = ?", String.class, PIPELINE_NAME)).contains(GROUP_KEY);
        // Nothing reached ADAS, so the group's evaluation state is untouched, and the pipeline is not paused: a
        // pause recorded now would outlive the pipeline's return and hold it back silently.
        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROWS_PATH)))).isEmpty();
        assertThat(computedVersion()).isNull();
        assertThat(pauseRegistry.isPaused(PIPELINE_NAME)).isFalse();
    }

    @Test
    void archivedWriteIsRecoveredByRequeueWithoutRecomputing() {
        // The other half of the archive's reason to exist: a bulk requeue that ran while the pipeline was
        // disabled used to discard every paid-for label it held. Once the pipeline is back the only
        // thing left to do is re-attempt the write, from rows the payload already holds.
        pipelineCache.replaceAll(Map.of());
        seedDirtyGroup();
        upsertService.write("w-1", groupWrite());

        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, groupPipeline()));
        stubRows();
        assertThat(dlqRequeueService.requeueByPipeline(PIPELINE_NAME)).isEqualTo(1);
        assertThat(drain()).isEqualTo(1);

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROWS_PATH)))).hasSize(1);
        assertThat(dialCalls()).as("recovery must re-post rows, not re-derive them").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dlq_item WHERE pipeline_name = ?", Long.class, PIPELINE_NAME)).isZero();
        // The write is what stamps a group clean, so the recovered write also closes the group out rather
        // than leaving it dirty for the sweep to evaluate all over again.
        assertThat(computedVersion()).isEqualTo(GROUP_VERSION);
    }

    @Test
    void pipelinePinnedToNewVersionBetweenPhasesGetsNoStaleRow() {
        stubDial();
        stubRows();

        workerService.process("chunk-1", chunk());
        // The pipeline is re-declared at a higher generation before the write executes.
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, TestPipelines.builder(PIPELINE_NAME).generation(4).build()));
        drain();

        assertThat(BACKEND.findAll(postRequestedFor(urlPathEqualTo(ROWS_PATH)))).isEmpty();
    }

    private static ChunkPayload chunk() {
        return new ChunkPayload(PIPELINE_NAME, 3, List.of(new ChunkRow("e0", Map.of("prompt", "great product"))));
    }

    private static PipelineDto groupPipeline() {
        return TestPipelines.builder(PIPELINE_NAME)
                .generation(3)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .grainKey("chat_id")
                .readyWhen(new ReadyWhenDto(null, "10m", null, 5))
                .model("gpt-4o")
                .build();
    }

    private static UpsertPayload groupWrite() {
        return new UpsertPayload(PIPELINE_NAME, 3, List.of(Map.of("chat_id", GROUP_KEY, "topic", "billing")),
                GROUP_KEY, GROUP_VERSION, false);
    }

    private void seedDirtyGroup() {
        jdbcTemplate.update("INSERT INTO group_state (pipeline_name, group_key, group_version, last_activity_at) "
                + "VALUES (?, ?, ?, now())", PIPELINE_NAME, GROUP_KEY, GROUP_VERSION);
    }

    private Long computedVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT computed_version FROM group_state WHERE pipeline_name = ? AND group_key = ?",
                Long.class, PIPELINE_NAME, GROUP_KEY);
    }

    private Integer evaluationsCharged() {
        return jdbcTemplate.queryForObject(
                "SELECT evaluations FROM group_state WHERE pipeline_name = ? AND group_key = ?",
                Integer.class, PIPELINE_NAME, GROUP_KEY);
    }

    private static PipelineDto pipeline() {
        return TestPipelines.builder(PIPELINE_NAME)
                .generation(3)
                .model("gpt-4o")
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"Classify: {{prompt}}\"}]}")
                .responseSchema(Map.of(
                        "type", "object",
                        "required", List.of("label"),
                        "properties", Map.of("label", Map.of("type", "string"))))
                .transformInputs(TestPipelines.columnVar("prompt", "request_body"))
                .outputs(List.of(new OutputDto("label", "sentiment", null)))
                .build();
    }

    /** A DIAL completion labelling the row {@code positive}. */
    private void stubDial() {
        String body = jsonMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", "{\"label\":\"positive\"}"))),
                "usage", Map.of("total_tokens", 7)));
        BACKEND.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(okJson(body)));
    }

    private void stubRows() {
        BACKEND.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(okJson("{\"inserted\":1}")));
    }

    /** Executes every queued write, returning how many ran. */
    private int drain() {
        return new UpsertDrain(scheduler, upsertService).drain();
    }

    private int dialCalls() {
        return BACKEND.findAll(postRequestedFor(urlPathMatching("/openai/deployments/.*"))).size();
    }

    private long queuedWrites() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", Long.class,
                UpsertRowsTask.TASK_NAME);
        return count == null ? 0 : count;
    }
}
