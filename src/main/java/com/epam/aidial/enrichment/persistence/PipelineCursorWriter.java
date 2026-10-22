package com.epam.aidial.enrichment.persistence;

import com.epam.aidial.enrichment.exception.PipelineRecreatedException;
import com.epam.aidial.enrichment.scan.CursorGuard;
import com.epam.aidial.enrichment.scan.LagSignal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The one write that advances a live cursor, shared by the row, group and Class-1 lanes.
 *
 * <p><strong>It refuses a commit for a pipeline that no longer owns the row.</strong> A tick reads its declaration
 * and cursor when it starts; if the pipeline is deleted and recreated under the same name meanwhile, the sync resets
 * the row for the new pipeline, and a plain find-modify-save would then write the old pipeline's position over it,
 * so the new pipeline would skip history after all. The row is therefore locked and its {@code pipeline_created_at}
 * compared with the {@code created_at} the tick ran under.
 *
 * <p><strong>The cursor row is the first lock a commit takes</strong> ({@link #lockOwned}, before any enqueue or
 * mark), and the reset's first statement takes the same lock. That one order is what makes the two safe against each
 * other: either the commit holds the row first, and the reset waits until the commit's chunks and marks are
 * committed and then discards them with everything else; or the reset holds it first, and the commit waits, finds a
 * row it does not own, and refuses before writing anything. It also leaves no second lock the two could take in
 * opposite orders, so neither deadlocks the other.
 */
@Component
@RequiredArgsConstructor
public class PipelineCursorWriter {

    private final PipelineCursorRepository repository;

    /**
     * Locks the pipeline's cursor row for the rest of the caller's transaction, inserting it first when there is none,
     * and checks that the tick's pipeline still owns it.
     *
     * @param pipelineCreatedAt the {@code created_at} of the declaration the tick ran under; null skips the check
     * @throws PipelineRecreatedException when the row now belongs to a recreated pipeline
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PipelineCursorEntity lockOwned(String pipelineName, Instant pipelineCreatedAt) {
        repository.insertIfAbsent(pipelineName, pipelineCreatedAt, Instant.now());
        PipelineCursorEntity cursor = repository.findForUpdate(pipelineName)
                .orElseThrow(() -> new IllegalStateException("pipeline_cursor row for '" + pipelineName + "' vanished under its lock"));
        Instant owner = cursor.getPipelineCreatedAt();
        if (owner != null && pipelineCreatedAt != null && !owner.equals(pipelineCreatedAt)) {
            throw new PipelineRecreatedException(pipelineName, pipelineCreatedAt, owner);
        }
        return cursor;
    }

    /**
     * Advances a row {@link #lockOwned} returned and records the scan, guarded the way every lane guards it: a blank
     * {@code nextCursor} keeps the stored position, and an unknown lag keeps the last known one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void advance(PipelineCursorEntity cursor, String nextCursor, Long lagSeconds) {
        Instant now = Instant.now();
        cursor.setCursor(CursorGuard.advance(cursor.getCursor(), nextCursor));
        cursor.setUpdatedAt(now);
        cursor.setLastScanAt(now);
        cursor.setLagSeconds(LagSignal.orKeep(cursor.getLagSeconds(), lagSeconds));
        repository.save(cursor);
    }

    /**
     * Both steps in a transaction of their own, for the Class-1 lane, which has nothing else to commit with the cursor.
     *
     * @throws PipelineRecreatedException when the row now belongs to a recreated pipeline
     */
    @Transactional
    public void lockAndAdvance(String pipelineName, Instant pipelineCreatedAt, String nextCursor, Long lagSeconds) {
        advance(lockOwned(pipelineName, pipelineCreatedAt), nextCursor, lagSeconds);
    }
}
