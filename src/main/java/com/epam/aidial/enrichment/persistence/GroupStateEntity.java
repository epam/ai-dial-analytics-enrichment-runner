package com.epam.aidial.enrichment.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One active group of a group pipeline. {@code groupVersion} is {@code max(source version)} over the members
 * seen so far as epoch millis; the group is <em>dirty</em> while it exceeds {@code computedVersion}.
 *
 * <p>{@code computedTruncated} is the only record anywhere that a group's label was derived from a
 * trimmed member set — ADAS's write-back allow-list refuses it as an enrichment column.
 */
@Entity
@Table(name = "group_state")
@IdClass(GroupStateId.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupStateEntity {

    @Id
    @Column(name = "pipeline_name")
    private String pipelineName;

    @Id
    @Column(name = "group_key")
    private String groupKey;

    @Column(name = "group_version")
    private long groupVersion;

    @Column(name = "last_activity_at")
    private Instant lastActivityAt;

    /**
     * Whether any member seen so far satisfied the pipeline's {@code ready_when.signal} — ADAS's verdict,
     * projected as {@code __signal} on the scanned row, never re-evaluated here. Monotone: the upsert ORs
     * it in, so replaying a page cannot un-signal a group, and an evaluation does not clear it.
     */
    @Column(name = "signalled")
    private boolean signalled;

    @Column(name = "computed_version")
    private Long computedVersion;

    @Column(name = "computed_at")
    private Instant computedAt;

    @Column(name = "computed_truncated")
    private boolean computedTruncated;

    /** The UTC day {@link #evaluations} counts, so the ceiling's daily reset needs no scheduled job. */
    @Column(name = "evaluations_day")
    private LocalDate evaluationsDay;

    /** Evaluations charged against the pipeline's {@code cost_ceiling} on {@link #evaluationsDay}. */
    @Column(name = "evaluations")
    private int evaluations;

    public GroupStateEntity(String pipelineName, String groupKey) {
        this.pipelineName = pipelineName;
        this.groupKey = groupKey;
    }

    /** Whether the group has members newer than the version it was last computed at. */
    public boolean isDirty() {
        return computedVersion == null || groupVersion > computedVersion;
    }
}
