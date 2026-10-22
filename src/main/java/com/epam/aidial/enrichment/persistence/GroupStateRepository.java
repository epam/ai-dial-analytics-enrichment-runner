package com.epam.aidial.enrichment.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA access to {@code group_state}. The dirty-mark upsert, the sweep's dirty-and-ready select and the
 * eviction delete are set-based and live in {@link GroupStateWriter}; this interface covers the
 * single-row reads and writes the group worker needs.
 */
public interface GroupStateRepository extends JpaRepository<GroupStateEntity, GroupStateId> {

    /** Active groups across all pipelines — the gauge's value, refreshed off the scrape path. */
    @Query("SELECT count(g) FROM GroupStateEntity g")
    long countActive();

    /**
     * Groups whose version is ahead of what was last computed: the dirty backlog. This is the number that
     * distinguishes a group path that is draining from one that is stuck, which is otherwise indistinguishable
     * from one that is simply quiet.
     */
    @Query("SELECT count(g) FROM GroupStateEntity g "
            + "WHERE g.computedVersion IS NULL OR g.groupVersion > g.computedVersion")
    long countDirty();

    /** The group listing's first page, in the order the caller's {@link Pageable} sorts by. */
    List<GroupStateEntity> findByPipelineName(String pipelineName, Pageable pageable);

    /**
     * The oldest-first group listing page strictly past the group at {@code (afterAt, afterKey)}. A row-value
     * comparison, as in {@link DlqItemRepository#listAfter}, so PostgreSQL starts the index scan at the cursor.
     */
    @Query("SELECT g FROM GroupStateEntity g WHERE g.pipelineName = :pipelineName"
            + " AND (g.lastActivityAt, g.groupKey) > (:afterAt, :afterKey)"
            + " ORDER BY g.lastActivityAt ASC, g.groupKey ASC")
    List<GroupStateEntity> listAfterAsc(@Param("pipelineName") String pipelineName, @Param("afterAt") Instant afterAt,
                                        @Param("afterKey") String afterKey, Pageable pageable);

    /** The newest-first counterpart of {@link #listAfterAsc}; the operator cannot be bound as a parameter. */
    @Query("SELECT g FROM GroupStateEntity g WHERE g.pipelineName = :pipelineName"
            + " AND (g.lastActivityAt, g.groupKey) < (:afterAt, :afterKey)"
            + " ORDER BY g.lastActivityAt DESC, g.groupKey DESC")
    List<GroupStateEntity> listAfterDesc(@Param("pipelineName") String pipelineName, @Param("afterAt") Instant afterAt,
                                         @Param("afterKey") String afterKey, Pageable pageable);

    long countByPipelineName(String pipelineName);

    Optional<GroupStateEntity> findByPipelineNameAndGroupKey(String pipelineName, String groupKey);
}
