package com.epam.aidial.enrichment.sync;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ReadyWhenDto;
import com.epam.aidial.enrichment.adas.dto.TransformPreset;
import com.epam.aidial.enrichment.adas.dto.TransformType;
import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.class1.Class1MaterializeTask;
import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.epam.aidial.enrichment.dlq.DlqCircuitBreaker;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.scan.PipelineScanTask;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.CapturingAppender;
import com.epam.aidial.enrichment.support.EventCapture;
import com.github.kagkarlsson.scheduler.ScheduledExecutionsFilter;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.PlainScheduleAndData;
import com.github.kagkarlsson.scheduler.task.schedule.CronSchedule;
import com.github.kagkarlsson.scheduler.task.schedule.CronStyle;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 exit-criteria test: enabled pipelines synced from ADAS become recurring {@code pipeline-scan}
 * instances, and a pipeline that disappears from the feed (disabled/deleted) has its instance cancelled.
 * ADAS is stubbed with WireMock; the runner's own PostgreSQL runs in Testcontainers. Also verifies
 * the case-insensitive enum binding of ADAS's lowercase wire values.
 */
@SpringBootTest
class PipelineSyncIntegrationTest {

    private static final WireMockServer ADAS = AdasWireMock.server();

    /** The shape of the cron ADAS stores on a row-grain pipeline by default: every minute, at one fixed second. */
    private static final String ROW_CRON = "10 * * * * *";

    /** A row fixture's {@code advanced}; a test strips it to model a declaration that set no knob. */
    private static final String ADVANCED = "\"advanced\":{\"rows_per_scan\":1000},";

    @Autowired
    private PipelineSyncService syncService;

    @Autowired
    private PipelineCache cache;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DlqCircuitBreaker dlqCircuitBreaker;

    @Autowired
    private PipelinePauseRegistry pauseRegistry;

    @Autowired
    private Environment environment;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private PipelineScanTask pipelineScanTask;

    @Autowired
    private GroupProperties groupProperties;

    @BeforeAll
    static void startAdas() {
        ADAS.start();
    }

    @AfterAll
    static void stopAdas() {
        ADAS.stop();
    }

    @BeforeEach
    void resetStubs() {
        ADAS.resetAll();
        // Scheduler rows and cursors outlive a test method (the code under test commits its own
        // transactions), so isolate by truncating rather than relying on reconcile to converge.
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, pipeline_cursor, group_state");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("adas.base-url", ADAS::baseUrl);
        // Keep the scheduler from firing and the background poll from racing the manual syncs.
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
        // Low enough that a test can reach it without 50 calls.
        registry.add("dlq.breaker.threshold", () -> "2");
    }

    @Test
    void enabledPipelinesBecomeRecurringTasksAndRemovedPipelinesAreCancelled() {
        stubPipelines(row("r1", 1)
                + "," + pipeline("r2", 1, "schedule", "0 0 3 * * *"));

        syncService.sync();

        assertThat(cache.size()).isEqualTo(2);
        assertThat(scanTaskCount()).isEqualTo(2);

        // Case-insensitive enum binding of ADAS's lowercase wire values.
        PipelineDto r1 = cache.get("r1");
        assertThat(r1.triggerKind()).isEqualTo(TriggerKind.SCHEDULE);
        assertThat(r1.triggerCron()).isEqualTo(ROW_CRON);
        assertThat(r1.transform().type()).isEqualTo(TransformType.LLM);
        assertThat(r1.transform().preset()).isEqualTo(TransformPreset.CHAT_COMPLETION);
        assertThat(r1.grainKey()).isEqualTo("event_id");
        // transform.inputs is the variable map; the top-level `inputs` is the read source and is not
        // modelled at all. A member read at the wrong level would bind to nothing.
        assertThat(r1.transformInputs()).containsOnlyKeys("prompt");
        assertThat(r1.responseSchema()).containsEntry("type", "object");

        // r2 disabled -> disappears from the feed -> its recurring task is cancelled.
        stubPipelines(row("r1", 1));
        syncService.sync();

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("r2")).isNull();
        assertThat(scanTaskCount()).isEqualTo(1);
    }

    /**
     * The {@code enabled=true} the poll sends is the first guard, not the only one: ADAS's unfiltered
     * listing carries disabled pipelines, so a fetch that ever loses the filter (a hand-run call, a future
     * incremental path) must still not schedule one. Belt-and-braces on purpose — a recurring task
     * firing against a pipeline the operator switched off is exactly the failure this pair prevents.
     */
    @Test
    void disabledPipelineInTheFeedIsNeitherCachedNorScheduled() {
        stubPipelines(row("live", 1)
                + "," + row("switched-off", 1).replace(
                        "\"enabled\":true", "\"enabled\":false"));

        syncService.sync();

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("live")).isNotNull();
        assertThat(cache.get("switched-off")).isNull();
        assertThat(scanTaskCount()).isEqualTo(1);
    }

    /**
     * Regression for the "disable the only enabled pipeline" bug: under {@code enabled=true} a disabled
     * pipeline leaves the feed, so disabling the last one yields an empty one. Full-list polling means empty
     * == zero enabled pipelines, which must reconcile to empty (cache cleared, task cancelled) rather than
     * be skipped.
     *
     * <p>It now takes a <em>confirming</em> poll. An empty feed stays authoritative — this test is what
     * says so — but the one-poll path from an unexpected empty answer to "every task cancelled, cache
     * cleared, readiness latched UP, nothing reporting that enrichment stopped" is gone.
     */
    @Test
    void twoConsecutiveEmptyFeedsEvictTheLastPipelineAndCancelItsTask() {
        stubPipelines(row("only", 1));
        syncService.sync();
        assertThat(cache.size()).isEqualTo(1);
        assertThat(scanTaskCount()).isEqualTo(1);

        stubPipelines("");
        syncService.sync();

        // The first empty feed is unconfirmed: nothing is cancelled and the cache still holds the pipeline.
        assertThat(cache.size()).isEqualTo(1);
        assertThat(scanTaskCount()).isEqualTo(1);

        syncService.sync();

        assertThat(cache.size()).isZero();
        assertThat(cache.get("only")).isNull();
        assertThat(scanTaskCount()).isZero();
    }

    /**
     * The feed is an object, not an array, and the difference is not cosmetic on this path. A bare array
     * parsed leniently into nothing would reconcile as "every pipeline was disabled" and cancel every
     * recurring task — a total outage produced by a body the poll accepted. Failing the poll instead lands
     * on the non-destructive branch, which is the one shape of wrong answer this service can survive.
     */
    @Test
    void bareArrayIsRejectedRatherThanReconciledToZero() {
        stubPipelines(row("kept", 1));
        syncService.sync();
        assertThat(cache.size()).isEqualTo(1);

        ADAS.stubFor(get(urlPathEqualTo("/v1/pipelines"))
                .withQueryParam("kind", equalTo("enrich"))
                .withQueryParam("enabled", equalTo("true"))
                .withQueryParam("view", equalTo("compiled"))
                .willReturn(okJson("[" + row("kept", 1) + "]")));

        syncService.sync();

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("kept")).isNotNull();
        assertThat(scanTaskCount()).isEqualTo(1);
    }

    /**
     * The poll's two knobs are string literals inside {@code @Scheduled} placeholders carrying defaults, so a
     * half-done rename does not fail at startup — it silently falls back to 30 s / 10 s, and inside this
     * suite to a 10 s initial delay that starts polling ADAS mid-test. Asserting the property resolves is
     * what turns that into a failure with a name on it.
     */
    @Test
    void thePollIntervalPropertyResolves() {
        assertThat(environment.getProperty("adas.pipelines.poll-interval-ms")).isNotBlank();
        assertThat(environment.getProperty("adas.pipelines.initial-delay-ms")).isNotBlank();
    }

    @Test
    void pipelineLeavingTheFeedReleasesItsBreakerState() {
        // The breaker's per-pipeline group-failure count is in-memory and keyed by pipeline name: left behind it grows
        // with every pipeline the runner has ever seen, and hands a re-enabled pipeline the count it accumulated before.
        stubPipelines(row("churn", 1));
        syncService.sync();
        dlqCircuitBreaker.recordGroupFailure("churn");

        stubPipelines("");
        syncService.sync();
        stubPipelines(row("churn", 2));
        syncService.sync();
        dlqCircuitBreaker.recordGroupFailure("churn");

        // Two failures at a threshold of two — but the first belonged to the pipeline's previous life.
        assertThat(pauseRegistry.isPaused("churn")).isFalse();
    }

    @Test
    void sqlPipelineIsRoutedToTheMaterializeTaskAndLlmPipelineToTheScan() {
        stubPipelines(row("r1", 1) + "," + sqlPipeline("r2", 1));

        syncService.sync();

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("r2").transform().type()).isEqualTo(TransformType.SQL);
        // A sql transform carries none of the model fields and no inputs: the derivation stays inside ADAS,
        // and what the feed carries is the pipeline's resolved outputs naming the columns it writes.
        assertThat(cache.get("r2").transform().model()).isNull();
        assertThat(cache.get("r2").transformInputs()).isNull();
        assertThat(cache.get("r2").responseSchema()).isNull();
        assertThat(cache.get("r2").outputs()).singleElement()
                .satisfies(output -> {
                    assertThat(output.name()).isEqualTo("model_name");
                    assertThat(output.column()).isEqualTo("model_name");
                    assertThat(output.jsonata()).isNull();
                });
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactly("r1");
        assertThat(instanceIds(Class1MaterializeTask.TASK_NAME)).containsExactly("r2");
    }

    @Test
    void pipelineChangingTransformTypeMigratesLanesAndKeepsItsCursor() {
        stubPipelines(row("r1", 1));
        syncService.sync();
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactly("r1");
        jdbcTemplate.update("INSERT INTO pipeline_cursor (pipeline_name, cursor) VALUES (?, ?) "
                + "ON CONFLICT (pipeline_name) DO UPDATE SET cursor = EXCLUDED.cursor", "r1", "c-keep");

        // Same pipeline name, now a sql transform, at a higher generation.
        stubPipelines(sqlPipeline("r1", 2));
        syncService.sync();

        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).isEmpty();
        assertThat(instanceIds(Class1MaterializeTask.TASK_NAME)).containsExactly("r1");
        // The pipeline's position in its source did not change, so the cursor must survive the migration.
        assertThat(storedCursor()).isEqualTo("c-keep");
    }

    @Test
    void unexecutablePipelineIsNeitherCachedNorScheduled() {
        // A group pipeline with no group_by / ready_when parses (so it cannot poison the whole feed) but the
        // runner cannot execute it.
        stubPipelines(row("r1", 1) + "," + pipeline("r-group", 1, "group", null));

        syncService.sync();

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("r-group")).isNull();
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactly("r1");
        assertThat(instanceIds(Class1MaterializeTask.TASK_NAME)).isEmpty();
    }

    @Test
    void executableGroupPipelineIsCachedAndScheduledOnTheScanTask() {
        // The dirty-mark rides the ordinary scan tick, so a group pipeline lands on pipeline-scan like a row pipeline.
        stubPipelines(groupPipeline("g1", 1, "\"idle\":\"10m\""));

        syncService.sync();

        PipelineDto cached = cache.get("g1");
        assertThat(cached).isNotNull();
        assertThat(cached.triggerKind()).isEqualTo(TriggerKind.GROUP);
        assertThat(cached.groupBy()).isEqualTo("chat_id");
        assertThat(cached.readyWhen()).extracting(ReadyWhenDto::idle).isEqualTo("10m");
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactly("g1");
    }

    /**
     * The retargeted admission arm, asserted with {@code outputs} deliberately <b>present</b> so it pins the
     * transform arm rather than the pre-existing outputs one. The declaration <em>is</em> the transform since
     * the fold, so an entry that drops it while keeping {@code outputs} passes every other arm — and
     * {@code taskName()} would route it to the scan lane as an ordinary row-grain pipeline for the worker to
     * dereference.
     */
    @Test
    void pipelineWithNoTransformButOutputsPresentIsRefusedCountedAndLoggedOncePerGeneration() {
        String withoutTransform = row("r-no-transform", 1)
                .replace(LLM_TRANSFORM + ",", "");
        assertThat(withoutTransform).contains("\"outputs\"").doesNotContain("\"transform\":");
        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            stubPipelines(withoutTransform);

            syncService.sync();
            syncService.sync();
            syncService.sync();

            assertThat(cache.get("r-no-transform")).isNull();
            assertThat(instanceIds(PipelineScanTask.TASK_NAME)).isEmpty();
            assertThat(unsupportedGauge()).isEqualTo(1);
            // Logged once per generation, not once per 30-second poll.
            assertThat(appender.messages().stream()
                    .filter(m -> m != null && m.contains("carries no transform")).count()).isEqualTo(1);
        } finally {
            appender.detach(Level.INFO);
        }
    }

    /**
     * The fold moved the variable map into the transform block, and this pins that the rule about a Class-1
     * declaration came with it: a derivation renders no request, so a {@code sql} transform naming a variable
     * has nothing to substitute it into.
     */
    @Test
    void sqlPipelineDeclaringTransformInputsIsRefused() {
        stubPipelines(row("r1", 1)
                + "," + sqlPipeline("r-sql-inputs", 1)
                        .replace("\"transform\":{\"type\":\"sql\"}",
                                "\"transform\":{\"type\":\"sql\",\"inputs\":{\"text\":{\"column\":\"request_body\"}}}"));

        syncService.sync();

        assertThat(cache.get("r-sql-inputs")).isNull();
        assertThat(instanceIds(Class1MaterializeTask.TASK_NAME)).isEmpty();
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactly("r1");
    }

    /**
     * The schema the gate reads is the entry's <b>top-level</b> one. ADAS also serves a member of the same
     * name <em>inside</em> {@code transform}, meaning only "the schema this declaration stores" — so a
     * pipeline that stores none but for which ADAS composed one must be admitted, and reading the authored
     * member would refuse exactly those.
     */
    @Test
    void theResolvedSchemaIsReadFromTheTopLevelNotFromInsideTheTransform() {
        stubPipelines(pipeline("r-composed", 1, "schedule", ROW_CRON, LLM_TRANSFORM_WITHOUT_STORED_SCHEMA));

        syncService.sync();

        PipelineDto cached = cache.get("r-composed");
        assertThat(cached).isNotNull();
        assertThat(cached.responseSchema()).containsEntry("type", "object");
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactly("r-composed");
    }

    /**
     * The single field whose absence produced a silent cursor burn rather than a refusal: both lanes read a
     * scanned row by the grain key, and every written row is keyed by it.
     */
    @Test
    void pipelineWithBlankGrainKeyIsRefusedAndCounted() {
        stubPipelines(row("r-no-grain", 1)
                .replace("\"grain_key\":\"event_id\"", "\"grain_key\":\"\""));

        syncService.sync();

        assertThat(cache.get("r-no-grain")).isNull();
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).isEmpty();
        assertThat(unsupportedGauge()).isEqualTo(1);
    }

    @Test
    void groupPipelineWithoutVersionColumnIsRefused() {
        // ADAS omits version_column when the source declares no scan metadata; such a group could never
        // be dirty-marked, so it is refused once rather than dead-lettering every scanned row.
        stubPipelines(groupPipeline("g-noversion", 1, "\"idle\":\"10m\"")
                .replace("\"version_column\":\"_ingested_at\",", ""));

        syncService.sync();

        assertThat(cache.get("g-noversion")).isNull();
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).isEmpty();
    }

    @Test
    void groupPipelineDeclaringSignalIsExecuted() {
        // ADAS evaluates the predicate inside the scan and projects the verdict per row, so the runner has
        // nothing to parse — it records what it was told and reads it as a readiness branch.
        stubPipelines(row("r1", 1)
                + "," + groupPipeline("g-signal", 1, "\"signal\":\"event_kind = 'chat_end'\",\"idle\":\"10m\""));

        syncService.sync();

        assertThat(cache.get("g-signal")).isNotNull();
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactlyInAnyOrder("r1", "g-signal");
    }

    @Test
    void pipelineWithoutAdvancedFallsBackToTheConfiguredDefaults() {
        // ADAS omits `advanced` entirely when the declaration set no knob, and writes no default into the
        // projection — a default echoed back would be indistinguishable from an operator having chosen it.
        // So an absent object must mean "apply the runner's own defaults", not "unrunnable".
        stubPipelines(row("r-bare", 1).replace(ADVANCED, ""));

        syncService.sync();

        PipelineDto cached = cache.get("r-bare");
        assertThat(cached).isNotNull();
        assertThat(cached.rowsPerScan()).isNull();
        assertThat(cached.rowsPerCall()).isNull();
        assertThat(cached.rateRpm()).isNull();
        assertThat(cached.sampleFraction()).isNull();
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactly("r-bare");
    }

    @Test
    void modelCallingPipelineWithoutOutputsIsRefused() {
        // Admitted, this pipeline would call the model and then upsert the grain key plus provenance and
        // nothing else — an all-NULL row replacing an already-paid-for label on the target's
        // ReplacingMergeTree, with nothing thrown, dead-lettered or retried.
        stubPipelines(row("r1", 1)
                + "," + row("r-nooutputs", 1)
                        .replace("\"outputs\":[{\"name\":\"label\",\"column\":\"label\"}]",
                                "\"outputs\":[]"));

        syncService.sync();

        assertThat(cache.get("r-nooutputs")).isNull();
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactly("r1");
        assertThat(instanceIds(Class1MaterializeTask.TASK_NAME)).isEmpty();
    }

    @Test
    void modelCallingPipelineWithoutResponseSchemaIsRefused() {
        // Admitted, every row of this pipeline would be sent to the model with no `response_format` — and
        // charged. A non-JSON reply then dead-letters at `validate`; a well-formed reply of the model's own
        // shape passes the parse-only check, maps to none of the declared outputs, and writes the same
        // all-NULL row over a paid-for label as the arm above. This asserts the feed-shaped case, not just
        // the predicate: the defect that motivated the gate was ADAS serving exactly this JSON.
        stubPipelines(row("r1", 1)
                + "," + pipeline("r-noschema", 1, "schedule", ROW_CRON, LLM_TRANSFORM_WITHOUT_SCHEMA));

        syncService.sync();

        assertThat(cache.get("r-noschema")).isNull();
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactly("r1");
    }

    @Test
    void pipelineRefusedAfterBeingScheduledHasItsInstanceCancelled() {
        stubPipelines(row("r1", 1));
        syncService.sync();
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactly("r1");

        // The pipeline is re-registered as a group pipeline, which the runner cannot execute.
        stubPipelines(pipeline("r1", 2, "group", null));
        syncService.sync();

        assertThat(cache.get("r1")).isNull();
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).isEmpty();
    }

    /**
     * The state the fold does <b>not</b> re-key, asserted rather than assumed. {@code pipeline_cursor} and
     * {@code group_state} are keyed by pipeline name alone, so re-reading every declaration in the new shape
     * must leave both untouched — no cursor loss, no re-derivation, and no repeat of
     * {@code V8__rekey_and_discard_runner_state.sql}. An earlier draft of this change asserted "state: none
     * discarded" for all five tables, which was true of these two and false of the other two; this pins the
     * half that is true.
     */
    @Test
    void cursorAndGroupStateSurviveSyncThatReReadsEveryDeclaration() {
        stubPipelines(row("r1", 1)
                + "," + groupPipeline("g1", 1, "\"idle\":\"10m\""));
        syncService.sync();
        jdbcTemplate.update("INSERT INTO pipeline_cursor (pipeline_name, cursor) VALUES (?, ?) "
                + "ON CONFLICT (pipeline_name) DO UPDATE SET cursor = EXCLUDED.cursor", "r1", "c-keep");
        jdbcTemplate.update("INSERT INTO group_state (pipeline_name, group_key, group_version, "
                + "computed_version, last_activity_at) VALUES (?, ?, ?, ?, now())",
                "g1", "chat-7", 1_700_000_000_000L, 1_700_000_000_000L);

        // A declaration edit on both: a higher generation, which is what re-keys the runner's work-item ids
        // and its counter rows — and must still not touch either of these two tables.
        stubPipelines(row("r1", 2)
                + "," + groupPipeline("g1", 2, "\"idle\":\"10m\""));
        syncService.sync();

        assertThat(storedCursor()).isEqualTo("c-keep");
        assertThat(jdbcTemplate.queryForObject("SELECT computed_version FROM group_state "
                + "WHERE pipeline_name = ? AND group_key = ?", Long.class, "g1", "chat-7"))
                .isEqualTo(1_700_000_000_000L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM group_state", Long.class)).isEqualTo(1);
    }

    /**
     * ADAS retired {@code on_ingest} and migrated every such pipeline to {@code schedule}, so one arriving is a
     * producer the contract check should already have refused. The kind stays modelled so the parse survives;
     * the gate refuses that one entry, by name, and the rest of the feed is admitted.
     */
    @Test
    void retiredOnIngestPipelineIsRefusedAndTheRestOfTheFeedIsAdmitted() {
        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            stubPipelines(row("r1", 1) + "," + pipeline("r-legacy", 1, "on_ingest", null) + "," + sqlPipeline("r2", 1));

            syncService.sync();

            assertThat(cache.get("r-legacy")).isNull();
            assertThat(cache.get("r1")).isNotNull();
            assertThat(cache.get("r2")).isNotNull();
            assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactly("r1");
            assertThat(instanceIds(Class1MaterializeTask.TASK_NAME)).containsExactly("r2");
            assertThat(unsupportedGauge()).isEqualTo(1);
            assertThat(appender.messages()).anyMatch(m -> m != null && m.contains("'on_ingest' is retired"));
        } finally {
            appender.detach(Level.INFO);
        }
    }

    /**
     * ADAS stores a cron on every row-grain pipeline, so a cronless {@code schedule} entry is a producer defect.
     * Refused at admission, it never reaches the cache — where it would look healthy while reconcile threw on it.
     */
    @Test
    void schedulePipelineWithoutCronIsRefusedAndTheRestOfTheFeedIsAdmitted() {
        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            stubPipelines(row("r1", 1) + "," + pipeline("r-nocron", 1, "schedule", null)
                    + "," + sqlPipeline("r-sql-nocron", 1).replace(",\"cron\":\"23 * * * * *\"", ""));

            syncService.sync();

            assertThat(cache.get("r-nocron")).isNull();
            assertThat(cache.get("r-sql-nocron")).isNull();
            assertThat(cache.get("r1")).isNotNull();
            assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactly("r1");
            assertThat(instanceIds(Class1MaterializeTask.TASK_NAME)).isEmpty();
            assertThat(unsupportedGauge()).isEqualTo(2);
            assertThat(appender.messages()).anyMatch(m -> m != null && m.contains("declares no trigger.cron"));
        } finally {
            appender.detach(Level.INFO);
        }
    }

    @Test
    void groupPipelineMarkScanRunsOnTheConfiguredGroupCadence() {
        stubPipelines(groupPipeline("g1", 1, "\"idle\":\"30m\""));

        syncService.sync();

        assertThat(storedScanSchedule("g1"))
                .isEqualTo(Schedules.fixedDelay(groupProperties.scanCadence()));
    }

    /**
     * An idle window shorter than two mark-scan cadences lets a still-active group look idle. Admission warns once per
     * generation and still admits the pipeline; an idle window that spans the cadence is not warned about.
     */
    @Test
    void groupPipelineWithIdleShorterThanTwoScanCadencesIsWarnedOncePerGenerationAndAdmitted() {
        EventCapture capture = EventCapture.attach();
        try {
            stubPipelines(groupPipeline("g-short", 1, "\"idle\":\"30s\"") + "," + groupPipeline("g-long", 1, "\"idle\":\"10m\""));
            syncService.sync();
            syncService.sync();
            assertThat(shortIdleWarnings(capture)).hasSize(1).allMatch(line -> line.startsWith("Group pipeline g-short (generation 1)"));

            stubPipelines(groupPipeline("g-short", 2, "\"idle\":\"30s\""));
            syncService.sync();
            assertThat(shortIdleWarnings(capture)).hasSize(2);
        } finally {
            capture.detach();
        }
        assertThat(cache.get("g-short")).isNotNull();
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactly("g-short");
    }

    private static List<String> shortIdleWarnings(EventCapture capture) {
        return capture.events().stream()
                .filter(event -> event.getLevel() == org.apache.logging.log4j.Level.WARN)
                .map(event -> event.getMessage().getFormattedMessage())
                .filter(line -> line.contains("group.scan-cadence"))
                .toList();
    }

    /**
     * V44 rewrote every {@code on_ingest} trigger to a cron without bumping {@code generation}. A persisted
     * fixed-delay instance must still end up on its cron after the restart, and the restart is modelled here by
     * an empty cache — the runner's only memory of what it scheduled — over the instance the old build left.
     */
    @Test
    void restartReschedulesPersistedFixedDelayInstanceOntoItsCron() {
        persistFixedDelayInstance();
        cache.replaceAll(Map.of());
        stubPipelines(row("r1", 1));

        syncService.sync();

        assertThat(storedScanSchedule("r1")).isEqualTo(rowCron());
        assertThat(cache.get("r1")).isNotNull();
    }

    /**
     * The instance is still picked by an execution the scale-down killed, so the first poll cannot reschedule it.
     * The pipeline's generation is unchanged and it is cached, so only the stored-schedule comparison makes a later
     * poll, after the instance is released, move it onto its cron.
     */
    @Test
    void pickedInstanceKeepsItsFixedDelayUntilReleasedAndIsThenRescheduled() {
        persistFixedDelayInstance();
        jdbcTemplate.update("UPDATE scheduled_tasks SET picked = true, picked_by = 'scaled-down-pod', last_heartbeat = now() "
                + "WHERE task_name = ? AND task_instance = ?", PipelineScanTask.TASK_NAME, "r1");
        cache.replaceAll(Map.of());
        stubPipelines(row("r1", 1));

        syncService.sync();

        assertThat(storedScanSchedule("r1")).isInstanceOf(FixedDelay.class);
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactly("r1");

        // Declared dead and revived: db-scheduler releases the row with its persisted schedule.
        jdbcTemplate.update("UPDATE scheduled_tasks SET picked = false, picked_by = NULL "
                + "WHERE task_name = ? AND task_instance = ?", PipelineScanTask.TASK_NAME, "r1");
        syncService.sync();

        assertThat(storedScanSchedule("r1")).isEqualTo(rowCron());
        assertThat(cache.get("r1").generation()).isEqualTo(1);
    }

    /**
     * The comparison's risk is a stored schedule that never equals the derived one — a serialization that loses
     * the zone or the style — which would reschedule every pipeline on every poll. Both schedule kinds must
     * round-trip through the scheduler's store equal to what the factory derives.
     */
    @Test
    void pollWhoseStoredSchedulesAlreadyMatchReschedulesNothing() {
        stubPipelines(row("r1", 1) + "," + sqlPipeline("r2", 1) + "," + groupPipeline("g1", 1, "\"idle\":\"10m\""));
        syncService.sync();
        List<Map<String, Object>> before = instanceRows();
        EventCapture capture = EventCapture.attach();
        try {
            syncService.sync();

            assertThat(infoLines(capture)).noneMatch(line -> line.contains("rescheduled") || line.contains("added"));
        } finally {
            capture.detach();
        }
        // A reschedule rewrites execution_time and bumps version, so unchanged rows prove nothing was rewritten.
        assertThat(instanceRows()).isEqualTo(before);
    }

    /**
     * A restart alone does not reschedule an instance whose stored schedule already matches: that would push an
     * overdue cron fire to its next slot and wipe the instance's failure counters.
     */
    @Test
    void restartLeavesMatchingInstanceUntouched() {
        stubPipelines(row("r1", 1));
        syncService.sync();
        List<Map<String, Object>> before = instanceRows();
        cache.replaceAll(Map.of());

        syncService.sync();

        assertThat(instanceRows()).isEqualTo(before);
        assertThat(cache.get("r1")).isNotNull();
    }

    /**
     * An instance picked at a generation bump is hidden from the listing and cannot be re-created. The pipeline is
     * still cached at its new declaration, so the workers neither drop its queued work as "absent from cache" nor
     * keep running the old generation; its stored schedule is compared again once it is released.
     */
    @Test
    void pickedInstanceAtGenerationBumpStillCachesTheNewDeclaration() {
        stubPipelines(row("r1", 1));
        syncService.sync();
        jdbcTemplate.update("UPDATE scheduled_tasks SET picked = true, picked_by = 'other-pod', last_heartbeat = now() "
                + "WHERE task_name = ? AND task_instance = ?", PipelineScanTask.TASK_NAME, "r1");
        stubPipelines(row("r1", 2));
        EventCapture capture = EventCapture.attach();
        try {
            syncService.sync();

            assertThat(infoLines(capture)).noneMatch(line -> line.startsWith("Pipeline r1 "));
        } finally {
            capture.detach();
        }
        assertThat(cache.get("r1").generation()).isEqualTo(2);
        assertThat(instanceIds(PipelineScanTask.TASK_NAME)).containsExactly("r1");
    }

    private List<Map<String, Object>> instanceRows() {
        return jdbcTemplate.queryForList("SELECT task_name, task_instance, execution_time, version FROM scheduled_tasks "
                + "ORDER BY task_name, task_instance");
    }

    /** A scan instance for {@code r1} persisted on a fixed-delay schedule, as an older runner stored it. */
    private void persistFixedDelayInstance() {
        scheduler.scheduleIfNotExists(pipelineScanTask.task().schedulableInstance("r1",
                new PlainScheduleAndData(Schedules.fixedDelay(Duration.ofSeconds(60)), "r1")));
    }

    private Schedule storedScanSchedule(String instanceId) {
        List<Schedule> schedules = new ArrayList<>();
        scheduler.fetchScheduledExecutionsForTask(PipelineScanTask.TASK_NAME, PlainScheduleAndData.class,
                ScheduledExecutionsFilter.all(), exec -> {
                    if (exec.getTaskInstance().getId().equals(instanceId)) {
                        schedules.add(exec.getData().getSchedule());
                    }
                });
        assertThat(schedules).hasSize(1);
        return schedules.getFirst();
    }

    private static Schedule rowCron() {
        return new CronSchedule(ROW_CRON, ZoneOffset.UTC, CronStyle.SPRING53);
    }

    private long scanTaskCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", Long.class, PipelineScanTask.TASK_NAME);
        return count == null ? 0 : count;
    }

    private List<String> instanceIds(String taskName) {
        return jdbcTemplate.queryForList(
                "SELECT task_instance FROM scheduled_tasks WHERE task_name = ? ORDER BY task_instance",
                String.class, taskName);
    }

    private String storedCursor() {
        return jdbcTemplate.queryForObject(
                "SELECT cursor FROM pipeline_cursor WHERE pipeline_name = 'r1'", String.class);
    }

    /**
     * The authored {@code llm} transform and the derived top-level {@code response_schema} beside it, exactly
     * as ADAS serves them on the compiled projection: the transform carries what the call is made of — type,
     * preset, model, template and its {@code inputs} variable map — and the schema the model is held to sits
     * at the entry's top level. Shared by the row and group fixtures, so the wire shape is stated once.
     *
     * <p>It carries {@code transform.response_schema} too, holding a <em>different</em> schema from the
     * top-level one. That is not decoration: the two members mean different things ("as authored" against
     * "as resolved"), and a fixture in which they were identical would pass whichever one the code read.
     */
    private static final String LLM_TRANSFORM = """
            "transform":{"type":"llm","preset":"chat_completion","model":"gpt-4o",
              "request_template":"{\\"messages\\":[{\\"role\\":\\"user\\",\\"content\\":\\"{{prompt}}\\"}]}",
              "inputs":{"prompt":{"column":"request_body"}},
              "outputs":{"label":{"prose":"the label"}},
              "response_schema":{"type":"object","properties":{"stored_only":{"type":"string"}}}},
            "response_schema":{"type":"object",
              "additionalProperties":false,"properties":{"label":{"type":"string"}}}""";

    /**
     * A transform storing no schema of its own, with ADAS's composed one at the top level — which is the
     * common shape, not an edge case. Reading the authored member instead of the top-level one would refuse
     * every pipeline of this shape.
     */
    private static final String LLM_TRANSFORM_WITHOUT_STORED_SCHEMA = """
            "transform":{"type":"llm","preset":"chat_completion","model":"gpt-4o",
              "request_template":"{\\"messages\\":[]}",
              "inputs":{"prompt":{"column":"request_body"}}},
            "response_schema":{"type":"object",
              "additionalProperties":false,"properties":{"label":{"type":"string"}}}""";

    /**
     * The same transform with <b>no</b> resolved schema anywhere — the shape the admission gate refuses,
     * because the runner sets {@code response_format} only when a schema is present.
     */
    private static final String LLM_TRANSFORM_WITHOUT_SCHEMA = """
            "transform":{"type":"llm","preset":"chat_completion","model":"gpt-4o",
              "request_template":"{\\"messages\\":[]}",
              "inputs":{"prompt":{"column":"request_body"}}}""";

    /** A poll that changed nothing says so at DEBUG; the pipelines it added, rescheduled or removed are named at INFO. */
    @Test
    void syncLogsAddedRescheduledAndRemovedPipelinesOnlyWhenSomethingChanged() {
        EventCapture capture = EventCapture.attach();
        try {
            stubPipelines(row("log-a", 1) + "," + row("log-b", 1));
            syncService.sync();
            assertThat(infoLines(capture)).contains("Pipeline log-a added: lane=row generation=1",
                    "Pipeline log-b added: lane=row generation=1");

            capture.clear();
            syncService.sync();
            assertThat(infoLines(capture)).noneMatch(line -> line.startsWith("Pipeline "));

            stubPipelines(row("log-a", 2));
            syncService.sync();
            assertThat(infoLines(capture)).contains("Pipeline log-a rescheduled: lane=row generation=1->2",
                    "Pipeline log-b removed: lane=row generation=1");
        } finally {
            capture.detach();
        }
    }

    /** A persistent outage logs its first failure with a stack, then every Nth, then its end with the count. */
    @Test
    void repeatedSyncFailuresAreDeduplicatedAndTheRecoveryIsCounted() {
        ADAS.stubFor(get(urlPathEqualTo("/v1/pipelines")).willReturn(aResponse().withStatus(503).withBody("upstream-body-marker")));
        EventCapture capture = EventCapture.attach();
        try {
            int failures = PipelineSyncService.FAILURE_LOG_EVERY + 2;
            for (int i = 0; i < failures; i++) {
                syncService.sync();
            }
            List<LogEvent> warnings = capture.events().stream()
                    .filter(event -> event.getLevel() == org.apache.logging.log4j.Level.WARN)
                    .filter(event -> event.getMessage().getFormattedMessage().startsWith("Pipeline sync"))
                    .toList();
            assertThat(warnings).hasSize(2);
            assertThat(warnings.getFirst().getMessage().getFormattedMessage()).startsWith("Pipeline sync failed;");
            assertThat(warnings.get(1).getMessage().getFormattedMessage())
                    .startsWith("Pipeline sync still failing after " + PipelineSyncService.FAILURE_LOG_EVERY + " consecutive failures");
            assertThat(capture.events()).extracting(EventCapture::rendered).noneMatch(text -> text.contains("upstream-body-marker"));

            ADAS.resetAll();
            stubPipelines(row("r1", 1));
            syncService.sync();
            assertThat(infoLines(capture)).anyMatch(line -> line.startsWith("Pipeline sync recovered after " + failures + " failure(s) in "));
        } finally {
            capture.detach();
        }
    }

    private static List<String> infoLines(EventCapture capture) {
        return capture.events().stream()
                .filter(event -> event.getLevel() == org.apache.logging.log4j.Level.INFO)
                .map(event -> event.getMessage().getFormattedMessage())
                .toList();
    }

    private double unsupportedGauge() {
        return meterRegistry.get("runner.pipelines.unsupported").gauge().value();
    }

    /**
     * Stubs the registry feed, and requires all three parameters on it. Unfiltered, ADAS's
     * {@code GET /v1/pipelines} is the operator listing of the whole registry — every kind, disabled ones
     * included — and its default projection is the authored declaration, which carries no resolved schema, no
     * grain key and no outputs. Matching on all three means dropping any one of them fails every sync test
     * here with an unmatched request, rather than silently pulling pipelines the runner must not schedule,
     * cannot model, or would refuse as unrunnable. There is deliberately no {@code contract} parameter to
     * match: the version travels as the {@code Adas-Contract-Version} header on every call. The body is the
     * {@code {"pipelines":[…]}} envelope, not a bare array.
     */
    private void stubPipelines(String pipelineObjects) {
        ADAS.stubFor(get(urlPathEqualTo("/v1/pipelines"))
                .withQueryParam("kind", equalTo("enrich"))
                .withQueryParam("enabled", equalTo("true"))
                .withQueryParam("view", equalTo("compiled"))
                .willReturn(okJson("{\"pipelines\":[" + pipelineObjects + "]}")));
    }

    /** A group pipeline whose readiness declaration is supplied verbatim, so a test can vary just that. */
    private static String groupPipeline(String name, long generation, String readyWhenFields) {
        return """
                {
                  "name":"%s","kind":"enrich",
                  %s,
                  "target":"enr_%s","inputs":["dial_usage_log"],"grain_key":"chat_id",
                  "version_column":"_ingested_at",
                  "trigger":{"kind":"group","group_by":"chat_id","ready_when":{%s}},
                  "outputs":[{"name":"topic","column":"topic"}],
                  "advanced":{"rows_per_scan":1000},
                  "enabled":true,"generation":%d,
                  "created_at":"2026-07-01T00:00:00Z","updated_at":"2026-07-01T00:00:00Z"
                }
                """.formatted(name, LLM_TRANSFORM, name, readyWhenFields, generation);
    }

    /**
     * A Class-1 pipeline. Its transform carries no model, no template and no {@code inputs} — the SQL
     * derivation lives in ADAS and never crosses the wire — so what the feed carries is the pipeline's
     * resolved {@code outputs}, and there is no top-level {@code response_schema} because a derivation
     * constrains no model response.
     */
    private static String sqlPipeline(String name, long generation) {
        return """
                {
                  "name":"%s","kind":"enrich",
                  "transform":{"type":"sql"},
                  "target":"enr_%s","inputs":["dial_usage_log"],"grain_key":"event_id",
                  "trigger":{"kind":"schedule","cron":"23 * * * * *"},
                  "outputs":[{"name":"model_name","column":"model_name"}],
                  "enabled":true,"generation":%d,
                  "created_at":"2026-07-01T00:00:00Z","updated_at":"2026-07-01T00:00:00Z"
                }
                """.formatted(name, name, generation);
    }

    /** A row-grain llm pipeline on {@link #ROW_CRON}: the shape every row-grain entry has under contract 3. */
    private static String row(String name, long generation) {
        return pipeline(name, generation, "schedule", ROW_CRON);
    }

    /** An llm pipeline under any trigger kind; a null {@code cron} omits {@code trigger.cron}. */
    private static String pipeline(String name, long generation, String triggerKind, String cron) {
        return pipeline(name, generation, triggerKind, cron, LLM_TRANSFORM);
    }

    /**
     * The same, with the transform block and the top-level schema beside it supplied verbatim, so a test can
     * vary just those.
     */
    private static String pipeline(String name, long generation, String triggerKind, String cron, String transform) {
        String triggerCron = cron == null ? "" : ",\"cron\":\"" + cron + "\"";
        return """
                {
                  "name":"%s","kind":"enrich",
                  %s,
                  "target":"enr_%s","inputs":["dial_usage_log"],"grain_key":"event_id",
                  "trigger":{"kind":"%s"%s},%s
                  "outputs":[{"name":"label","column":"label"}],
                  "enabled":true,"generation":%d,
                  "created_at":"2026-07-01T00:00:00Z","updated_at":"2026-07-01T00:00:00Z"
                }
                """.formatted(name, transform, name, triggerKind, triggerCron, ADVANCED, generation);
    }
}
