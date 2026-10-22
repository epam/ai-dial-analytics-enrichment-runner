package com.epam.aidial.enrichment.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The opaque scan cursor for one pipeline (ADAS owns the cursor internals; the runner stores it
 * verbatim). Advanced atomically with chunk enqueue by the scan transaction, through
 * {@link PipelineCursorWriter}.
 *
 * <p>The row also records which pipeline the name meant when the runner last saw it: ADAS's {@code created_at}.
 * It is mapped read-only because only the native statements in {@link PipelineCursorRepository} write it — the
 * sync's adoption and reset, and the commit's insert of a missing row — so no save of this entity can overwrite it.
 */
@Entity
@Table(name = "pipeline_cursor")
@Getter
@Setter
@NoArgsConstructor
public class PipelineCursorEntity {

    @Id
    @Column(name = "pipeline_name")
    private String pipelineName;

    @Column(name = "cursor")
    private String cursor;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "last_scan_at")
    private Instant lastScanAt;

    @Column(name = "lag_seconds")
    private Long lagSeconds;

    @Column(name = "pipeline_created_at", insertable = false, updatable = false)
    private Instant pipelineCreatedAt;

    public PipelineCursorEntity(String pipelineName) {
        this.pipelineName = pipelineName;
    }

    @PrePersist
    @PreUpdate
    void stampUpdatedAt() {
        if (updatedAt == null) {
            // updated_at is NOT NULL; keep it set even if a caller persists without stamping it.
            updatedAt = Instant.now();
        }
    }
}
