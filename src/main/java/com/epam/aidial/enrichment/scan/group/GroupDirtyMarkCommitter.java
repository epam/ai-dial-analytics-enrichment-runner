package com.epam.aidial.enrichment.scan.group;

import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.persistence.GroupStateWriter;
import com.epam.aidial.enrichment.persistence.PipelineCursorEntity;
import com.epam.aidial.enrichment.persistence.PipelineCursorWriter;
import com.epam.aidial.enrichment.scan.CursorAccountingGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Commits one dirty-mark page atomically: the page's group-state upserts, its dead-letter items, and the
 * advance of {@code pipeline_cursor} to the scan's {@code next_cursor} — all together or none. The group-grain
 * counterpart of {@link com.epam.aidial.enrichment.scan.ScanPageCommitter}.
 *
 * <p>Nothing is enqueued here. The dirty-mark only records that groups changed; deciding which of them is
 * worth evaluating is the readiness sweep's job, on its own schedule. That separation is what keeps this
 * page's cost proportional to its rows rather than to the number of groups.
 *
 * <p>It takes the whole {@link GroupMarkBatch} for the same reason the row lane's committer takes its batch:
 * the accounting the advance is checked against must come from the page being committed. This lane needs
 * the guard as much as the row lane does and is easier to reason wrongly about — many rows collapse into
 * one mark, so "some marks were written" says nothing about whether every row reached an outcome.
 */
@Component
@RequiredArgsConstructor
public class GroupDirtyMarkCommitter {

    private final GroupStateWriter groupStateWriter;
    private final PipelineCursorWriter cursorWriter;
    private final DlqItemWriter dlqItemWriter;
    private final RunnerMetrics runnerMetrics;

    @Transactional
    public void commit(String pipelineName,
                       Instant pipelineCreatedAt,
                       String nextCursor,
                       Long lagSeconds,
                       GroupMarkBatch batch) {
        // First, before any write: the refusal must leave the transaction with nothing to undo.
        CursorAccountingGuard.requireAccounted(runnerMetrics, pipelineName, batch.accounting());
        // Then the cursor row's lock, before the marks: it is what orders this commit against a recreation reset,
        // which deletes the very rows these marks would lock.
        PipelineCursorEntity cursor = cursorWriter.lockOwned(pipelineName, pipelineCreatedAt);

        groupStateWriter.upsertMarks(batch.marks());
        // Upserted for the same reason the marks are: a page can be replayed, and the same group key can be
        // dead-lettered by more than one page, so an insert would collide with the partial unique key and abort
        // the commit.
        dlqItemWriter.upsertAll(batch.dlqItems());

        // Guarded like the row lane's commit: a blank next_cursor must not overwrite the stored position, which
        // would restart the pipeline from the source start rather than merely making no progress, and an unknown
        // lag keeps the last known one.
        cursorWriter.advance(cursor, nextCursor, lagSeconds);
    }
}
