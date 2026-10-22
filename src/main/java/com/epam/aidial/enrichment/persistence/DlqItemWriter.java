package com.epam.aidial.enrichment.persistence;

import com.epam.aidial.enrichment.observability.status.PipelineActivity;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * The one way a dead-letter item is written, and the reason it exists is
 * {@code dlq_item_row_identity_uk} — a <em>partial</em> unique index on
 * {@code (pipeline_name, grain_key, pipeline_generation) WHERE grain_key IS NOT NULL}.
 *
 * <p>Any insert carrying a grain key can therefore collide, and every path that dead-letters one does so more
 * than once by design: a work item runs at-least-once, so the same bad row, or the same poison group, is
 * dead-lettered on several attempts. A plain {@code save()} turns the second one into a constraint violation —
 * which a failure handler swallows (losing the payload it was archiving) and a worker propagates (turning a
 * permanent rejection into an endless retry chain). Upserting on that key is what makes the write idempotent
 * instead.
 *
 * <p>An item with <em>no</em> grain key — an exhausted row chunk — does not satisfy the index predicate, so the
 * conflict arbiter simply does not apply to it and the same statement inserts it. One path, not two.
 *
 * <p>{@code run_id} — the backfill run that produced the item — is overwritten on conflict and is deliberately
 * <em>not</em> part of the identity. Adding it would split a grain failing on the live path and again in a replay
 * at the same generation into two rows, re-admitting the duplicate accumulation this index closed. So it follows
 * the last writer (but see below), back to null when the live path re-fails the grain. Items without a grain key are plain
 * inserts and never collapse.
 *
 * <p><b>A payload-less repeat never overwrites a requeueable item.</b> A group's exhausted evaluation (with its
 * payload) and a mark-time {@code input_map} failure for the same group key (without one) share the identity.
 * When the payload-less one lands second, the row keeps its payload and the task and run that go with it, and
 * only the stage and error move — otherwise a replay meeting one bad row of a group would make the live path's
 * requeueable failure unrequeueable and file it under the run.
 *
 * <p>{@code created_at} is deliberately not touched on conflict: refreshing it on every repeat would keep
 * pushing the item out of the retention sweep's reach, so a permanently failing item would never expire.
 */
@Component
@RequiredArgsConstructor
public class DlqItemWriter {

    private static final String UPSERT = """
            INSERT INTO dlq_item
                (pipeline_name, grain_key, pipeline_generation, stage, error, raw_response, payload, task_name,
                 run_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (pipeline_name, grain_key, pipeline_generation) WHERE grain_key IS NOT NULL
            DO UPDATE SET stage = EXCLUDED.stage, error = EXCLUDED.error,
                          raw_response = EXCLUDED.raw_response,
                          payload = CASE WHEN %1$s THEN dlq_item.payload ELSE EXCLUDED.payload END,
                          task_name = CASE WHEN %1$s THEN dlq_item.task_name ELSE EXCLUDED.task_name END,
                          run_id = CASE WHEN %1$s THEN dlq_item.run_id ELSE EXCLUDED.run_id END
            """.formatted("EXCLUDED.payload IS NULL AND dlq_item.payload IS NOT NULL");

    private final JdbcTemplate jdbcTemplate;
    private final PipelineActivity pipelineActivity;

    /** Upserts one dead-letter item. */
    public void upsert(DlqItemEntity item) {
        upsertAll(List.of(item));
    }

    /** Upserts a batch of dead-letter items — a compute phase's per-row failures, or a page's. */
    public void upsertAll(List<DlqItemEntity> items) {
        if (items.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(UPSERT, items, items.size(), DlqItemWriter::bind);
        items.forEach(item -> pipelineActivity.recordDeadLetters(item.getPipelineName(), 1));
    }

    private static void bind(PreparedStatement ps, DlqItemEntity item) throws SQLException {
        ps.setString(1, item.getPipelineName());
        ps.setString(2, item.getGrainKey());
        if (item.getPipelineGeneration() != null) {
            ps.setLong(3, item.getPipelineGeneration());
        } else {
            ps.setNull(3, Types.BIGINT);
        }
        ps.setString(4, item.getStage());
        ps.setString(5, item.getError());
        ps.setString(6, item.getRawResponse());
        ps.setString(7, item.getPayload());
        // The column is NOT NULL with an enrich-chunk default, which only the JPA @PrePersist path applies.
        ps.setString(8, item.getTaskName() != null ? item.getTaskName() : EnrichChunkTask.TASK_NAME);
        ps.setString(9, item.getRunId());
    }
}
