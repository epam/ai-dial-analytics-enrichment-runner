package com.epam.aidial.enrichment.worker.upsert;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * A write work item: the rows one compute produced, waiting to be written. A mutable POJO rather than a record
 * because db-scheduler's serializer is Jackson 2 — the same reason {@code ChunkPayload} is one.
 *
 * <p>This is what makes an upsert retry free: the write phase re-posts these rows rather than re-deriving them,
 * so a flaky write costs HTTP calls instead of model calls.
 *
 * <p>It carries <strong>extracted outputs only</strong> — grain keys, label columns and provenance — and never
 * source rows. That is a privacy bound as much as a size one: source rows for a body-reading pipeline are the
 * sensitive data that must not be persisted in {@code scheduled_tasks}, which has no retention bound. Compute
 * has already reduced them to labels by the time this is built, so honouring the constraint costs nothing.
 *
 * <p>{@code groupKey}/{@code groupVersion}/{@code truncated} are present only for a group write, where the
 * successful write is also what stamps the group clean. A row-grain write leaves them absent.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpsertPayload {

    private String pipelineName;
    private long pipelineGeneration;
    private List<Map<String, Object>> rows;

    /** Group grain only: the group whose state this write stamps once it lands. */
    private String groupKey;

    /** Group grain only: the version the group was evaluated at. */
    private Long groupVersion;

    /** Group grain only: whether the evaluated input had been trimmed. */
    private boolean truncated;

    /** The row-grain form: rows only, with no group to stamp. */
    public UpsertPayload(String pipelineName, long pipelineGeneration, List<Map<String, Object>> rows) {
        this(pipelineName, pipelineGeneration, rows, null, null, false);
    }

    /** Whether this write also stamps a group's state on success. */
    public boolean isGroupWrite() {
        return groupKey != null && groupVersion != null;
    }
}
