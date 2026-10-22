package com.epam.aidial.enrichment.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DlqItemRepository extends JpaRepository<DlqItemEntity, Long> {

    /**
     * The listing's filter, every part optional. {@code lane} is {@code live} (no run) or {@code backfill} (any run);
     * a run filter already implies the backfill lane. Shared by the pages and the counts so they always agree.
     */
    String LISTING_FILTER = """
             WHERE (:pipelineName IS NULL OR d.pipelineName = :pipelineName)
               AND (:runId IS NULL OR d.runId = :runId)
               AND (:lane IS NULL
                    OR (:lane = 'live' AND d.runId IS NULL)
                    OR (:lane = 'backfill' AND d.runId IS NOT NULL))
            """;

    /** Newest first, {@code id} breaking ties, so the order is total and a keyset cursor can resume it. */
    String LISTING_ORDER = " ORDER BY d.createdAt DESC, d.id DESC";

    List<DlqItemEntity> findByPipelineName(String pipelineName);

    List<DlqItemEntity> findByRunId(String runId);

    List<DlqItemEntity> findByPipelineNameAndRunId(String pipelineName, String runId);

    /** The listing's first page. */
    @Query("SELECT d FROM DlqItemEntity d" + LISTING_FILTER + LISTING_ORDER)
    List<DlqItemEntity> list(@Param("pipelineName") String pipelineName, @Param("runId") String runId,
                             @Param("lane") String lane, Pageable pageable);

    /**
     * The listing page strictly past the item at {@code (afterAt, afterId)} in listing order. A row-value comparison
     * rather than the expanded {@code OR}: PostgreSQL starts the index scan at the cursor instead of filtering every
     * newer row. A separate method rather than an optional cursor in {@link #list}: a null {@code Instant} bound only
     * under {@code IS NULL} gives PostgreSQL no type to infer.
     */
    @Query("SELECT d FROM DlqItemEntity d" + LISTING_FILTER
            + " AND (d.createdAt, d.id) < (:afterAt, :afterId)" + LISTING_ORDER)
    List<DlqItemEntity> listAfter(@Param("pipelineName") String pipelineName, @Param("runId") String runId,
                                  @Param("lane") String lane, @Param("afterAt") Instant afterAt,
                                  @Param("afterId") long afterId, Pageable pageable);

    /** The listing's counters over the same filter; a cursor never narrows them. */
    @Query("SELECT new com.epam.aidial.enrichment.persistence.DlqCounts(COUNT(d),"
            + " COALESCE(SUM(CASE WHEN d.payload IS NOT NULL THEN 1 ELSE 0 END), 0))"
            + " FROM DlqItemEntity d" + LISTING_FILTER)
    DlqCounts counts(@Param("pipelineName") String pipelineName, @Param("runId") String runId,
                     @Param("lane") String lane);

    long deleteByCreatedAtBefore(Instant cutoff);
}
