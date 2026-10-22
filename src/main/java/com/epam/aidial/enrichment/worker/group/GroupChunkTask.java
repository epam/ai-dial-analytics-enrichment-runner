package com.epam.aidial.enrichment.worker.group;

import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The db-scheduler one-time task for a group work item: one execution evaluates one group, enqueued by the
 * readiness sweep and executed by {@link GroupWorkerService}.
 *
 * <p>The handler is resolved lazily through an {@link ObjectProvider} to break the definitional cycle — the
 * worker needs the {@code Scheduler}, which needs this task.
 */
@Component
public class GroupChunkTask {

    public static final String TASK_NAME = "enrich-group";

    private final OneTimeTask<GroupPayload> task;

    public GroupChunkTask(ObjectProvider<GroupWorkerService> workerProvider, GroupFailureHandler failureHandler) {
        this.task = Tasks.oneTime(TASK_NAME, GroupPayload.class)
                .onFailure(failureHandler)
                .execute((instance, context) ->
                        workerProvider.getObject().process(instance.getId(), instance.getData()));
    }

    public OneTimeTask<GroupPayload> task() {
        return task;
    }
}
