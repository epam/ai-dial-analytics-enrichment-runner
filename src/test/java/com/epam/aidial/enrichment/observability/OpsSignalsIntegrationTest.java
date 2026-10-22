package com.epam.aidial.enrichment.observability;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ReadyWhenDto;
import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The group and Class-1 lanes are observable, and a group is operable individually.
 *
 * <p>The cardinality assertion is the load-bearing one — a group key is unbounded, so a per-group metric series
 * would exhaust a Prometheus instance. That constraint is easy to violate by accident and impossible to notice
 * without a test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpsSignalsIntegrationTest {

    private static final String PIPELINE_NAME = "r-ops";
    private static final String GROUP_KEY = "chat-a";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private RunnerMetrics runnerMetrics;

    @Autowired
    private PipelineCache pipelineCache;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
        registry.add("group.sweep-initial-delay", () -> "3600s");
        registry.add("group.sweep-interval", () -> "3600s");
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, group_state");
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, groupPipeline()));
        jdbcTemplate.update("""
                INSERT INTO group_state (pipeline_name, group_key, group_version, last_activity_at, evaluations)
                VALUES (?, ?, ?, ?, ?)
                """, PIPELINE_NAME, GROUP_KEY, 100L, Timestamp.from(Instant.parse("2026-07-01T10:00:00Z")), 2);
    }

    @Test
    void groupAndQueueMetersAreExposed() throws Exception {
        runnerMetrics.refreshQueriedGauges();

        String scrape = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(scrape)
                .contains("runner_queue_depth")
                .contains("runner_group_active")
                .contains("runner_group_dirty")
                .contains("runner_group_ceiling_blocked")
                .contains("runner_dial_rate_limited")
                .contains("runner_pipelines_unsupported")
                // Pre-registered, so the series exists before the first stall rather than appearing with it.
                .contains("runner_cursor_stalls");
    }

    @Test
    void cursorStallsAreCountedPerLaneAndExistBeforeTheFirstStall() {
        List<String> lanes = meterRegistry.find("runner.cursor.stalls").counters().stream()
                .map(c -> c.getId().getTag("lane"))
                .toList();
        // Pre-registered per lane: a stall is rare, and a series that only appears once it has happened cannot
        // be alerted on beforehand.
        assertThat(lanes).containsExactlyInAnyOrder("scan", "class1", "backfill");
        double class1Before = counter("runner.cursor.stalls", "lane", "class1");
        double scanBefore = counter("runner.cursor.stalls", "lane", "scan");

        runnerMetrics.incrementCursorStall(RunnerMetrics.CursorLane.CLASS1);

        // A halted drain throws nothing, so this is the only counter that moves — the tick-failure counter
        // cannot cover a lane that stops progressing without failing.
        assertThat(counter("runner.cursor.stalls", "lane", "class1")).isEqualTo(class1Before + 1);
        assertThat(counter("runner.cursor.stalls", "lane", "scan")).isEqualTo(scanBefore);
    }

    @Test
    void class1RowsWrittenIsAttributableToItsPipelineAndEvictedWithIt() {
        double before = counter("runner.class1.rows.written", "pipeline", PIPELINE_NAME);

        runnerMetrics.addClass1RowsWritten(PIPELINE_NAME, 6L);
        runnerMetrics.addClass1RowsWritten("r-other", 2L);

        // A summed lane total cannot answer which pipeline stopped writing, which is the only question it is asked.
        assertThat(counter("runner.class1.rows.written", "pipeline", PIPELINE_NAME)).isEqualTo(before + 6);
        assertThat(counter("runner.class1.rows.written", "pipeline", "r-other")).isEqualTo(2);

        // ...and the series is evicted with the pipeline, so the tag cannot grow without bound.
        runnerMetrics.evictPipeline("r-other");
        assertThat(meterRegistry.find("runner.class1.rows.written").tag("pipeline", "r-other").counter()).isNull();
    }

    /**
     * Both new per-pipeline series are tagged by pipeline <em>and</em> reason, so a single pipeline can hold
     * several of each — and eviction has to drop every one of them, or the cardinality it bounds would grow
     * by reason as well as by name.
     */
    @Test
    void skipAndRefusalSeriesAreAttributableAndEvictedWithTheirPipeline() {
        runnerMetrics.recordSkippedRows("r-evicted", RunnerMetrics.SkipReason.SAMPLING, 7);
        runnerMetrics.recordSkippedRows("r-evicted", RunnerMetrics.SkipReason.NULL_GRAIN_KEY, 2);
        runnerMetrics.recordRefusedPage("r-evicted", RunnerMetrics.RefusalReason.MISSING_COLUMN);
        runnerMetrics.recordSkippedRows(PIPELINE_NAME, RunnerMetrics.SkipReason.SAMPLING, 1);

        // Told apart by reason: "this pipeline enriches a sampled fraction" and "this pipeline enriched
        // nothing" are otherwise the same absence of activity.
        assertThat(counter("runner.scan.rows.skipped", "reason", "sampling")).isGreaterThan(0);
        assertThat(meterRegistry.get("runner.scan.rows.skipped")
                .tag("pipeline", "r-evicted").tag("reason", "null_grain_key").counter().count()).isEqualTo(2);
        assertThat(meterRegistry.get("runner.scan.pages.refused")
                .tag("pipeline", "r-evicted").tag("reason", "missing_column").counter().count()).isEqualTo(1);

        runnerMetrics.evictPipeline("r-evicted");

        assertThat(meterRegistry.find("runner.scan.rows.skipped").tag("pipeline", "r-evicted").counters())
                .isEmpty();
        assertThat(meterRegistry.find("runner.scan.pages.refused").tag("pipeline", "r-evicted").counters())
                .isEmpty();
        // Another pipeline's series is untouched by the eviction.
        assertThat(meterRegistry.find("runner.scan.rows.skipped")
                .tag("pipeline", PIPELINE_NAME).tag("reason", "sampling").counter()).isNotNull();
    }

    /**
     * A zero skip is not recorded at all: a lane that skips nothing should leave the series absent rather
     * than flat at zero, which reads as "measured, and it was none" for a pipeline that was never measured.
     */
    @Test
    void zeroSkipRecordsNoSeries() {
        runnerMetrics.recordSkippedRows("r-nozero", RunnerMetrics.SkipReason.SAMPLING, 0);

        assertThat(meterRegistry.find("runner.scan.rows.skipped").tag("pipeline", "r-nozero").counters())
                .isEmpty();
    }

    @Test
    void caughtUpPipelineReportsZeroLagAndUnknownLagLeavesItAlone() {
        runnerMetrics.recordLag(PIPELINE_NAME, 604_800L);
        assertThat(lagGauge()).isEqualTo(604_800);

        // Zero is a real value, not "no value": an empty page establishes it, and without it a pipeline that has
        // drained a week of backlog keeps exporting a week of lag until the process restarts.
        runnerMetrics.recordLag(PIPELINE_NAME, 0L);
        assertThat(lagGauge()).isZero();

        // A genuinely unknown lag holds the last value rather than inventing one.
        runnerMetrics.recordLag(PIPELINE_NAME, 30L);
        runnerMetrics.recordLag(PIPELINE_NAME, null);
        assertThat(lagGauge()).isEqualTo(30);
    }

    @Test
    void queueDepthIsReportedPerTask() {
        runnerMetrics.refreshQueriedGauges();

        List<String> tasks = meterRegistry.find("runner.queue.depth").gauges().stream()
                .map(g -> g.getId().getTag("task"))
                .toList();

        // A single summed depth cannot say which lane is backed up, which is the only question depth is asked for.
        assertThat(tasks).contains("enrich-chunk", "enrich-group", "upsert-rows");
    }

    @Test
    void noMeterIsTaggedByGroupKey() {
        runnerMetrics.refreshQueriedGauges();

        // A group key is unbounded: one series per conversation would exhaust the registry. Group detail belongs
        // on spans, in the MDC and in the pull-based introspection endpoint.
        List<String> tagValues = meterRegistry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream().map(t -> t.getKey() + "=" + t.getValue()))
                .toList();
        assertThat(tagValues).noneMatch(t -> t.contains("group_key") || t.contains(GROUP_KEY));
    }

    @Test
    void rateLimitedCallIsCountedAsRateLimitedAndError() {
        double errorsBefore = counter("runner.dial.calls", "outcome", "error");
        double limitedBefore = counter("runner.dial.rate.limited", null, null);

        runnerMetrics.recordDialRateLimited();

        // Two dimensions, not two populations: a 429 is still a failed call.
        assertThat(counter("runner.dial.rate.limited", null, null)).isEqualTo(limitedBefore + 1);
        assertThat(counter("runner.dial.calls", "outcome", "error")).isEqualTo(errorsBefore);
    }

    @Test
    void groupStateIsInspectableWithoutMemberContent() throws Exception {
        mockMvc.perform(get("/v1/pipelines/{name}/groups", PIPELINE_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].group_key").value(GROUP_KEY))
                .andExpect(jsonPath("$.groups[0].group_version").value(100))
                .andExpect(jsonPath("$.groups[0].evaluations").value(2))
                .andExpect(jsonPath("$.groups[0].dirty").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.has_more").value(false))
                // Never member rows and never the label.
                .andExpect(jsonPath("$.groups[0].rows").doesNotExist())
                .andExpect(jsonPath("$.groups[0].members").doesNotExist());
    }

    @Test
    void perGroupRequeueEnqueuesOnceForItsVersion() throws Exception {
        mockMvc.perform(post("/v1/pipelines/{name}/groups/{key}/requeue", PIPELINE_NAME, GROUP_KEY))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/v1/pipelines/{name}/groups/{key}/requeue", PIPELINE_NAME, GROUP_KEY))
                .andExpect(status().isAccepted());

        // The same deterministic identity the sweep uses, so the override cannot duplicate a queued version.
        assertThat(groupChunkTaskCount()).isEqualTo(1);
    }

    @Test
    void introspectingNonGroupPipelineIsNotFound() throws Exception {
        pipelineCache.replaceAll(Map.of("r-row", TestPipelines.schedule("r-row", 1, TestPipelines.EVERY_30_SECONDS)));

        mockMvc.perform(get("/v1/pipelines/{name}/groups", "r-row")).andExpect(status().isNotFound());
    }

    @Test
    void groupGaugesReadCachedValueNotTheTable() {
        runnerMetrics.refreshQueriedGauges();
        double activeBefore = activeGroupsGauge();

        // Change the table without refreshing: a gauge that queried during the scrape would move.
        jdbcTemplate.update("""
                INSERT INTO group_state (pipeline_name, group_key, group_version, last_activity_at)
                VALUES (?, ?, ?, ?)
                """, PIPELINE_NAME, "chat-b", 200L, Timestamp.from(Instant.parse("2026-07-01T11:00:00Z")));

        assertThat(activeGroupsGauge())
                .as("scrape latency must not become a function of table size")
                .isEqualTo(activeBefore);

        runnerMetrics.refreshQueriedGauges();
        assertThat(activeGroupsGauge()).isEqualTo(activeBefore + 1);
    }

    private double lagGauge() {
        var gauge = meterRegistry.find("runner.scan.lag.seconds").tag("pipeline", PIPELINE_NAME).gauge();
        return gauge == null ? -1 : gauge.value();
    }

    private double activeGroupsGauge() {
        var gauge = meterRegistry.find("runner.group.active").gauge();
        return gauge == null ? -1 : gauge.value();
    }

    private static PipelineDto groupPipeline() {
        return TestPipelines.builder(PIPELINE_NAME)
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .grainKey("chat_id")
                .readyWhen(new ReadyWhenDto(null, "10m", null, null))
                .build();
    }

    private double counter(String name, String tagKey, String tagValue) {
        var search = meterRegistry.find(name);
        if (tagKey != null) {
            search = search.tag(tagKey, tagValue);
        }
        var counter = search.counter();
        return counter == null ? 0 : counter.count();
    }

    private long groupChunkTaskCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", Long.class, GroupChunkTask.TASK_NAME);
        return count == null ? 0 : count;
    }
}
