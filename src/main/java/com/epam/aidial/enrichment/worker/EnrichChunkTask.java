package com.epam.aidial.enrichment.worker;

import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The one-time work-item task the scan enqueues per chunk. The handler delegates to
 * {@link WorkerService} (resolved lazily via an {@link ObjectProvider}); failures are handled by
 * {@link ChunkFailureHandler} (backoff retries then DLQ archive).
 *
 * <p>The queue priorities a chunk is enqueued at live on {@link WorkPriority}, not here: three tasks share
 * them, and holding them on one task is what let the write-back borrow the compute lane's value.
 */
@Component
public class EnrichChunkTask {

    public static final String TASK_NAME = "enrich-chunk";

    private final OneTimeTask<ChunkPayload> task;

    public EnrichChunkTask(ObjectProvider<WorkerService> workerProvider, ChunkFailureHandler failureHandler) {
        this.task = Tasks.oneTime(TASK_NAME, ChunkPayload.class)
                .onFailure(failureHandler)
                .execute((instance, context) -> workerProvider.getObject().process(instance.getId(), instance.getData()));
    }

    public OneTimeTask<ChunkPayload> task() {
        return task;
    }
}
