package com.epam.aidial.enrichment.worker.group;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A group work item: one execution, one group. A mutable POJO rather than a record because
 * db-scheduler's {@code JacksonSerializer} is Jackson 2 and must round-trip it — the same reason
 * {@link com.epam.aidial.enrichment.worker.ChunkPayload} is one.
 *
 * <p>It carries <strong>keys only</strong>, and that is a <strong>privacy boundary</strong> as much as a size
 * one. The group's member rows are fetched at execution time and never put in the payload: a group's members
 * can be megabytes, and for a body-reading pipeline they are source content that must not be persisted in
 * {@code scheduled_tasks}. A change that adds member rows here "for convenience" would put bodies in a table
 * with no retention bound — it is wrong, not merely inefficient.
 *
 * <p>{@code groupVersion} is what makes the item identifiable: it pins the evaluation to the group as it
 * stood when the sweep selected it, so a group that changes afterwards is a different item.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupPayload {

    private String pipelineName;
    private long pipelineGeneration;
    private String groupKey;
    private long groupVersion;
}
