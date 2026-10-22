package com.epam.aidial.enrichment.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A backfill (or schedule) run's bookkeeping. A backfill tracks its own opaque cursor here,
 * independent of the live {@code pipeline_cursor}, so live and backfill scans of the same pipeline do not
 * interfere; because ADAS treats the cursor statelessly, both scans are identical to ADAS.
 *
 * <p><strong>Never saved after the insert.</strong> The entity has no {@code @DynamicUpdate} and no
 * {@code @Version}, so a save writes every column — and a verb that loaded the row before a page commit would
 * write the old cursor back. Every transition is a conditional single-statement update on
 * {@link RunHistoryRepository} instead.
 *
 * <p>There is deliberately no total: ADAS exposes no cardinality, the cursor is opaque, the window's upper bound
 * moves, and a source count would not be comparable with {@link #rowsDone} anyway.
 */
@Entity
@Table(name = "run_history")
@Getter
@Setter
@NoArgsConstructor
public class RunHistoryEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "pipeline_name")
    private String pipelineName;

    @Column(name = "kind")
    private String kind;

    @Column(name = "state")
    private String state;

    @Column(name = "rows_done")
    private long rowsDone;

    @Column(name = "cursor")
    private String cursor;

    /** Exclusive lower bound of the version window this run covers; null for a full replay. */
    @Column(name = "window_from")
    private Instant windowFrom;

    /** Inclusive upper bound of the version window this run covers; null for a full replay. */
    @Column(name = "window_to")
    private Instant windowTo;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * Why the run is paused, as {@code BackfillPauseReason.dbValue()}; present only while it is paused, and always
     * together with {@link #pausedAt}. A {@code String}, like {@link #state} and {@link #kind}, so the mapper
     * serializes the stored spelling rather than an enum's {@code name()}.
     */
    @Column(name = "paused_reason")
    private String pausedReason;

    @Column(name = "paused_at")
    private Instant pausedAt;

    /** The classified reason the last batch failed; cleared by the next successful page commit. */
    @Column(name = "last_error")
    private String lastError;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    /**
     * The pipeline {@code generation} the run is draining under — set at start, adopted again on resume. Null only
     * for a run created before it was recorded, which adopts the current generation on its next batch.
     */
    @Column(name = "pipeline_generation")
    private Long pipelineGeneration;

    @PrePersist
    void prePersist() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }
}
