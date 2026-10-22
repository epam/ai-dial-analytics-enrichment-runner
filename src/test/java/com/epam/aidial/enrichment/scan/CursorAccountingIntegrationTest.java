package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.backfill.BackfillPageCommitter;
import com.epam.aidial.enrichment.backfill.RunState;
import com.epam.aidial.enrichment.exception.UnaccountedScanPageException;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.persistence.GroupStateEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.persistence.RunHistoryEntity;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.epam.aidial.enrichment.scan.group.GroupDirtyMarkCommitter;
import com.epam.aidial.enrichment.scan.group.GroupMarkBatch;
import com.epam.aidial.enrichment.support.TestPages;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.worker.ChunkPayload;
import com.epam.aidial.enrichment.worker.ChunkRow;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cursor cannot advance over rows nothing accounted for — on all three cursors.
 *
 * <p>The rule is deliberately blind to cause, so this test never arranges a broken projection: it hands the
 * committers a batch whose accounting does not cover its rows and asserts the advance is refused. That is
 * the point of putting the check in the committers — every silent-skip defect this service has had, and any
 * future one, reaches the same place, and refusing the commit converts the whole class from a permanent
 * loss into a retry without the committer needing to know which invariant broke upstream.
 *
 * <p>The <b>equality</b> test is what the partial cases pin. The builders route every row to exactly one
 * outcome, so asserting the weaker "at least one accounted" would leave a loop that exits early or a branch
 * that forgets to count exactly as invisible as the wholly unaccounted page.
 */
@SpringBootTest
class CursorAccountingIntegrationTest {

    private static final String PIPELINE_NAME = "r-accounting";

    @Autowired
    private ScanPageCommitter scanPageCommitter;

    @Autowired
    private GroupDirtyMarkCommitter groupDirtyMarkCommitter;

    @Autowired
    private BackfillPageCommitter backfillPageCommitter;

    @Autowired
    private PipelineCursorRepository cursorRepository;

    @Autowired
    private RunHistoryRepository runHistoryRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, pipeline_cursor, dlq_item, group_state, run_history");
    }

    @Test
    void whollyUnaccountedPageDoesNotAdvanceTheLiveCursor() {
        double before = refusals();

        assertThatThrownBy(() -> scanPageCommitter.commit(PIPELINE_NAME, 3, TestPipelines.CREATED_AT, null, "c1", 1L,
                new ChunkBatch(List.of(), List.of(), TestPages.unaccounted(1000))))
                .isInstanceOf(UnaccountedScanPageException.class)
                .hasMessageContaining("1000");

        assertThat(cursorRepository.findById(PIPELINE_NAME)).isEmpty();
        assertThat(refusals()).isEqualTo(before + 1);
    }

    @Test
    void partiallyUnaccountedPageDoesNotAdvanceEither() {
        double before = refusals();

        // 9 of 10 rows reached an outcome. The tenth is the bug this equality test exists to catch.
        assertThatThrownBy(() -> scanPageCommitter.commit(PIPELINE_NAME, 3, TestPipelines.CREATED_AT, null, "c1", 1L,
                new ChunkBatch(List.of(chunk()), List.of(), TestPages.partly(10, 9, 0))))
                .isInstanceOf(UnaccountedScanPageException.class);

        assertThat(cursorRepository.findById(PIPELINE_NAME)).isEmpty();
        assertThat(chunkCount()).isZero();
        assertThat(refusals()).isEqualTo(before + 1);
    }

    /**
     * Every deliberate exclusion accounts for its row, so a fully sampled-out page is not a stall: it
     * advances, exactly as it must, or raising a sampling fraction would be the only way to make a pipeline
     * progress.
     */
    @Test
    void pageAccountedForEntirelyBySamplingStillAdvances() {
        scanPageCommitter.commit(PIPELINE_NAME, 3, TestPipelines.CREATED_AT, null, "c1", 1L,
                new ChunkBatch(List.of(), List.of(),
                        TestPages.allSkipped(50, RunnerMetrics.SkipReason.SAMPLING)));

        assertThat(cursorRepository.findById(PIPELINE_NAME)).get()
                .extracting(PipelineCursorEntity::getCursor).isEqualTo("c1");
    }

    /** An empty page has nothing to account for and commits exactly as it always has. */
    @Test
    void anEmptyPageIsUnaffected() {
        scanPageCommitter.commit(PIPELINE_NAME, 3, TestPipelines.CREATED_AT, null, "c1", 0L,
                new ChunkBatch(List.of(), List.of(), TestPages.allWork(0)));

        assertThat(cursorRepository.findById(PIPELINE_NAME)).get()
                .extracting(PipelineCursorEntity::getCursor).isEqualTo("c1");
    }

    /**
     * The group lane needs the guard as much as the row lane and is easier to reason wrongly about: many
     * rows collapse into one mark, so "some marks were written" says nothing about whether every row
     * reached an outcome.
     */
    @Test
    void groupPageWhoseRowsAreNotAllAccountedForDoesNotAdvance() {
        double before = refusals();

        assertThatThrownBy(() -> groupDirtyMarkCommitter.commit(PIPELINE_NAME, TestPipelines.CREATED_AT, "c1", 1L,
                new GroupMarkBatch(List.of(mark()), List.of(), TestPages.partly(100, 40, 0))))
                .isInstanceOf(UnaccountedScanPageException.class);

        assertThat(cursorRepository.findById(PIPELINE_NAME)).isEmpty();
        assertThat(groupStateCount()).isZero();
        assertThat(refusals()).isEqualTo(before + 1);
    }

    @Test
    void groupPageAccountedForEntirelyByItsMarksAdvances() {
        groupDirtyMarkCommitter.commit(PIPELINE_NAME, TestPipelines.CREATED_AT, "c1", 1L,
                new GroupMarkBatch(List.of(mark()), List.of(), TestPages.allWork(100)));

        assertThat(cursorRepository.findById(PIPELINE_NAME)).get()
                .extracting(PipelineCursorEntity::getCursor).isEqualTo("c1");
        assertThat(groupStateCount()).isEqualTo(1);
    }

    /**
     * The backfill's cursor is the one where a silent skip is unrecoverable by waiting: a run drains a whole
     * window, marks itself complete, and nothing afterwards says the window was not covered. So the refusal
     * must also leave the run <b>not done</b>.
     */
    @Test
    void unaccountedBackfillPageNeitherAdvancesTheRunNorCompletesIt() {
        RunHistoryEntity run = startedRun();
        double before = refusals();

        assertThatThrownBy(() -> backfillPageCommitter.commit(run.getId(), PIPELINE_NAME, 3, null, "c1",
                new ChunkBatch(List.of(), List.of(), TestPages.unaccounted(1000))))
                .isInstanceOf(UnaccountedScanPageException.class);

        RunHistoryEntity after = runHistoryRepository.findById(run.getId()).orElseThrow();
        assertThat(after.getCursor()).isNull();
        assertThat(after.getRowsDone()).isZero();
        assertThat(after.getState()).isEqualTo(RunState.RUNNING.dbValue());
        assertThat(after.getFinishedAt()).isNull();
        assertThat(refusals()).isEqualTo(before + 1);
    }

    @Test
    void partiallyUnaccountedBackfillGroupPageDoesNotAdvanceTheRun() {
        RunHistoryEntity run = startedRun();

        assertThatThrownBy(() -> backfillPageCommitter.commitGroupMarks(run.getId(), PIPELINE_NAME, 3, null, "c1",
                new GroupMarkBatch(List.of(mark()), List.of(), TestPages.partly(100, 99, 0))))
                .isInstanceOf(UnaccountedScanPageException.class);

        RunHistoryEntity after = runHistoryRepository.findById(run.getId()).orElseThrow();
        assertThat(after.getCursor()).isNull();
        assertThat(after.getState()).isEqualTo(RunState.RUNNING.dbValue());
        assertThat(groupStateCount()).isZero();
    }

    @Test
    void fullyAccountedBackfillPageAdvancesTheRun() {
        RunHistoryEntity run = startedRun();

        backfillPageCommitter.commit(run.getId(), PIPELINE_NAME, 3, null, "c1",
                new ChunkBatch(List.of(), List.of(),
                        TestPages.allSkipped(50, RunnerMetrics.SkipReason.SAMPLING)));

        RunHistoryEntity after = runHistoryRepository.findById(run.getId()).orElseThrow();
        assertThat(after.getCursor()).isEqualTo("c1");
        assertThat(after.getRowsDone()).isEqualTo(50);
    }

    private RunHistoryEntity startedRun() {
        RunHistoryEntity run = new RunHistoryEntity();
        run.setId("run-" + System.nanoTime());
        run.setPipelineName(PIPELINE_NAME);
        run.setKind("backfill");
        run.setState(RunState.RUNNING.dbValue());
        // The generation the commits below pass: the page commit is conditional on it.
        run.setPipelineGeneration(3L);
        run.setStartedAt(Instant.parse("2026-07-01T10:00:00Z"));
        return runHistoryRepository.save(run);
    }

    private static ChunkPayload chunk() {
        return new ChunkPayload(PIPELINE_NAME, 3, List.of(new ChunkRow("e0", Map.of("prompt", "hi"))));
    }

    private static GroupStateEntity mark() {
        return GroupStateEntity.builder()
                .pipelineName(PIPELINE_NAME)
                .groupKey("chat-a")
                .groupVersion(100L)
                .lastActivityAt(Instant.parse("2026-07-01T10:00:00Z"))
                .build();
    }

    private double refusals() {
        var counter = meterRegistry.find("runner.scan.pages.refused")
                .tag("pipeline", PIPELINE_NAME)
                .tag("reason", RunnerMetrics.RefusalReason.UNACCOUNTED_ROWS.tag())
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private long chunkCount() {
        return count("scheduled_tasks");
    }

    private long groupStateCount() {
        return count("group_state");
    }

    private long count(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
