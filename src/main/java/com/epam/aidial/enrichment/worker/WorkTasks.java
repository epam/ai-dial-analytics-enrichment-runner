package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.upsert.UpsertRowsTask;

import java.util.List;

/**
 * The db-scheduler tasks that hold a pipeline's <em>pending work</em>, as opposed to the recurring ticks that
 * create it (see {@link com.epam.aidial.enrichment.sync.PipelineRouting#PIPELINE_TASK_NAMES}).
 *
 * <p>Every one of them uses a pipeline-prefixed instance id (see
 * {@link com.epam.aidial.enrichment.scan.DeterministicId}), which is what lets a pipeline's in-flight depth be
 * counted with a {@code LIKE 'pipelineName#%'} instead of deserializing payloads.
 *
 * <p>Enumerated in one place because two consumers must agree on it: backpressure, which asks "how much work
 * does this pipeline already have queued?", and the queue-depth gauges, which report the same number per task.
 * Phase 2 turned one work-item type into three, and a list that named only the first made backpressure blind
 * to two thirds of the queue.
 */
public final class WorkTasks {

    /** Every work-item task whose instance ids are pipeline-prefixed, so a pipeline's depth spans all of them. */
    public static final List<String> PIPELINE_SCOPED = List.of(
            EnrichChunkTask.TASK_NAME, GroupChunkTask.TASK_NAME, UpsertRowsTask.TASK_NAME);

    private WorkTasks() {
    }
}
