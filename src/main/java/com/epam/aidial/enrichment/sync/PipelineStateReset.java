package com.epam.aidial.enrichment.sync;

import com.epam.aidial.enrichment.persistence.GroupStateWriter;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.epam.aidial.enrichment.scan.InflightCounter;
import com.epam.aidial.enrichment.worker.WorkTasks;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Discards the persisted runner state of a pipeline recreated under the same name, so the new pipeline starts the way
 * a brand-new one does: no cursor, no groups, nothing queued, no backfill in progress. Its own bean because the sync's
 * reconcile is not transactional, and a call from inside it would bypass the proxy.
 *
 * <p><strong>The cursor row goes first, as a compare-and-set.</strong> It is the lock every live commit takes first
 * (see {@link com.epam.aidial.enrichment.persistence.PipelineCursorWriter}), so a commit racing the reset either
 * finishes before it — and its chunks and marks are then discarded below — or waits and refuses. And it is
 * conditional on the row still recording the {@code created_at} the sync read: a second pod that read the same old
 * value finds the row already handed over and resets nothing, rather than discarding progress the new pipeline has
 * made since. After the cursor, the order is the one the backfill commits already use (run, then group marks), so
 * neither deadlocks the other.
 *
 * <p>Kept, deliberately: {@code dlq_item} and {@code spend_counter} rows (what failed and what was spent under the
 * name), {@code run_history} rows (cancelled, not deleted), and executions already picked, which are running.
 */
@Component
@RequiredArgsConstructor
public class PipelineStateReset {

    private final RunHistoryRepository runHistoryRepository;
    private final GroupStateWriter groupStateWriter;
    private final PipelineCursorRepository cursorRepository;
    private final InflightCounter inflightCounter;
    private final Clock clock;

    /** What one reset removed, for the line that announces it. */
    public record Removed(int runsCancelled, int groups, int workItems) {
    }

    /**
     * Resets the pipeline's persisted state and records {@code createdAt} as the pipeline its name now means.
     *
     * @param expected  the {@code created_at} the sync read from the cursor row
     * @param createdAt the recreated pipeline's {@code created_at}, stamped on the reset cursor row
     * @return what was removed, or empty when the row no longer records {@code expected} and nothing was reset
     */
    @Transactional
    public Optional<Removed> reset(String pipelineName, Instant expected, Instant createdAt) {
        Instant now = clock.instant();
        if (cursorRepository.resetOwner(pipelineName, expected, createdAt, now) == 0) {
            return Optional.empty();
        }
        int runs = runHistoryRepository.cancelActiveForPipeline(pipelineName, now);
        int groups = groupStateWriter.deleteAll(pipelineName);
        int workItems = inflightCounter.deletePendingForPipeline(WorkTasks.PIPELINE_SCOPED, pipelineName);
        return Optional.of(new Removed(runs, groups, workItems));
    }
}
