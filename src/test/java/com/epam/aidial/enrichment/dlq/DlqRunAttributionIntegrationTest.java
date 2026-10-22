package com.epam.aidial.enrichment.dlq;

import com.epam.aidial.enrichment.backfill.BackfillPageCommitter;
import com.epam.aidial.enrichment.backfill.RunState;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemRepository;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.persistence.RunHistoryEntity;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.epam.aidial.enrichment.scan.ChunkBatch;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.scan.group.GroupMarkBatch;
import com.epam.aidial.enrichment.support.TestPages;
import com.epam.aidial.enrichment.worker.ChunkFailureHandler;
import com.epam.aidial.enrichment.worker.ChunkPayload;
import com.epam.aidial.enrichment.worker.ChunkRow;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.upsert.UpsertRowsTask;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A dead letter records the backfill run that produced it — from the run's own page commit, or from the work
 * item's instance id — and keeps it across a requeue; the listing and the requeue narrow by run and by lane.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DlqRunAttributionIntegrationTest {

    private static final String PIPELINE = "p-attr";

    @Autowired
    private BackfillPageCommitter committer;

    @Autowired
    private RunHistoryRepository runHistoryRepository;

    @Autowired
    private DlqItemRepository dlqItemRepository;

    @Autowired
    private DlqItemWriter dlqItemWriter;

    @Autowired
    private DlqRequeueService requeueService;

    @Autowired
    private ChunkFailureHandler chunkFailureHandler;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, dlq_item, run_history, group_state");
    }

    @Test
    void backfillPageCommitStampsItsDeadLettersWithTheRunOnTheChunkAndGroupMarkPaths() {
        String runId = runningRun();
        committer.commit(runId, PIPELINE, 3, null, "c1", new ChunkBatch(List.of(),
                List.of(poisonRow("e0")), TestPages.workAndDeadLetters(0, 1)));
        committer.commitGroupMarks(runId, PIPELINE, 3, "c1", "c2", new GroupMarkBatch(List.of(),
                new ArrayList<>(List.of(poisonRow("chat-a"))), TestPages.workAndDeadLetters(0, 1)));

        assertThat(dlqItemRepository.findAll()).hasSize(2)
                .allSatisfy(item -> assertThat(item.getRunId()).isEqualTo(runId));
    }

    @Test
    void grainFailingInBothLanesStaysOneRowNamingTheLastWriter() {
        dlqItemWriter.upsert(grainItem(null));
        dlqItemWriter.upsert(grainItem("run-1"));
        assertThat(dlqItemRepository.findAll()).singleElement()
                .satisfies(item -> assertThat(item.getRunId()).isEqualTo("run-1"));

        dlqItemWriter.upsert(grainItem(null));
        assertThat(dlqItemRepository.findAll()).singleElement()
                .satisfies(item -> assertThat(item.getRunId()).isNull());
    }

    /**
     * A live group's exhausted evaluation is requeueable; a replay then meets one bad row of the same group at
     * mark time. The second, payload-less failure moves the stage and error only — the row stays the live path's,
     * and requeueable.
     */
    @Test
    void payloadLessRepeatKeepsTheRequeueableItemItsTaskAndItsLane() {
        dlqItemWriter.upsert(DlqItemEntity.builder().pipelineName(PIPELINE).grainKey("chat-a").pipelineGeneration(3L)
                .stage("dial_call").error("exhausted").payload("{\"group\":\"chat-a\"}")
                .taskName(GroupChunkTask.TASK_NAME).build());
        dlqItemWriter.upsert(DlqItemEntity.builder().pipelineName(PIPELINE).grainKey("chat-a").pipelineGeneration(3L)
                .stage("input_map").error("unmappable").runId("run-1").build());

        assertThat(dlqItemRepository.findAll()).singleElement().satisfies(item -> {
            assertThat(item.getPayload()).isEqualTo("{\"group\":\"chat-a\"}");
            assertThat(item.getTaskName()).isEqualTo(GroupChunkTask.TASK_NAME);
            assertThat(item.getRunId()).isNull();
            assertThat(item.getStage()).isEqualTo("input_map");
            assertThat(item.getError()).isEqualTo("unmappable");
        });
    }

    @Test
    void repeatCarryingPayloadReplacesItAndNamesItsRun() {
        dlqItemWriter.upsert(DlqItemEntity.builder().pipelineName(PIPELINE).grainKey("chat-a").pipelineGeneration(3L)
                .stage("dial_call").error("exhausted").payload("{\"v\":1}")
                .taskName(GroupChunkTask.TASK_NAME).build());
        dlqItemWriter.upsert(DlqItemEntity.builder().pipelineName(PIPELINE).grainKey("chat-a").pipelineGeneration(3L)
                .stage("upsert").error("write failed").payload("{\"v\":2}")
                .taskName(UpsertRowsTask.TASK_NAME).runId("run-1").build());

        assertThat(dlqItemRepository.findAll()).singleElement().satisfies(item -> {
            assertThat(item.getPayload()).isEqualTo("{\"v\":2}");
            assertThat(item.getTaskName()).isEqualTo(UpsertRowsTask.TASK_NAME);
            assertThat(item.getRunId()).isEqualTo("run-1");
            assertThat(item.getStage()).isEqualTo("upsert");
        });
    }

    @Test
    void theListingSeparatesReplayFromWhatIsFailingNow() throws Exception {
        archive(PIPELINE, "run-1");
        archive(PIPELINE, "run-2");
        archive(PIPELINE, null);

        mockMvc.perform(get("/v1/dlq").param("run_id", "run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].run_id").value("run-1"));
        mockMvc.perform(get("/v1/dlq").param("lane", "live"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].run_id").doesNotExist());
        mockMvc.perform(get("/v1/dlq").param("lane", "BACKFILL").param("pipeline_name", PIPELINE))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void anImpossibleOrUnknownFilterIsRefused() throws Exception {
        mockMvc.perform(get("/v1/dlq").param("lane", "live").param("run_id", "run-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        mockMvc.perform(get("/v1/dlq").param("lane", "replay"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("live, backfill")));
        mockMvc.perform(post("/v1/dlq/requeue"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void requeueSelectsByRunByPipelineOrByBoth() {
        archive(PIPELINE, "run-1");
        archive(PIPELINE, "run-1");
        archive("p-other", "run-1");
        archive(PIPELINE, null);

        assertThat(requeueService.requeueBy(PIPELINE, "run-1")).isEqualTo(2);
        assertThat(requeueService.requeueBy(null, "run-1")).isEqualTo(1);
        assertThat(requeueService.requeueBy(PIPELINE, "run-9")).isZero();
        // The live item is left in place until the pipeline is named.
        assertThat(dlqItemRepository.findAll()).singleElement()
                .satisfies(item -> assertThat(item.getRunId()).isNull());
        assertThat(requeueService.requeueBy(PIPELINE, null)).isEqualTo(1);
    }

    @Test
    void requeuedRunItemThatFailsAgainKeepsItsRun() {
        DlqItemEntity item = archive(PIPELINE, "run-1");

        requeueService.requeue(item.getId());

        String instanceId = jdbcTemplate.queryForObject(
                "SELECT task_instance FROM scheduled_tasks WHERE task_name = ?", String.class, EnrichChunkTask.TASK_NAME);
        // Still counted toward the pipeline's live backpressure, and still the run's.
        assertThat(instanceId).startsWith(DeterministicId.pipelinePrefix(PIPELINE));
        assertThat(DeterministicId.backfillRunIdOf(instanceId)).isEqualTo("run-1");

        TaskInstance<ChunkPayload> instance = new TaskInstance<>(EnrichChunkTask.TASK_NAME, instanceId, payload());
        Execution execution = new Execution(Instant.now(), instance);
        execution.consecutiveFailures = 99;
        @SuppressWarnings("unchecked")
        ExecutionOperations<ChunkPayload> operations = mock(ExecutionOperations.class);
        chunkFailureHandler.onFailure(ExecutionComplete.failure(execution, Instant.now(), Instant.now(),
                new IllegalStateException("still failing")), operations);

        assertThat(dlqItemRepository.findAll()).singleElement()
                .satisfies(again -> assertThat(again.getRunId()).isEqualTo("run-1"));
    }

    private String runningRun() {
        RunHistoryEntity run = new RunHistoryEntity();
        run.setId("run-" + System.nanoTime());
        run.setPipelineName(PIPELINE);
        run.setKind("backfill");
        run.setState(RunState.RUNNING.dbValue());
        // The generation the commits pass: the page commit is conditional on it.
        run.setPipelineGeneration(3L);
        run.setStartedAt(Instant.now());
        return runHistoryRepository.save(run).getId();
    }

    private static DlqItemEntity poisonRow(String grainKey) {
        return DlqItemEntity.builder().pipelineName(PIPELINE).grainKey(grainKey).pipelineGeneration(3L)
                .stage("input_map").error("unmappable").build();
    }

    private static DlqItemEntity grainItem(String runId) {
        return DlqItemEntity.builder().pipelineName(PIPELINE).grainKey("e0").pipelineGeneration(3L)
                .stage("validate").error("bad output").runId(runId).build();
    }

    private DlqItemEntity archive(String pipelineName, String runId) {
        return dlqItemRepository.save(DlqItemEntity.builder()
                .pipelineName(pipelineName).pipelineGeneration(3L).stage("dial_call").error("exhausted")
                .runId(runId)
                .payload(jsonMapper.writeValueAsString(payload()))
                .build());
    }

    private static ChunkPayload payload() {
        return new ChunkPayload(PIPELINE, 3, List.of(new ChunkRow("e0", Map.of("prompt", "x"))));
    }
}
