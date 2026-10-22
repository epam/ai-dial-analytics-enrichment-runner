package com.epam.aidial.enrichment.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface PipelineCursorRepository extends JpaRepository<PipelineCursorEntity, String> {

    /**
     * Records which pipeline the name means without touching its cursor: inserts a row carrying only
     * {@code pipeline_created_at}, or fills the column on a row that has none. A row that already records a value
     * is left alone, so this never hides a recreation; the sync compares that value before calling this.
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO pipeline_cursor (pipeline_name, updated_at, pipeline_created_at) VALUES (:name, :at, :createdAt)
            ON CONFLICT (pipeline_name) DO UPDATE SET pipeline_created_at = EXCLUDED.pipeline_created_at
             WHERE pipeline_cursor.pipeline_created_at IS NULL
            """, nativeQuery = true)
    int adopt(@Param("name") String name, @Param("createdAt") Instant createdAt, @Param("at") Instant at);

    /** Inserts an empty row for a commit to lock, stamped with the pipeline the commit ran under; a no-op when one exists. */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO pipeline_cursor (pipeline_name, updated_at, pipeline_created_at) VALUES (:name, :at, :createdAt)
            ON CONFLICT (pipeline_name) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("name") String name, @Param("createdAt") Instant createdAt, @Param("at") Instant at);

    /** The row, locked for the rest of the caller's transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM PipelineCursorEntity c WHERE c.pipelineName = :name")
    Optional<PipelineCursorEntity> findForUpdate(@Param("name") String name);

    /**
     * Hands the row to a recreated pipeline: clears the cursor, the scan time and the lag, and records the new
     * {@code created_at}, but only while the row still records {@code expected} (null for a row from before the column). Zero rows means another pod already
     * reset it, and there is nothing left to reset.
     * Run first in the reset's transaction, so its row lock orders the reset against a live commit.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE pipeline_cursor SET cursor = NULL, last_scan_at = NULL, lag_seconds = NULL, updated_at = :at,
                   pipeline_created_at = :createdAt
             WHERE pipeline_name = :name AND pipeline_created_at IS NOT DISTINCT FROM CAST(:expected AS TIMESTAMPTZ)
            """, nativeQuery = true)
    int resetOwner(@Param("name") String name, @Param("expected") Instant expected, @Param("createdAt") Instant createdAt,
                   @Param("at") Instant at);
}
