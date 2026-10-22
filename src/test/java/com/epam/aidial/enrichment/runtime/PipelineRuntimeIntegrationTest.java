package com.epam.aidial.enrichment.runtime;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ReadyWhenDto;
import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.class1.Class1MaterializeTask;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.observability.status.PipelineStatusReporter;
import com.epam.aidial.enrichment.pause.PauseOrigin;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemRepository;
import com.epam.aidial.enrichment.persistence.PipelineCursorEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.scan.PipelineScanTask;
import com.epam.aidial.enrichment.spend.SpendService;
import com.epam.aidial.enrichment.support.EventCapture;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.upsert.UpsertRowsTask;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The runtime view across the three lanes: which sections each lane carries, what each count is counted from, and the
 * fields that fall back to durable state after a restart.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PipelineRuntimeIntegrationTest {

    private static final String ROW = "rt-row";
    private static final String GROUP = "rt-group";
    private static final String CLASS1 = "rt-class1";
    private static final String BUDGETED = "rt-budgeted";
    private static final String UNPARSEABLE = "rt-unparseable";
    private static final long CALL_BUDGET = 5;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PipelineCache pipelineCache;

    @Autowired
    private PipelineRunOutcome runOutcome;

    @Autowired
    private PipelinePauseRegistry pauseRegistry;

    @Autowired
    private PipelineCursorRepository cursorRepository;

    @Autowired
    private DlqItemRepository dlqItemRepository;

    @Autowired
    private SpendService spendService;

    @Autowired
    private PipelineStatusReporter statusReporter;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
        registry.add("spend.pause.enabled", () -> "true");
        registry.add("spend.budget.daily-calls", () -> String.valueOf(CALL_BUDGET));
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, dlq_item, run_history, group_state, spend_counter, pipeline_cursor");
        PipelineDto row = TestPipelines.builder(ROW).rowsPerCall(null).build();
        PipelineDto group = TestPipelines.builder(GROUP)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("conversation_id").grainKey("conversation_id")
                .readyWhen(new ReadyWhenDto(null, "10m", null, 1))
                .build();
        PipelineDto class1 = TestPipelines.class1(CLASS1, 2, TestPipelines.EVERY_5_MINUTES);
        PipelineDto budgeted = TestPipelines.schedule(BUDGETED, 1, TestPipelines.EVERY_MINUTE);
        PipelineDto unparseable = TestPipelines.builder(UNPARSEABLE)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("conversation_id").grainKey("conversation_id")
                .readyWhen(new ReadyWhenDto(null, "not-a-duration", null, null))
                .build();
        pipelineCache.replaceAll(Map.of(ROW, row, GROUP, group, CLASS1, class1, BUDGETED, budgeted, UNPARSEABLE, unparseable));
        runOutcome.retain(Set.of());
        for (String name : List.of(ROW, GROUP, CLASS1, BUDGETED)) {
            pauseRegistry.resume(name);
        }
    }

    @Test
    void rowPipelineReportsItsQueueNextToItsLag() throws Exception {
        cursor(ROW, 0L);
        runOutcome.recordSuccess(ROW, false);
        recurring(PipelineScanTask.TASK_NAME, ROW, Instant.now().plusSeconds(30), false, 0, null);
        String prefix = DeterministicId.pipelinePrefix(ROW);
        work(EnrichChunkTask.TASK_NAME, prefix + "a", 0);
        work(EnrichChunkTask.TASK_NAME, prefix + "b", 1);
        work(UpsertRowsTask.TASK_NAME, prefix + "c", 0);
        // Two live dead letters count; a backfill run's does not.
        deadLetter("e1", null, "validate", true);
        deadLetter("e2", null, "dial_call", false);
        deadLetter("e3", "run-1", "dial_call", true);
        spendService.record(ROW, 1, 100, false);
        spendService.record(ROW, 1, 50, false);
        spendService.record(ROW, 1, 0, true);

        mockMvc.perform(get("/v1/pipelines/{name}/runtime", ROW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipeline_name").value(ROW))
                .andExpect(jsonPath("$.lane").value("row"))
                .andExpect(jsonPath("$.status.state").value("active"))
                .andExpect(jsonPath("$.status.stalled").doesNotExist())
                .andExpect(jsonPath("$.status.origin").doesNotExist())
                .andExpect(jsonPath("$.schedule.next_run_at").exists())
                .andExpect(jsonPath("$.schedule.running_now").value(false))
                .andExpect(jsonPath("$.schedule.consecutive_failures").value(0))
                .andExpect(jsonPath("$.schedule.last_error").doesNotExist())
                .andExpect(jsonPath("$.progress.lag_seconds").value(0))
                .andExpect(jsonPath("$.progress.has_more").value(false))
                .andExpect(jsonPath("$.progress.caught_up_at").exists())
                .andExpect(jsonPath("$.queue.computing").value(2))
                .andExpect(jsonPath("$.queue.awaiting_write").value(1))
                .andExpect(jsonPath("$.queue.retrying").doesNotExist())
                .andExpect(jsonPath("$.groups").doesNotExist())
                .andExpect(jsonPath("$.spend_today.calls").value(2))
                .andExpect(jsonPath("$.spend_today.tokens").value(150))
                .andExpect(jsonPath("$.spend_today.failed_calls").value(1))
                .andExpect(jsonPath("$.spend_today.budget").doesNotExist())
                .andExpect(jsonPath("$.failures.total").value(2))
                .andExpect(jsonPath("$.failures.requeueable_total").value(1))
                .andExpect(jsonPath("$.failures.by_stage").doesNotExist())
                .andExpect(jsonPath("$.failures.last").doesNotExist())
                .andExpect(jsonPath("$.backfill").doesNotExist())
                .andExpect(jsonPath("$.settings").doesNotExist());

        // The totals are the DLQ listing's own for this pipeline and lane live.
        mockMvc.perform(get("/v1/dlq").param("pipeline_name", ROW).param("lane", "live"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.requeueable_total").value(1));
    }

    @Test
    void groupPipelineCountsReadinessAsTheSweepWould() throws Exception {
        Instant now = Instant.now();
        Instant quiet = now.minus(Duration.ofHours(1));
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        group("g-ready", 5, null, quiet, null, 0);
        group("g-idle", 5, null, now, null, 0);
        group("g-blocked", 5, 3L, quiet, today, 1);
        group("g-clean", 2, 2L, quiet, today, 1);

        mockMvc.perform(get("/v1/pipelines/{name}/runtime", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lane").value("group"))
                .andExpect(jsonPath("$.groups.pending").value(3))
                .andExpect(jsonPath("$.groups.ready").value(1))
                .andExpect(jsonPath("$.groups.waiting_idle").value(1))
                .andExpect(jsonPath("$.groups.ceiling_blocked").value(1))
                .andExpect(jsonPath("$.groups.tracked").doesNotExist())
                .andExpect(jsonPath("$.groups.evaluations_today").doesNotExist())
                .andExpect(jsonPath("$.groups.oldest_pending_at").doesNotExist())
                .andExpect(jsonPath("$.groups.last_evaluated_at").doesNotExist())
                .andExpect(jsonPath("$.queue").exists())
                .andExpect(jsonPath("$.failures").exists())
                .andExpect(jsonPath("$.settings").doesNotExist());
    }

    @Test
    void groupQueueCountsTheLiveWorkBackpressureCounts() throws Exception {
        String groupItem = DeterministicId.groupInstanceId(GROUP, 1, "g1", 5);
        work(GroupChunkTask.TASK_NAME, groupItem, 1);
        work(UpsertRowsTask.TASK_NAME, DeterministicId.upsertInstanceId(groupItem), 0);
        // A requeued backfill item keeps the pipeline prefix and counts; a backfill run's own chunk does not.
        work(EnrichChunkTask.TASK_NAME, DeterministicId.requeueInstanceId(GROUP, 7L, "run-1"), 0);
        work(EnrichChunkTask.TASK_NAME, DeterministicId.backfillChunkInstanceId("run-1", 1, null, 0), 0);

        mockMvc.perform(get("/v1/pipelines/{name}/runtime", GROUP))
                .andExpect(jsonPath("$.queue.computing").value(2))
                .andExpect(jsonPath("$.queue.awaiting_write").value(1))
                .andExpect(jsonPath("$.queue.retrying").doesNotExist());
    }

    /** One bad duration fails that pipeline's sweeps; it must not also fail the read that would explain it. */
    @Test
    void unparseableReadinessOmitsOnlyWhatDependsOnIt() throws Exception {
        mockMvc.perform(get("/v1/pipelines/{name}/runtime", UNPARSEABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lane").value("group"))
                .andExpect(jsonPath("$.groups").doesNotExist())
                .andExpect(jsonPath("$.queue").exists())
                .andExpect(jsonPath("$.status.state").exists());
    }

    @Test
    void class1PipelineReportsItsFailureWithoutTheLlmSections() throws Exception {
        Instant failedAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        recurring(Class1MaterializeTask.TASK_NAME, CLASS1, Instant.now().plusSeconds(60), false, 2, failedAt);
        runOutcome.recordFailure(CLASS1, "IllegalStateException");

        mockMvc.perform(get("/v1/pipelines/{name}/runtime", CLASS1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lane").value("class1"))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.schedule.consecutive_failures").value(2))
                .andExpect(jsonPath("$.schedule.last_error").value("IllegalStateException"))
                .andExpect(jsonPath("$.queue").doesNotExist())
                .andExpect(jsonPath("$.spend_today").doesNotExist())
                .andExpect(jsonPath("$.failures").doesNotExist())
                .andExpect(jsonPath("$.groups").doesNotExist())
                .andExpect(jsonPath("$.backfill").doesNotExist())
                .andExpect(jsonPath("$.settings").doesNotExist())
                .andExpect(jsonPath("$.status.stalled").doesNotExist());

        // The next fire succeeds: db-scheduler resets its count and the outcome clears the error.
        jdbcTemplate.update("UPDATE scheduled_tasks SET consecutive_failures = 0 WHERE task_instance = ?", CLASS1);
        runOutcome.recordSuccess(CLASS1, false);

        mockMvc.perform(get("/v1/pipelines/{name}/runtime", CLASS1))
                .andExpect(jsonPath("$.schedule.consecutive_failures").value(0))
                .andExpect(jsonPath("$.schedule.last_error").doesNotExist())
                .andExpect(jsonPath("$.schedule.last_error_at").doesNotExist());
    }

    @Test
    void runningFireHasNoNextRun() throws Exception {
        recurring(Class1MaterializeTask.TASK_NAME, CLASS1, Instant.now().minusSeconds(5), true, 0, null);

        mockMvc.perform(get("/v1/pipelines/{name}/runtime", CLASS1))
                .andExpect(jsonPath("$.schedule.running_now").value(true))
                .andExpect(jsonPath("$.schedule.next_run_at").doesNotExist());
    }

    @Test
    void failingPipelineKeepsItsFailureTimeAcrossRestart() throws Exception {
        Instant failedAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        cursor(CLASS1, 42L);
        recurring(Class1MaterializeTask.TASK_NAME, CLASS1, Instant.now().plusSeconds(60), false, 2, failedAt);

        String body = mockMvc.perform(get("/v1/pipelines/{name}/runtime", CLASS1))
                .andExpect(jsonPath("$.schedule.consecutive_failures").value(2))
                .andExpect(jsonPath("$.schedule.last_error").doesNotExist())
                .andExpect(jsonPath("$.progress.has_more").doesNotExist())
                .andExpect(jsonPath("$.progress.caught_up_at").doesNotExist())
                .andExpect(jsonPath("$.progress.lag_seconds").value(42))
                .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(body, "$.schedule.last_error_at"))).isEqualTo(failedAt);
    }

    /**
     * A failure no executor catches (a JVM Error) still counts in db-scheduler's row while the last recorded outcome is
     * a success, so the failure instant comes from the row.
     */
    @Test
    void failureTheOutcomeMissedStillReportsItsTime() throws Exception {
        Instant failedAt = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MILLIS);
        runOutcome.recordSuccess(CLASS1, false);
        recurring(Class1MaterializeTask.TASK_NAME, CLASS1, Instant.now().plusSeconds(60), false, 1, failedAt);

        String body = mockMvc.perform(get("/v1/pipelines/{name}/runtime", CLASS1))
                .andExpect(jsonPath("$.schedule.consecutive_failures").value(1))
                .andExpect(jsonPath("$.schedule.last_error").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(body, "$.schedule.last_error_at"))).isEqualTo(failedAt);
    }

    @Test
    void recoveredPipelineReportsNoFailureAfterRestart() throws Exception {
        recurring(Class1MaterializeTask.TASK_NAME, CLASS1, Instant.now().plusSeconds(60), false, 0,
                Instant.now().minus(Duration.ofHours(1)));

        mockMvc.perform(get("/v1/pipelines/{name}/runtime", CLASS1))
                .andExpect(jsonPath("$.schedule.last_error").doesNotExist())
                .andExpect(jsonPath("$.schedule.last_error_at").doesNotExist());
    }

    @Test
    void theStatusAgreesWithTheHeartbeat() throws Exception {
        for (int i = 0; i < CALL_BUDGET; i++) {
            spendService.record(BUDGETED, 1, 10, false);
        }

        mockMvc.perform(get("/v1/pipelines/{name}/runtime", BUDGETED))
                .andExpect(jsonPath("$.status.state").value("over_budget"));

        EventCapture capture = EventCapture.attach();
        try {
            statusReporter.report();
            assertThat(capture.events().stream().map(e -> e.getMessage().getFormattedMessage()))
                    .anySatisfy(line -> assertThat(line).startsWith("Pipeline status | " + BUDGETED + " (row, gen 1) | over budget |"));
        } finally {
            capture.detach();
        }
    }

    @Test
    void breakerPauseIsReportedWithItsOriginAndResumeTime() throws Exception {
        pauseRegistry.pauseUntil(ROW, PauseOrigin.BREAKER, "3 of its last 4 chunks dead-lettered",
                Instant.now().plus(Duration.ofMinutes(15)));

        mockMvc.perform(get("/v1/pipelines/{name}/runtime", ROW))
                .andExpect(jsonPath("$.status.state").value("paused"))
                .andExpect(jsonPath("$.status.origin").value("breaker"))
                .andExpect(jsonPath("$.status.reason").value("3 of its last 4 chunks dead-lettered"))
                .andExpect(jsonPath("$.status.since").exists())
                .andExpect(jsonPath("$.status.resumes_at").exists())
                .andExpect(jsonPath("$.status.stalled").doesNotExist());
    }

    @Test
    void thePausedListingIsSnakeCase() throws Exception {
        pauseRegistry.pauseUntil(ROW, PauseOrigin.BREAKER, "tripped", Instant.now().plus(Duration.ofMinutes(15)));

        mockMvc.perform(get("/v1/pipelines/paused"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused[0].pipeline_name").value(ROW))
                .andExpect(jsonPath("$.paused[0].origin").value("breaker"))
                .andExpect(jsonPath("$.paused[0].reason").value("tripped"))
                .andExpect(jsonPath("$.paused[0].since").exists())
                .andExpect(jsonPath("$.paused[0].resumes_at").exists())
                .andExpect(jsonPath("$.paused[0].pipelineName").doesNotExist())
                .andExpect(jsonPath("$.paused[0].resumesAt").doesNotExist());
    }

    /** The view no longer carries a backfill section, even while the pipeline has active runs. */
    @Test
    void activeBackfillRunsAddNoBackfillSection() throws Exception {
        Instant started = Instant.now().minus(Duration.ofHours(2));
        backfillRun("run-running", "running", started);
        backfillRun("run-paused", "paused", started.plus(Duration.ofHours(1)));

        mockMvc.perform(get("/v1/pipelines/{name}/runtime", ROW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.state").exists())
                .andExpect(jsonPath("$.backfill").doesNotExist());
    }

    @Test
    void uncachedPipelineIsNotFound() throws Exception {
        mockMvc.perform(get("/v1/pipelines/{name}/runtime", "absent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    private void cursor(String pipelineName, Long lagSeconds) {
        PipelineCursorEntity cursor = new PipelineCursorEntity(pipelineName);
        cursor.setCursor("c1");
        cursor.setUpdatedAt(Instant.now());
        cursor.setLastScanAt(Instant.now());
        cursor.setLagSeconds(lagSeconds);
        cursorRepository.save(cursor);
    }

    private void recurring(String taskName, String pipelineName, Instant executionTime, boolean picked, int failures,
                           Instant lastFailure) {
        jdbcTemplate.update("""
                        INSERT INTO scheduled_tasks (task_name, task_instance, execution_time, picked, consecutive_failures,
                                                     last_failure, version)
                        VALUES (?, ?, ?, ?, ?, ?, 1)
                        """, taskName, pipelineName, Timestamp.from(executionTime), picked, failures,
                lastFailure == null ? null : Timestamp.from(lastFailure));
    }

    private void work(String taskName, String instanceId, int failures) {
        recurring(taskName, instanceId, Instant.now(), false, failures, null);
    }

    private void deadLetter(String grainKey, String runId, String stage, boolean withPayload) {
        dlqItemRepository.save(DlqItemEntity.builder()
                .pipelineName(ROW).pipelineGeneration(1L).grainKey(grainKey).stage(stage).error("rejected")
                .runId(runId).payload(withPayload ? "{}" : null).rawResponse(withPayload ? "{\"label\":1}" : null).build());
    }

    private void group(String key, long version, Long computedVersion, Instant lastActivity, LocalDate evaluationsDay,
                       int evaluations) {
        jdbcTemplate.update("""
                        INSERT INTO group_state (pipeline_name, group_key, group_version, last_activity_at, computed_version,
                                                 computed_at, evaluations_day, evaluations)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, GROUP, key, version, Timestamp.from(lastActivity), computedVersion,
                computedVersion == null ? null : Timestamp.from(lastActivity), evaluationsDay, evaluations);
    }

    private void backfillRun(String id, String state, Instant startedAt) {
        jdbcTemplate.update("""
                        INSERT INTO run_history (id, pipeline_name, kind, state, started_at, paused_reason, paused_at)
                        VALUES (?, ?, 'backfill', ?, ?, ?, ?)
                        """, id, ROW, state, Timestamp.from(startedAt), "paused".equals(state) ? "operator" : null,
                "paused".equals(state) ? Timestamp.from(startedAt) : null);
    }
}
