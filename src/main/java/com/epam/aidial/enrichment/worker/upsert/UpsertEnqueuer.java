package com.epam.aidial.enrichment.worker.upsert;

import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.worker.WorkPriority;
import com.github.kagkarlsson.scheduler.Scheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Commits a compute phase's outcome: the write item it produced and the dead-letter items it could not produce,
 * in <strong>one</strong> transaction.
 *
 * <p>The atomicity is the point. Without it a crash between "compute succeeded" and "write item enqueued"
 * silently drops the result — the compute item is gone, nothing holds the rows, and the row is never written.
 * The enqueue joins the Spring transaction through the tx-aware datasource, the same mechanism the scan's atomic
 * enqueue-and-cursor-advance uses.
 */
@Component
@RequiredArgsConstructor
public class UpsertEnqueuer {

    private final Scheduler scheduler;
    private final UpsertRowsTask upsertRowsTask;
    private final DlqItemWriter dlqItemWriter;

    /**
     * @param computeInstanceId the compute item's id, which the write item's id derives from so a repeated
     *                          compute enqueues one write
     * @param payload           the write item, or null when the compute produced no rows
     * @param dlqItems          per-row dead-letters from the compute phase
     */
    @Transactional
    public void commit(String computeInstanceId, UpsertPayload payload, List<DlqItemEntity> dlqItems) {
        dlqItemWriter.upsertAll(dlqItems);
        if (payload == null || payload.getRows() == null || payload.getRows().isEmpty()) {
            return;
        }
        scheduler.scheduleIfNotExists(
                upsertRowsTask.task()
                        .instanceBuilder(DeterministicId.upsertInstanceId(computeInstanceId))
                        .data(payload)
                        .priority(WorkPriority.WRITE_BACK)
                        .build(),
                Instant.now());
    }
}
