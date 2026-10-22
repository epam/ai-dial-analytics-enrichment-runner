package com.epam.aidial.enrichment.support;

import com.epam.aidial.enrichment.worker.upsert.UpsertPayload;
import com.epam.aidial.enrichment.worker.upsert.UpsertRowsTask;
import com.epam.aidial.enrichment.worker.upsert.UpsertService;
import com.github.kagkarlsson.scheduler.Scheduler;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the queued write phase in a test, the way the scheduler would.
 *
 * <p>Since H1 the compute phase ends at an enqueue, so a test that wants an enrichment row written must run both
 * phases. Reading the items back out of {@code scheduled_tasks} rather than passing payloads in memory means the
 * payload's serialization round-trip is exercised too — which is where a Jackson-2 POJO mistake would hide.
 */
@RequiredArgsConstructor
public final class UpsertDrain {

    private final Scheduler scheduler;
    private final UpsertService upsertService;

    /** Executes every queued write item and returns how many ran. */
    public int drain() {
        List<Runnable> pending = new ArrayList<>();
        scheduler.fetchScheduledExecutionsForTask(UpsertRowsTask.TASK_NAME, UpsertPayload.class, execution -> {
            String id = execution.getTaskInstance().getId();
            UpsertPayload payload = execution.getData();
            pending.add(() -> upsertService.write(id, payload));
        });
        pending.forEach(Runnable::run);
        return pending.size();
    }
}
