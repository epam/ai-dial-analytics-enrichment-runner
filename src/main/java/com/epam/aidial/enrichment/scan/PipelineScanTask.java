package com.epam.aidial.enrichment.scan;

import com.github.kagkarlsson.scheduler.task.helper.PlainScheduleAndData;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTaskWithPersistentSchedule;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The single db-scheduler recurring task type that drives per-pipeline incremental scans. Each enabled
 * pipeline is one <em>instance</em> of this task (instance id = pipeline name), carrying its own persisted
 * schedule (its cron for a {@code schedule} pipeline, the runner's {@code group.scan-cadence} fixed delay for a
 * {@code group} one) via {@link PlainScheduleAndData} — so the schedule can differ per pipeline and change at
 * runtime.
 *
 * <p>The handler delegates to {@link PipelineScanExecutor}, resolved lazily via an {@link ObjectProvider}
 * to break the definitional cycle (the executor needs the {@code Scheduler}, which needs this task).
 */
@Component
public class PipelineScanTask {

    public static final String TASK_NAME = "pipeline-scan";

    private final RecurringTaskWithPersistentSchedule<PlainScheduleAndData> task;

    public PipelineScanTask(ObjectProvider<PipelineScanExecutor> executorProvider) {
        this.task = Tasks.recurringWithPersistentSchedule(TASK_NAME, PlainScheduleAndData.class)
                .execute((instance, context) -> executorProvider.getObject().execute(instance.getId()));
    }

    public RecurringTaskWithPersistentSchedule<PlainScheduleAndData> task() {
        return task;
    }
}
