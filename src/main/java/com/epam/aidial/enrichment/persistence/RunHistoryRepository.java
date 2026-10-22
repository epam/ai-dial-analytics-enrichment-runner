package com.epam.aidial.enrichment.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Reads of {@code run_history}, and every transition of a run.
 *
 * <p><strong>Each transition is one conditional statement that returns a row count</strong>, never a
 * load-and-save: the entity maps every column, so a save by a verb that loaded the row before a page commit
 * writes the old cursor back. The condition is the run's admissible state, so a verb applied to a run that
 * changed since it was read touches nothing and says so with a zero. The operator verbs turn that zero into a
 * {@code 409}; the executor's own transitions yield quietly.
 *
 * <p>Each is {@link Transactional} so it joins the caller's transaction when there is one (the page commit,
 * the service verbs) and still runs in its own when called alone (the batch failure handler).
 *
 * <p>Native SQL, so the statements read exactly as PostgreSQL runs them: {@code IS NOT DISTINCT FROM} is the
 * null-safe comparison a run's first page (null cursor) and a pre-V10 run (null generation) need, and the casts
 * give a null parameter a type PostgreSQL can resolve.
 */
public interface RunHistoryRepository extends JpaRepository<RunHistoryEntity, String> {

    List<RunHistoryEntity> findByPipelineNameAndState(String pipelineName, String state);

    /** How many runs are in a state; the status heartbeat's backfill counts. */
    long countByState(String state);

    /**
     * Advances a running run by one committed page, and only if its cursor is still the one the page was read
     * from and its pinned generation is still the one the batch drains under — the single-writer check. Zero rows
     * means the run was cancelled, paused or advanced by another execution since the page was read, or resumed
     * under a new generation (an execution that read the old one must not write a page keyed to it). A successful
     * commit also clears the last batch failure, so a stale error cannot read as current on a run that is making
     * progress.
     *
     * @param next the cursor to store, already passed through {@code CursorGuard.advance}
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE run_history
               SET cursor = CAST(:next AS TEXT), rows_done = rows_done + :rowsDelta,
                   last_error = NULL, last_error_at = NULL
             WHERE id = :id AND state = 'running' AND cursor IS NOT DISTINCT FROM CAST(:cursorBefore AS TEXT)
               AND pipeline_generation IS NOT DISTINCT FROM CAST(:generation AS BIGINT)
            """, nativeQuery = true)
    int advance(@Param("id") String id, @Param("generation") Long generation, @Param("cursorBefore") String cursorBefore,
                @Param("next") String next, @Param("rowsDelta") long rowsDelta);

    /**
     * The executor's pause: takes effect only on a still-running run that is still pinned to the generation the
     * pausing execution read, so a stale execution cannot re-pause a run an operator just resumed under a newer
     * one.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE run_history SET state = 'paused', paused_reason = :reason, paused_at = :at
             WHERE id = :id AND state = 'running'
               AND pipeline_generation IS NOT DISTINCT FROM CAST(:generation AS BIGINT)
            """, nativeQuery = true)
    int pauseIfRunning(@Param("id") String id, @Param("generation") Long generation, @Param("reason") String reason,
                       @Param("at") Instant at);

    /** The executor's completion: takes effect only on a still-running run, so a cancel's instant stands. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE run_history SET state = 'done', finished_at = :at
             WHERE id = :id AND state = 'running'
            """, nativeQuery = true)
    int markDoneIfRunning(@Param("id") String id, @Param("at") Instant at);

    /** An operator's pause. Same statement as the executor's, with the operator reason. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE run_history SET state = 'paused', paused_reason = 'operator', paused_at = :at
             WHERE id = :id AND state = 'running'
            """, nativeQuery = true)
    int pauseByOperator(@Param("id") String id, @Param("at") Instant at);

    /**
     * Sets a paused run running again under {@code generation}, clearing its reason in the same statement. Can
     * raise a unique violation on {@code run_history_one_running_per_pipeline_uk} if another run of the
     * pipeline became running concurrently.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE run_history
               SET state = 'running', paused_reason = NULL, paused_at = NULL, pipeline_generation = :generation
             WHERE id = :id AND state = 'paused'
            """, nativeQuery = true)
    int resumeIfPaused(@Param("id") String id, @Param("generation") long generation);

    /**
     * Cancels a running or paused run, recording its completion instant once and clearing any pause reason.
     * {@code last_error} is kept: "why was this run given up on?" is what it answers.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE run_history SET state = 'cancelled', finished_at = :at, paused_reason = NULL, paused_at = NULL
             WHERE id = :id AND state IN ('running', 'paused')
            """, nativeQuery = true)
    int cancelIfActive(@Param("id") String id, @Param("at") Instant at);

    /**
     * Cancels every running or paused run of a pipeline, the way {@link #cancelIfActive} cancels one. Only for a
     * pipeline recreated under the same name: its runs scan for the pipeline that was deleted, and a paused one could
     * otherwise be resumed onto the new pipeline with the old run's cursor.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE run_history SET state = 'cancelled', finished_at = :at, paused_reason = NULL, paused_at = NULL
             WHERE pipeline_name = :pipelineName AND state IN ('running', 'paused')
            """, nativeQuery = true)
    int cancelActiveForPipeline(@Param("pipelineName") String pipelineName, @Param("at") Instant at);

    /** Records a batch failure on a still-running run; zero rows means the run is no longer running. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE run_history SET last_error = :message, last_error_at = :at
             WHERE id = :id AND state = 'running'
            """, nativeQuery = true)
    int recordBatchFailureIfRunning(@Param("id") String id, @Param("message") String message, @Param("at") Instant at);

    /** Pins a running run that predates the generation column to the pipeline's current generation. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE run_history SET pipeline_generation = :generation
             WHERE id = :id AND state = 'running' AND pipeline_generation IS NULL
            """, nativeQuery = true)
    int adoptGeneration(@Param("id") String id, @Param("generation") long generation);

    /**
     * The run listing: every filter optional, newest first. One query with optional predicates rather than a
     * derived method per combination of three filters.
     */
    @Query("""
            SELECT r FROM RunHistoryEntity r
             WHERE (:pipelineName IS NULL OR r.pipelineName = :pipelineName)
               AND (:state IS NULL OR r.state = :state)
               AND (:pausedReason IS NULL OR r.pausedReason = :pausedReason)
             ORDER BY r.startedAt DESC, r.id DESC
            """)
    List<RunHistoryEntity> list(@Param("pipelineName") String pipelineName, @Param("state") String state,
                                @Param("pausedReason") String pausedReason, Pageable pageable);
}
