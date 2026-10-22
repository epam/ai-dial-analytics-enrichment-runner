package com.epam.aidial.enrichment.class1;

import com.github.kagkarlsson.scheduler.task.helper.PlainScheduleAndData;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTaskWithPersistentSchedule;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The db-scheduler recurring task type that drives per-pipeline Class-1 materialize ticks — the Class-1
 * counterpart of {@code pipeline-scan}. Each enabled Class-1 pipeline is one <em>instance</em> (instance id =
 * pipeline name), carrying its own persisted schedule via {@link PlainScheduleAndData}, so cadence can differ
 * per pipeline and change at runtime.
 *
 * <p>Class-1 gets its own task rather than a branch inside the scan tick because the two share only the
 * cursor: a different endpoint, a different response shape, no chunking, no queue and different gates.
 *
 * <p>The handler delegates to {@link Class1MaterializeExecutor}, resolved lazily via an
 * {@link ObjectProvider} to break the definitional cycle (the executor needs the {@code Scheduler},
 * which needs this task).
 */
@Component
public class Class1MaterializeTask {

    public static final String TASK_NAME = "class1-materialize";

    private final RecurringTaskWithPersistentSchedule<PlainScheduleAndData> task;

    public Class1MaterializeTask(ObjectProvider<Class1MaterializeExecutor> executorProvider) {
        this.task = Tasks.recurringWithPersistentSchedule(TASK_NAME, PlainScheduleAndData.class)
                .execute((instance, context) -> executorProvider.getObject().execute(instance.getId()));
    }

    public RecurringTaskWithPersistentSchedule<PlainScheduleAndData> task() {
        return task;
    }
}
