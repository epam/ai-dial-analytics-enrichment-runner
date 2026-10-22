package com.epam.aidial.enrichment.worker.upsert;

import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The db-scheduler one-time task that writes a computed result: the second phase of enrichment.
 *
 * <p>Separating it from compute is the whole point — a transient write failure retries only this item, from the
 * rows it already carries, so a flaky ADAS or a network blip no longer costs a second model call.
 *
 * <p>The handler is resolved lazily through an {@link ObjectProvider} to break the definitional cycle: the
 * service needs the {@code Scheduler}, which needs this task.
 */
@Component
public class UpsertRowsTask {

    public static final String TASK_NAME = "upsert-rows";

    private final OneTimeTask<UpsertPayload> task;

    public UpsertRowsTask(ObjectProvider<UpsertService> serviceProvider, UpsertFailureHandler failureHandler) {
        this.task = Tasks.oneTime(TASK_NAME, UpsertPayload.class)
                .onFailure(failureHandler)
                .execute((instance, context) ->
                        serviceProvider.getObject().write(instance.getId(), instance.getData()));
    }

    public OneTimeTask<UpsertPayload> task() {
        return task;
    }
}
