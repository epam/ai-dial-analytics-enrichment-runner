package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.persistence.PipelineCursorEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorWriter;
import com.epam.aidial.enrichment.worker.ChunkPayload;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.epam.aidial.enrichment.worker.WorkPriority;
import com.github.kagkarlsson.scheduler.Scheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Commits one scan page's effects in a single transaction: enqueue the page's chunk work items (with
 * deterministic ids), record any input-mapping DLQ items, and advance the pipeline cursor to the scan's
 * {@code nextCursor}. Either all three commit together or none do.
 *
 * <p>db-scheduler was wired over a {@code TransactionAwareDataSourceProxy}, so the
 * {@code scheduleIfNotExists} inserts join this Spring-managed transaction alongside the JPA writes.
 * The scan HTTP call happens <em>before</em> this transaction and has no side effects, so it is safe
 * to repeat: a crash before commit leaves the cursor unchanged and the next fire re-scans the same
 * range.
 *
 * <p>It takes the whole {@link ChunkBatch} rather than its two lists, so the row accounting the advance is
 * checked against cannot be one page's counts over another page's rows — see
 * {@link CursorAccountingGuard}.
 */
@Component
@RequiredArgsConstructor
public class ScanPageCommitter {

    private final Scheduler scheduler;
    private final EnrichChunkTask enrichChunkTask;
    private final PipelineCursorWriter cursorWriter;
    private final DlqItemWriter dlqItemWriter;
    private final RunnerMetrics runnerMetrics;

    @Transactional
    public void commit(String pipelineName,
                       long pipelineGeneration,
                       Instant pipelineCreatedAt,
                       String cursorBefore,
                       String nextCursor,
                       Long lagSeconds,
                       ChunkBatch batch) {
        // First, before any write: the refusal must leave the transaction with nothing to undo.
        CursorAccountingGuard.requireAccounted(runnerMetrics, pipelineName, batch.accounting());
        // Then the cursor row's lock, before the enqueues: it is what orders this commit against a recreation reset,
        // and a refusal for a recreated pipeline must come before anything is enqueued for it.
        PipelineCursorEntity cursor = cursorWriter.lockOwned(pipelineName, pipelineCreatedAt);

        List<DlqItemEntity> dlqItems = batch.dlqItems();
        int index = 0;
        for (ChunkPayload chunk : batch.chunks()) {
            String instanceId = DeterministicId.chunkInstanceId(pipelineName, pipelineGeneration, cursorBefore, index++);
            scheduler.scheduleIfNotExists(
                    enrichChunkTask.task().instanceBuilder(instanceId)
                            .data(chunk)
                            .priority(WorkPriority.LIVE)
                            .build(),
                    Instant.now());
        }

        // Upserted, not inserted: these carry grain keys, and the same grain key can be dead-lettered by two
        // pages (a row re-ingested at a new version keeps its grain key). A plain insert would collide with the
        // partial unique key and abort the whole page commit, stalling the pipeline on that page.
        dlqItemWriter.upsertAll(dlqItems);

        // Guarded, not assigned: a blank next_cursor means "no new position", and writing it through would
        // reset the pipeline to the source start on the next fire. The drain loop's stall guard stops the loop,
        // but this transaction has already committed by then. The lag is guarded the same way, so the column and
        // the exported gauge never disagree about the same page.
        cursorWriter.advance(cursor, nextCursor, lagSeconds);
    }
}
