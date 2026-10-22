package com.epam.aidial.enrichment.backfill;

import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.persistence.GroupStateWriter;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.epam.aidial.enrichment.scan.ChunkBatch;
import com.epam.aidial.enrichment.scan.CursorAccountingGuard;
import com.epam.aidial.enrichment.scan.CursorGuard;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.scan.group.GroupMarkBatch;
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
 * Commits one backfill scan page in a single transaction: enqueue the page's chunk work items at
 * <em>backfill</em> priority (so they never starve live scans), record any input-map DLQ items, and
 * advance the run's own cursor + rows_done in {@code run_history} (not the live {@code pipeline_cursor}).
 *
 * <p><b>The row-accounting guard applies here too, and matters most here.</b> The backfill shares the page
 * builders with the live lanes, so it already produces the same accounting; what it does not share is the
 * commit, and this cursor is the one where a silent skip cannot be recovered by waiting. A live scan that
 * burns a page loses one tick's rows and keeps meeting new ones; a run exists to re-drive a <em>history</em>,
 * drains batch by batch, and marks itself complete when its cursor reaches the end of the window — after
 * which nothing says the window was not covered, because "already enriched" is not a predicate anywhere and
 * the run row says done. See {@link CursorAccountingGuard}.
 *
 * <p><b>At most one execution advances a run, and only while it is running.</b> Every commit begins — before any
 * of its writes, inside the same transaction — with a compare-and-set on the run's state, cursor and pinned
 * generation ({@link RunHistoryRepository#advance}). Taking the row lock first is also what keeps a losing
 * execution from reaching the chunk inserts: it waits on the lock, then matches nothing. When the run was
 * cancelled or paused since the page was read, resumed under a newer generation, or another execution of the run
 * advanced it first, the set touches nothing and the committer throws
 * {@link RunSupersededException}, which rolls back the page's enqueues, marks and dead letters with it. That one
 * check stops a cancelled run from spending at its next page and makes duplicate, retried and crash-revived
 * executions harmless: whichever commits second finds the cursor moved and stops.
 *
 * <p>Every dead letter a page writes is stamped with the run, so the run's failures are separable from the
 * live path's.
 */
@Component
@RequiredArgsConstructor
public class BackfillPageCommitter {

    private final Scheduler scheduler;
    private final EnrichChunkTask enrichChunkTask;
    private final RunHistoryRepository runHistoryRepository;
    private final DlqItemWriter dlqItemWriter;
    private final GroupStateWriter groupStateWriter;
    private final RunnerMetrics runnerMetrics;

    /**
     * Commits a row page and answers the cursor it stored.
     *
     * @param pipelineGeneration the generation the run is pinned to and the batch drains under — part of the
     *                           single-writer check, and the namespace of the chunk ids
     */
    @Transactional
    public String commit(String runId,
                         String pipelineName,
                         long pipelineGeneration,
                         String cursorBefore,
                         String nextCursor,
                         ChunkBatch batch) {
        // First, before any write — and before the run is advanced or could be marked complete.
        CursorAccountingGuard.requireAccounted(runnerMetrics, pipelineName, batch.accounting());
        String stored = advanceRun(runId, pipelineGeneration, cursorBefore, nextCursor, batch.rowCount());

        int index = 0;
        for (ChunkPayload chunk : batch.chunks()) {
            String instanceId = DeterministicId.backfillChunkInstanceId(runId, pipelineGeneration, cursorBefore, index++);
            scheduler.scheduleIfNotExists(
                    enrichChunkTask.task().instanceBuilder(instanceId)
                            .data(chunk)
                            .priority(WorkPriority.BACKFILL)
                            .build(),
                    Instant.now());
        }

        dlqItemWriter.upsertAll(stamped(runId, batch.dlqItems()));
        return stored;
    }

    /**
     * Commits a group page: the run's dirty marks, its dead-letters and its cursor advance, together.
     *
     * <p>Nothing is enqueued. Marking a group dirty over the window is the whole job — the readiness sweep is
     * what decides when a dirty group is evaluated, so the pipeline's idle, staleness and cost-ceiling gates still
     * apply. Enqueueing from here would bypass all three. The mark-time dead letters are this run's; the sweep's
     * later evaluations are not.
     */
    @Transactional
    public String commitGroupMarks(String runId,
                                   String pipelineName,
                                   long pipelineGeneration,
                                   String cursorBefore,
                                   String nextCursor,
                                   GroupMarkBatch batch) {
        CursorAccountingGuard.requireAccounted(runnerMetrics, pipelineName, batch.accounting());
        String stored = advanceRun(runId, pipelineGeneration, cursorBefore, nextCursor, batch.rowCount());

        groupStateWriter.upsertMarks(batch.marks());
        dlqItemWriter.upsertAll(stamped(runId, batch.dlqItems()));
        return stored;
    }

    /**
     * Advances a Class-1 run: no work to commit, just the cursor and the rows ADAS reported writing. Answers the
     * cursor it stored.
     */
    @Transactional
    public String commitMaterializedWindow(String runId, long pipelineGeneration, String cursorBefore,
                                           String nextCursor, long rowsWritten) {
        return advanceRun(runId, pipelineGeneration, cursorBefore, nextCursor, rowsWritten);
    }

    private String advanceRun(String runId, long pipelineGeneration, String cursorBefore, String nextCursor,
                              long rowsInPage) {
        // Guarded like the live lanes' commits: a blank next_cursor keeps the run's position, because writing
        // it through would restart the run from the start of its window on resume.
        String next = CursorGuard.advance(cursorBefore, nextCursor);
        if (runHistoryRepository.advance(runId, pipelineGeneration, cursorBefore, next, rowsInPage) == 0) {
            throw new RunSupersededException(runId);
        }
        return next;
    }

    private static List<DlqItemEntity> stamped(String runId, List<DlqItemEntity> items) {
        items.forEach(item -> item.setRunId(runId));
        return items;
    }
}
