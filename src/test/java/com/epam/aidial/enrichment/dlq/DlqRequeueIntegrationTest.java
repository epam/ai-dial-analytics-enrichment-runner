package com.epam.aidial.enrichment.dlq;

import com.epam.aidial.enrichment.exception.DlqNotRequeueableException;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemRepository;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.worker.ChunkPayload;
import com.epam.aidial.enrichment.worker.ChunkRow;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.epam.aidial.enrichment.worker.PreFoldWorkItem;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.group.GroupPayload;
import com.epam.aidial.enrichment.worker.upsert.UpsertPayload;
import com.epam.aidial.enrichment.worker.upsert.UpsertRowsTask;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M5 requeue path: an archived chunk (with payload) is re-enqueued and its DLQ item deleted in one
 * transaction; a per-row poison item (no payload) is rejected as not requeueable. PostgreSQL runs in
 * Testcontainers; the scheduler is not started (scheduling still writes rows).
 */
@SpringBootTest
class DlqRequeueIntegrationTest {

    @Autowired
    private DlqRequeueService requeueService;

    @Autowired
    private DlqItemRepository dlqItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private DlqItemWriter dlqItemWriter;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, dlq_item");
    }

    @Test
    void archivingWithoutGrainKeyInsertsRatherThanArbitrating() {
        // Every archival path shares one upsert statement whose conflict arbiter is a *partial* unique index on
        // (pipeline_name, grain_key, pipeline_generation) WHERE grain_key IS NOT NULL. An exhausted row chunk carries no
        // grain key, so it falls outside the predicate: each one must accumulate its own row rather than
        // colliding, and the statement must not reject it for failing to match the arbiter.
        dlqItemWriter.upsert(DlqItemEntity.builder()
                .pipelineName("r-nograin").pipelineGeneration(3L).stage("dial_call").error("first").build());
        dlqItemWriter.upsert(DlqItemEntity.builder()
                .pipelineName("r-nograin").pipelineGeneration(3L).stage("dial_call").error("second").build());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dlq_item WHERE pipeline_name = ?", Long.class, "r-nograin")).isEqualTo(2);
        // The column is NOT NULL with a default only the JPA path applies, so the writer must supply one.
        assertThat(jdbcTemplate.queryForList(
                "SELECT task_name FROM dlq_item WHERE pipeline_name = ?", String.class, "r-nograin"))
                .containsOnly(EnrichChunkTask.TASK_NAME);
    }

    @Test
    void archivingTheSameGrainKeyTwiceUpdatesInPlaceAndKeepsItsAge() {
        dlqItemWriter.upsert(DlqItemEntity.builder()
                .pipelineName("r-grain").grainKey("g1").pipelineGeneration(3L).stage("group_fetch").error("first")
                .taskName(GroupChunkTask.TASK_NAME).build());
        Object firstCreatedAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM dlq_item WHERE pipeline_name = ?", Object.class, "r-grain");

        dlqItemWriter.upsert(DlqItemEntity.builder()
                .pipelineName("r-grain").grainKey("g1").pipelineGeneration(3L).stage("group_map").error("second")
                .taskName(GroupChunkTask.TASK_NAME).build());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dlq_item WHERE pipeline_name = ?", Long.class, "r-grain")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error FROM dlq_item WHERE pipeline_name = ?", String.class, "r-grain")).isEqualTo("second");
        // created_at is deliberately untouched: refreshing it on every repeat would keep a permanently failing
        // item out of the retention sweep's reach forever.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_at FROM dlq_item WHERE pipeline_name = ?", Object.class, "r-grain"))
                .isEqualTo(firstCreatedAt);
    }

    @Test
    void requeuesArchivedChunkAndDeletesTheItem() {
        ChunkPayload payload = new ChunkPayload("r1", 3, List.of(new ChunkRow("e0", Map.of("prompt", "x"))));
        DlqItemEntity item = dlqItemRepository.save(DlqItemEntity.builder()
                .pipelineName("r1").pipelineGeneration(3L).stage("dial_call").error("boom")
                .payload(jsonMapper.writeValueAsString(payload))
                .build());

        int requeued = requeueService.requeue(item.getId());

        assertThat(requeued).isEqualTo(1);
        assertThat(dlqItemRepository.findById(item.getId())).isEmpty();
        assertThat(enrichChunkCount()).isEqualTo(1);
    }

    /**
     * The one consequence of ADAS's evaluator fold that can bite after its deploy window has closed. An
     * archive written before the fold names {@code evaluatorVersion}, not {@code pipelineGeneration}, so it
     * deserializes with a generation of 0 — and every lane would then do something wrong with it silently: a
     * chunk or group item is dropped by the stale-declaration guard, and an archived <em>write</em> carries
     * rows stamped with the retired {@code evaluator_version} provenance column, which ADAS refuses with
     * HTTP 422 for a reason the operator cannot fix. Refusing at the boundary says so once and leaves the
     * row intact.
     */
    @Test
    void anArchivePredatingTheFoldIsRefusedRatherThanReRun() {
        // Written by hand, because no builder in this repository can produce the retired shape any more —
        // which is the point: this is what a row archived by the previous image actually holds.
        DlqItemEntity item = dlqItemRepository.save(DlqItemEntity.builder()
                .pipelineName("r-prefold")
                .pipelineGeneration(2L)
                .stage("dial_call")
                .error("boom")
                .payload("{\"pipelineName\":\"r-prefold\",\"evaluatorVersion\":2,"
                        + "\"rows\":[{\"grainKey\":\"e0\",\"inputs\":{\"prompt\":\"x\"}}]}")
                .build());

        assertThatThrownBy(() -> requeueService.requeue(item.getId()))
                .isInstanceOf(DlqNotRequeueableException.class)
                .hasMessageContaining("pipeline_generation");
        assertThat(dlqItemRepository.findById(item.getId())).isPresent();
        assertThat(enrichChunkCount()).isZero();
    }

    /**
     * The other shape of the same archive, and the one testing member presence alone would have let
     * through: a payload serialized by <em>this</em> build after it had already deserialized a pre-fold
     * item, so it <em>does</em> name {@code pipelineGeneration}, at 0.
     *
     * <p>The lanes no longer archive a pre-fold work item — the chunk and write lanes adopt the live
     * generation and run it, and the group lane discards it to the readiness sweep. One producer remains,
     * and it is the shape modelled here: a pre-fold <em>write</em> whose pipeline is absent from the
     * cache. {@code UpsertService} archives that item with its rows rather than destroying a paid-for
     * result, and it takes that arm before there is any live generation to adopt. Requeueing it would
     * hand the lane back a payload naming a generation ADAS never served, so it is refused; recovery is a
     * bounded backfill, or a hand replay of the rows the archive keeps readable.
     */
    @Test
    void archiveOfPreFoldWriteWithNoCachedPipelineIsTerminal() {
        DlqItemEntity item = dlqItemRepository.save(DlqItemEntity.builder()
                .pipelineName("r-guarded")
                .pipelineGeneration(PreFoldWorkItem.PRE_FOLD_GENERATION)
                .stage(DlqStage.UPSERT.dbValue())
                .error("pipeline absent from the runner's cache (disabled or deleted) when the write ran")
                .taskName(UpsertRowsTask.TASK_NAME)
                .payload(jsonMapper.writeValueAsString(new UpsertPayload("r-guarded",
                        PreFoldWorkItem.PRE_FOLD_GENERATION, List.of(Map.of("event_id", "e0")))))
                .build());

        assertThatThrownBy(() -> requeueService.requeue(item.getId()))
                .isInstanceOf(DlqNotRequeueableException.class)
                .hasMessageContaining("pipeline_generation");
        // Retained with its rows, which is the whole reason it was archived rather than dropped.
        assertThat(dlqItemRepository.findById(item.getId())).isPresent();
        assertThat(taskCount(UpsertRowsTask.TASK_NAME)).isZero();
    }

    /**
     * A bulk requeue skips such an archive the way it skips a poison row, rather than failing the batch: it
     * means "re-run what can be re-run", and one unreadable archive must not deny the operator the others.
     */
    @Test
    void bulkRequeueSkipsPreFoldArchiveAndStillRequeuesTheRest() {
        dlqItemRepository.save(DlqItemEntity.builder()
                .pipelineName("r-mixed")
                .pipelineGeneration(2L)
                .stage("dial_call")
                .payload("{\"pipelineName\":\"r-mixed\",\"evaluatorVersion\":2,\"rows\":[]}")
                .build());
        dlqItemRepository.save(DlqItemEntity.builder()
                .pipelineName("r-mixed")
                .pipelineGeneration(17L)
                .stage("dial_call")
                .payload(jsonMapper.writeValueAsString(new ChunkPayload("r-mixed", 17,
                        List.of(new ChunkRow("e0", Map.of("prompt", "x"))))))
                .build());

        assertThat(requeueService.requeueByPipeline("r-mixed")).isEqualTo(1);

        assertThat(enrichChunkCount()).isEqualTo(1);
        // The unreadable one is retained for inspection, not deleted with the batch.
        assertThat(dlqItemRepository.findByPipelineName("r-mixed")).hasSize(1);
    }

    @Test
    void poisonItemWithoutPayloadIsNotRequeueable() {
        DlqItemEntity item = dlqItemRepository.save(DlqItemEntity.builder()
                .pipelineName("r1").grainKey("e0").stage("validate").error("bad output")
                .build());

        assertThatThrownBy(() -> requeueService.requeue(item.getId()))
                .isInstanceOf(DlqNotRequeueableException.class);
        assertThat(dlqItemRepository.findById(item.getId())).isPresent();
    }

    private long enrichChunkCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", Long.class, EnrichChunkTask.TASK_NAME);
        return count == null ? 0 : count;
    }

    @Test
    void archivedGroupItemRequeuesOntoTheGroupTask() {
        // Without the archived task_name this payload would be deserialized as a ChunkPayload and enqueued
        // as a chunk with no rows.
        GroupPayload payload = new GroupPayload("r-group", 3, "chat-a", 1_782_400_795_816L);
        DlqItemEntity item = dlqItemRepository.save(DlqItemEntity.builder()
                .pipelineName("r-group")
                .grainKey("chat-a")
                .pipelineGeneration(3L)
                .stage("group_map")
                .payload(jsonMapper.writeValueAsString(payload))
                .taskName(GroupChunkTask.TASK_NAME)
                .build());

        assertThat(requeueService.requeue(item.getId())).isEqualTo(1);

        assertThat(taskCount(GroupChunkTask.TASK_NAME)).isEqualTo(1);
        assertThat(taskCount(EnrichChunkTask.TASK_NAME)).isZero();
        assertThat(dlqItemRepository.findById(item.getId())).isEmpty();
    }

    @Test
    void legacyItemWithoutTaskNameRequeuesAsChunk() {
        // Rows archived before group work existed default to enrich-chunk.
        DlqItemEntity item = dlqItemRepository.save(DlqItemEntity.builder()
                .pipelineName("r-legacy")
                .pipelineGeneration(3L)
                .stage("dial_call")
                .payload(jsonMapper.writeValueAsString(
                        new ChunkPayload("r-legacy", 3, List.of(new ChunkRow("e0", Map.of("k", "v"))))))
                .build());

        assertThat(requeueService.requeue(item.getId())).isEqualTo(1);

        assertThat(taskCount(EnrichChunkTask.TASK_NAME)).isEqualTo(1);
        assertThat(taskCount(GroupChunkTask.TASK_NAME)).isZero();
    }

    @Test
    void archivedUpsertItemRequeuesAsWriteNotCompute() {
        // Requeuing a write must re-attempt the write. Deserializing its payload as a ChunkPayload would
        // enqueue a compute with no rows — the same class of bug the task_name column exists to prevent.
        UpsertPayload payload = new UpsertPayload("r-upsert", 3,
                List.of(Map.of("event_id", "e0", "sentiment", "positive")));
        DlqItemEntity item = dlqItemRepository.save(DlqItemEntity.builder()
                .pipelineName("r-upsert")
                .pipelineGeneration(3L)
                .stage("upsert")
                .payload(jsonMapper.writeValueAsString(payload))
                .taskName(UpsertRowsTask.TASK_NAME)
                .build());

        assertThat(requeueService.requeue(item.getId())).isEqualTo(1);

        assertThat(taskCount(UpsertRowsTask.TASK_NAME)).isEqualTo(1);
        assertThat(taskCount(EnrichChunkTask.TASK_NAME)).isZero();
        assertThat(dlqItemRepository.findById(item.getId())).isEmpty();
    }

    private long taskCount(String taskName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", Long.class, taskName);
        return count == null ? 0 : count;
    }
}
