package com.epam.aidial.enrichment.persistence;

import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A dead-lettered item: a permanent per-row failure (un-mappable input, schema-invalid output, …) or
 * an exhausted chunk execution the failure handler archives. Built generic so future db-scheduler
 * workers can reuse it. The {@code stage} column holds the lowercase {@code DlqStage} db value.
 */
@Entity
@Table(name = "dlq_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DlqItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_name")
    private String pipelineName;

    @Column(name = "grain_key")
    private String grainKey;

    /**
     * The pipeline {@code generation} the failing work item was built against — the third component of
     * {@code dlq_item_row_identity_uk}, which is what collapses a repeated per-row failure into one row.
     *
     * <p>Nullable because a chunk-level archive carries no grain key and so falls outside that partial
     * index. {@code Long}, not {@code Integer}: a generation is a 64-bit counter in ADAS, and the column was
     * widened to {@code BIGINT} with the rename. Rows written before the evaluator fold hold small
     * evaluator-version integers under this name — historical facts nothing will produce again. What
     * changed at the fold is the column's <em>meaning</em>, not its value space: generations and evaluator
     * versions are both small integers from 1, so a failing grain that already has a pre-fold row usually
     * produces a second row beside it (cleared by the retention sweep) and, where the two values happen to
     * coincide, upserts onto it instead. Neither loses anything that matters; a value in this column simply
     * cannot be read without knowing which era wrote it.
     */
    @Column(name = "pipeline_generation")
    private Long pipelineGeneration;

    @Column(name = "stage")
    private String stage;

    @Column(name = "error")
    private String error;

    @Column(name = "raw_response")
    private String rawResponse;

    @Column(name = "payload")
    private String payload;

    /**
     * Which db-scheduler task {@link #payload} belongs to, so a requeue deserializes it into the right
     * work-item type. Defaulted rather than nullable: a payload whose task is unknown could not be requeued.
     */
    @Column(name = "task_name")
    private String taskName;

    /**
     * The backfill run that produced this item, or null for the live path. An attribute, not part of the
     * identity: see {@link DlqItemWriter}. Group-sweep items never carry one — the sweep, not a run, enqueued them.
     */
    @Column(name = "run_id")
    private String runId;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (taskName == null) {
            taskName = EnrichChunkTask.TASK_NAME;
        }
    }
}
