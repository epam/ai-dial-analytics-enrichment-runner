package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.VarDto;
import com.epam.aidial.enrichment.exception.ScanPageColumnException;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.TestPages;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.ChunkPayload;
import com.epam.aidial.enrichment.worker.ChunkRow;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3 exit-criteria test: a scan fire enqueues deterministic chunk work items and advances the pipeline
 * cursor atomically, is idempotent under a repeated scan of the same range, dead-letters
 * input-mapping failures, and rolls back both the enqueue and the cursor advance together on failure.
 * ADAS scan is stubbed with WireMock; the runner's PostgreSQL runs in Testcontainers.
 */
@SpringBootTest
class ScanIntegrationTest {

    private static final WireMockServer ADAS = AdasWireMock.server();
    private static final String PIPELINE_NAME = "r-scan";

    @Autowired
    private PipelineScanExecutor executor;

    @Autowired
    private ScanPageCommitter committer;

    @Autowired
    private PipelineCache pipelineCache;

    @Autowired
    private PipelineCursorRepository cursorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeAll
    static void startAdas() {
        ADAS.start();
    }

    @AfterAll
    static void stopAdas() {
        ADAS.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("adas.base-url", ADAS::baseUrl);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
    }

    @BeforeEach
    void reset() {
        ADAS.resetAll();
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, pipeline_cursor, dlq_item");
        PipelineDto pipeline = TestPipelines.scanPipeline(PIPELINE_NAME, 2,
                TestPipelines.columnVar("prompt", "request_body"));
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, pipeline));
    }

    @Test
    void enqueuesDeterministicChunksAndAdvancesCursorAtomically() {
        stubScan(threeRowPage());

        executor.execute(PIPELINE_NAME);

        // 3 kept rows, chunk size 2 -> 2 chunks; cursor advanced to c1.
        assertThat(chunkCount()).isEqualTo(2);
        assertThat(cursorRepository.findById(PIPELINE_NAME)).get()
                .extracting(PipelineCursorEntity::getCursor).isEqualTo("c1");
    }

    @Test
    void repeatedScanOfSameRangeDoesNotDuplicateChunks() {
        stubScan(threeRowPage());

        executor.execute(PIPELINE_NAME);
        assertThat(chunkCount()).isEqualTo(2);

        // Simulate re-processing the same range (e.g. a crash before commit): rewind the cursor so
        // the next fire scans from the same cursor-before and produces identical deterministic ids.
        cursorRepository.deleteById(PIPELINE_NAME);
        executor.execute(PIPELINE_NAME);

        assertThat(chunkCount()).isEqualTo(2);
    }

    @Test
    void inputMappingFailuresAreDeadLetteredAndCursorStillAdvances() {
        PipelineDto failing = TestPipelines.scanPipeline(PIPELINE_NAME, 2,
                Map.of("x", new VarDto(null, "$notAFunction()")));
        pipelineCache.replaceAll(Map.of(PIPELINE_NAME, failing));
        stubScan(threeRowPage());

        executor.execute(PIPELINE_NAME);

        assertThat(dlqCount()).isEqualTo(3);
        assertThat(chunkCount()).isEqualTo(0);
        assertThat(cursorRepository.findById(PIPELINE_NAME)).get()
                .extracting(PipelineCursorEntity::getCursor).isEqualTo("c1");
    }

    @Test
    void commitRollsBackEnqueueAndCursorTogetherOnFailure() {
        ChunkPayload chunk = new ChunkPayload(PIPELINE_NAME, 3,
                List.of(new ChunkRow("e0", Map.of("prompt", "hi"))));
        DlqItemEntity invalid = DlqItemEntity.builder()
                .pipelineName(PIPELINE_NAME)
                .stage("not_a_valid_stage") // violates the dlq_item.stage CHECK constraint
                .build();

        assertThatThrownBy(() -> committer.commit(PIPELINE_NAME, 3, TestPipelines.CREATED_AT, null, "c1", 1L,
                new ChunkBatch(List.of(chunk), List.of(invalid), TestPages.workAndDeadLetters(1, 1))))
                .isInstanceOf(RuntimeException.class);

        assertThat(chunkCount()).isZero();
        assertThat(cursorRepository.findById(PIPELINE_NAME)).isEmpty();
    }

    private long chunkCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", Long.class, EnrichChunkTask.TASK_NAME);
        return count == null ? 0 : count;
    }

    private long dlqCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM dlq_item", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * The exact shape the deploy skew produced, and the one it cost most: ADAS answers 200 with real rows
     * projected under names the pipeline's grain key is not among. Before the {@code containsKey} split this
     * read as a null grain value on every row, so the page produced no chunk and no dead letter — and the
     * cursor advanced over it anyway, up to the per-fire page budget, with scan lag falling to zero as it
     * caught up. Every row passed was permanently unenriched, because "already enriched" is not a predicate
     * anywhere.
     */
    @Test
    void pageWithoutTheGrainColumnHoldsTheCursorAndWritesNoDeadLetter() {
        stubScan(pageWithoutGrainColumn());

        assertThatThrownBy(() -> executor.execute(PIPELINE_NAME))
                .isInstanceOf(ScanPageColumnException.class)
                .hasMessageContaining(PIPELINE_NAME)
                .hasMessageContaining("event_id");

        // Untouched, so the next cadence re-reads the same range once the projection is right.
        assertThat(cursorRepository.findById(PIPELINE_NAME)).isEmpty();
        assertThat(chunkCount()).isZero();
        // The page's rows are not row defects, and dead-lettering them would flood the DLQ with tens of
        // thousands of items whose only recovery is a bulk requeue.
        assertThat(dlqCount()).isZero();
        assertThat(missingColumnRefusals()).isEqualTo(1);
    }

    /**
     * The other side of the split: the column is there and this row's value is null, so there is no key to
     * write a row at. That is data, it stays a skip, and the cursor advances past it — but it is now
     * counted, which is the difference between "this pipeline enriches a sampled fraction" and "this
     * pipeline enriched nothing".
     */
    @Test
    void nullGrainValueInPresentColumnIsSkippedCountedAndTheCursorAdvances() {
        stubScan(pageWithNullGrain());

        executor.execute(PIPELINE_NAME);

        assertThat(cursorRepository.findById(PIPELINE_NAME)).get()
                .extracting(PipelineCursorEntity::getCursor).isEqualTo("c1");
        // One row had a usable key, so one chunk; the other was skipped and accounted for.
        assertThat(chunkCount()).isEqualTo(1);
        assertThat(dlqCount()).isZero();
        assertThat(nullGrainKeySkips()).isEqualTo(1);
    }

    private double missingColumnRefusals() {
        return meterRegistry.get("runner.scan.pages.refused")
                .tag("pipeline", PIPELINE_NAME).tag("reason", RunnerMetrics.RefusalReason.MISSING_COLUMN.tag()).counter().count();
    }

    private double nullGrainKeySkips() {
        return meterRegistry.get("runner.scan.rows.skipped")
                .tag("pipeline", PIPELINE_NAME).tag("reason", RunnerMetrics.SkipReason.NULL_GRAIN_KEY.tag()).counter().count();
    }

    private void stubScan(String responseJson) {
        ADAS.stubFor(post(urlPathEqualTo("/v1/pipelines/" + PIPELINE_NAME + "/scan")).willReturn(okJson(responseJson)));
    }

    /** Real rows, none of them carrying {@code event_id}: the authored-projection shape. */
    private static String pageWithoutGrainColumn() {
        return """
                {
                  "rows":[
                    {"request_body":"body-0"},
                    {"request_body":"body-1"}
                  ],
                  "next_cursor":"c1","has_more":false,"lag_seconds":2
                }
                """;
    }

    /** The grain column present on every row, holding null on one of them. */
    private static String pageWithNullGrain() {
        return """
                {
                  "rows":[
                    {"event_id":null,"request_body":"body-0"},
                    {"event_id":"e1","request_body":"body-1"}
                  ],
                  "next_cursor":"c1","has_more":false,"lag_seconds":2
                }
                """;
    }

    private static String threeRowPage() {
        return """
                {
                  "rows":[
                    {"event_id":"e0","request_body":"body-0"},
                    {"event_id":"e1","request_body":"body-1"},
                    {"event_id":"e2","request_body":"body-2"}
                  ],
                  "next_cursor":"c1","has_more":false,"lag_seconds":2
                }
                """;
    }
}
