package com.epam.aidial.enrichment.scan.group;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ScanResponse;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.ScanPageColumnException;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.GroupStateEntity;
import com.epam.aidial.enrichment.scan.PageAccountant;
import com.epam.aidial.enrichment.scan.PageAccounting;
import com.epam.aidial.enrichment.scan.Sampler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a scanned page of a <em>group</em> pipeline into per-group marks. The group-grain counterpart of
 * {@link com.epam.aidial.enrichment.scan.ChunkBuilder}: same page, same sampling idea, different unit of
 * work.
 *
 * <p>Each row contributes its {@code group_by} value as the group key and the source's version column as
 * both the version and the activity timestamp. A group's version is the <em>maximum</em> seen for it in
 * the page, as epoch millis — so the version the runner derives and the order ADAS scanned in cannot
 * disagree.
 *
 * <p>A row also contributes ADAS's readiness verdict ({@value ScanResponse#SIGNAL_COLUMN}) when the pipeline
 * declares a {@code ready_when.signal}. It is <em>recorded, never evaluated</em>: the predicate is governed
 * by ADAS's bounded grammar and computed inside its scan query, so the runner holds no second grammar that
 * could diverge from it. Signalled is OR-ed across the page rather than taken from the newest row — a group
 * whose completion marker is followed by another member is still complete.
 *
 * <p>Sampling is applied to the <em>group key</em>, not the row: the work is keyed by group, so sampling
 * by group is what keeps the decision deterministic and monotone at the grain that matters. Sampling by
 * row would admit a group on one tick and drop it on the next.
 *
 * <p><strong>Two required columns, both read structurally.</strong> A page that does not <em>carry</em>
 * {@code group_by} or the {@code version_column} is not the projection this lane asked for, and fails the
 * tick with the cursor untouched. The version column is the sharper of the two: {@link #versionOf} already
 * treated an absent key as an error, but dead-lettered the row for it — so a page whose version column was
 * simply not in the projection dead-lettered every row of every page, tens of thousands of items that were
 * not row defects. A key that is present and holds a null or uninterpretable value stays a dead letter,
 * because that is a defect in one row's data and the group's dirtiness is genuinely unknowable for it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupMarkBuilder {

    private final Sampler sampler;
    private final RunnerMetrics runnerMetrics;

    public GroupMarkBatch build(PipelineDto pipeline, ScanResponse page) {
        Map<String, GroupStateEntity> marks = new LinkedHashMap<>();
        List<DlqItemEntity> dlqItems = new ArrayList<>();
        List<Map<String, Object>> rows = page.rows() != null ? page.rows() : List.of();
        PageAccountant accountant = new PageAccountant();

        for (Map<String, Object> row : rows) {
            requireColumn(pipeline, row, pipeline.groupBy(), "group_by");
            // Read before sampling, and before the group key is even known to be usable: a page missing the
            // version column must fail on its first row rather than on the first row that survives sampling,
            // or a sampled pipeline would refuse the page only sometimes.
            requireColumn(pipeline, row, pipeline.versionColumn(), "version");

            Object groupValue = row.get(pipeline.groupBy());
            if (groupValue == null) {
                // The column is there and this row's value is null, so the row belongs to no group. The
                // cursor still advances past it — counted, so that is visible.
                accountant.countSkip(RunnerMetrics.SkipReason.NULL_GROUP_KEY);
                continue;
            }
            String groupKey = String.valueOf(groupValue);
            if (!sampler.keep(groupKey, pipeline.sampleFraction())) {
                accountant.countSkip(RunnerMetrics.SkipReason.SAMPLING);
                continue;
            }

            Instant activity;
            try {
                activity = versionOf(row, pipeline.versionColumn());
            } catch (IllegalArgumentException e) {
                // Never a silent drop: without a version the group's dirtiness is unknowable, so the row
                // is dead-lettered and the misconfiguration surfaces instead of a group that never fires.
                // Reachable only for a column that IS in the page — a missing one failed the tick above —
                // so this is one row's bad value, which is exactly what the DLQ is for.
                accountant.countDeadLetter();
                dlqItems.add(DlqItemEntity.builder()
                        .pipelineName(pipeline.name())
                        .grainKey(groupKey)
                        .pipelineGeneration(pipeline.generation())
                        .stage(DlqStage.INPUT_MAP.dbValue())
                        .error(e.getMessage())
                        .build());
                continue;
            }

            accountant.countWork();
            long version = activity.toEpochMilli();
            marks.merge(groupKey,
                    GroupStateEntity.builder()
                            .pipelineName(pipeline.name())
                            .groupKey(groupKey)
                            .groupVersion(version)
                            .lastActivityAt(activity)
                            .signalled(signalled(row))
                            .build(),
                    GroupMarkBuilder::merge);
        }

        PageAccounting accounting = accountant.accountFor(rows.size());
        accounting.skippedRows().forEach((reason, count) ->
                runnerMetrics.recordSkippedRows(pipeline.name(), reason, count));
        return new GroupMarkBatch(new ArrayList<>(marks.values()), dlqItems, accounting);
    }

    /**
     * Fails the tick when the page does not carry a column this lane dereferences, leaving the cursor where
     * it was so the next cadence retries the same range.
     *
     * <p>{@code containsKey}, not a null check: the two say different things, and only one of them is about
     * the page rather than about a row.
     */
    private void requireColumn(PipelineDto pipeline, Map<String, Object> row, String column, String role) {
        if (row.containsKey(column)) {
            return;
        }
        runnerMetrics.recordRefusedPage(pipeline.name(), RunnerMetrics.RefusalReason.MISSING_COLUMN);
        log.error("Scan page for pipeline {} does not carry its {} column '{}'; failing the tick and holding the "
                + "cursor rather than dead-lettering every row of the page", pipeline.name(), role, column);
        throw new ScanPageColumnException(pipeline.name(), column, role);
    }

    /**
     * Keeps whichever mark carries the later version, so a page's out-of-order rows still yield max — but
     * carries the signal forward from either, because a signal is a fact about the group and not about the
     * newest member.
     */
    private static GroupStateEntity merge(GroupStateEntity existing, GroupStateEntity incoming) {
        GroupStateEntity later = incoming.getGroupVersion() > existing.getGroupVersion() ? incoming : existing;
        later.setSignalled(existing.isSignalled() || incoming.isSignalled());
        return later;
    }

    /**
     * ADAS's readiness verdict for the row. Normalized there to a JSON boolean, but a number or its string
     * form is accepted too, so a differently-serializing ADAS version degrades to reading the verdict rather
     * than to never signalling. An absent verdict — every pipeline that declares no signal — is not a signal.
     */
    private static boolean signalled(Map<String, Object> row) {
        Object value = row.get(ScanResponse.SIGNAL_COLUMN);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && ("true".equalsIgnoreCase(value.toString()) || "1".equals(value.toString()));
    }

    /**
     * Reads the source's version column from a projected row, under the physical name the pipeline names.
     * ADAS serializes it as an ISO-8601 instant, but a numeric epoch-millis value is accepted too so a
     * differently-typed catalog column does not dead-letter an entire source.
     */
    private static Instant versionOf(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            throw new IllegalArgumentException("row has no version column '" + column + "'");
        }
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue());
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "version column '" + column + "' is not an instant: " + value, e);
        }
    }
}
