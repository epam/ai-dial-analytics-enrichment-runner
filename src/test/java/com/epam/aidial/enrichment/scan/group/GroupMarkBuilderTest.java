package com.epam.aidial.enrichment.scan.group;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ReadyWhenDto;
import com.epam.aidial.enrichment.adas.dto.ScanResponse;
import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.ScanPageColumnException;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.persistence.GroupStateEntity;
import com.epam.aidial.enrichment.scan.Sampler;
import com.epam.aidial.enrichment.support.TestPipelines;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GroupMarkBuilderTest {

    private static final String VERSION_COLUMN = "_ingested_at";
    private static final PipelineDto PIPELINE = TestPipelines.group("g1", 1, "chat_id", "10m");

    private final RunnerMetrics runnerMetrics = mock(RunnerMetrics.class);

    private final GroupMarkBuilder builder = new GroupMarkBuilder(new Sampler(), runnerMetrics);

    @Test
    void groupVersionIsTheMaximumSeenAsEpochMillis() {
        Instant oldest = Instant.parse("2026-07-01T10:00:00Z");
        Instant newest = Instant.parse("2026-07-01T10:00:02.500Z");
        // Deliberately out of order: max, not last-seen, is what the version means.
        GroupMarkBatch batch = builder.build(PIPELINE, page(List.of(
                row("chat-a", newest),
                row("chat-a", oldest),
                row("chat-a", Instant.parse("2026-07-01T10:00:01Z")))));

        assertThat(batch.marks()).hasSize(1);
        GroupStateEntity mark = batch.marks().getFirst();
        assertThat(mark.getGroupKey()).isEqualTo("chat-a");
        assertThat(mark.getGroupVersion()).isEqualTo(newest.toEpochMilli());
        assertThat(mark.getLastActivityAt()).isEqualTo(newest);
        assertThat(batch.rowCount()).isEqualTo(3);
        assertThat(batch.dlqItems()).isEmpty();
    }

    @Test
    void distinctGroupsEachGetOneMark() {
        GroupMarkBatch batch = builder.build(PIPELINE, page(List.of(
                row("chat-a", Instant.parse("2026-07-01T10:00:00Z")),
                row("chat-b", Instant.parse("2026-07-01T10:00:01Z")))));

        assertThat(batch.marks()).extracting(GroupStateEntity::getGroupKey)
                .containsExactlyInAnyOrder("chat-a", "chat-b");
    }

    @Test
    void epochMillisVersionIsAcceptedAsWellAsAnIsoString() {
        long millis = Instant.parse("2026-07-01T10:00:00Z").toEpochMilli();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chat_id", "chat-a");
        row.put(VERSION_COLUMN, millis);

        GroupMarkBatch batch = builder.build(PIPELINE, new ScanResponse(List.of(row), "c1", false, 0L));

        assertThat(batch.marks().getFirst().getGroupVersion()).isEqualTo(millis);
        assertThat(batch.dlqItems()).isEmpty();
    }

    /**
     * A <em>null</em> group value, not an absent column: the column is there and this row's value is null,
     * so the row belongs to no group. That stays a skip — and is now counted, because the cursor advances
     * past it and the accounting has to cover it or the committer refuses the advance.
     */
    @Test
    void rowWithNullGroupKeyIsSkippedWithoutDeadLettering() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chat_id", null);
        row.put(VERSION_COLUMN, "2026-07-01T10:00:00Z");

        GroupMarkBatch batch = builder.build(PIPELINE, new ScanResponse(List.of(row), "c1", false, 0L));

        assertThat(batch.marks()).isEmpty();
        assertThat(batch.dlqItems()).isEmpty();
        assertThat(batch.rowCount()).isEqualTo(1);
        // Accounted for, so the page still commits; counted, so "skipped" is not indistinguishable from
        // "enriched nothing".
        assertThat(batch.accounting().coversEveryRow()).isTrue();
        verify(runnerMetrics).recordSkippedRows(PIPELINE.name(), RunnerMetrics.SkipReason.NULL_GROUP_KEY, 1);
    }

    /**
     * The behaviour change this capability's spec calls the sharper case. An absent version <em>key</em>
     * used to dead-letter the row, so a page whose version column was simply not in the projection
     * dead-lettered every row of every page — tens of thousands of items that are not row defects,
     * recoverable only by bulk requeue. Now it fails the tick, and the cursor holds.
     */
    @Test
    void pageWithoutTheVersionColumnFailsTheTickRatherThanFloodingTheDlq() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chat_id", "chat-a");

        assertThatThrownBy(() -> builder.build(PIPELINE, new ScanResponse(List.of(row), "c1", false, 0L)))
                .isInstanceOf(ScanPageColumnException.class)
                .hasMessageContaining(PIPELINE.name())
                .hasMessageContaining(VERSION_COLUMN);

        verify(runnerMetrics).recordRefusedPage(PIPELINE.name(), RunnerMetrics.RefusalReason.MISSING_COLUMN);
    }

    /** The same split for the group-by column: absent key is the page's defect, not the row's. */
    @Test
    void pageWithoutTheGroupByColumnFailsTheTick() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(VERSION_COLUMN, "2026-07-01T10:00:00Z");

        assertThatThrownBy(() -> builder.build(PIPELINE, new ScanResponse(List.of(row), "c1", false, 0L)))
                .isInstanceOf(ScanPageColumnException.class)
                .hasMessageContaining("chat_id");

        verify(runnerMetrics).recordRefusedPage(PIPELINE.name(), RunnerMetrics.RefusalReason.MISSING_COLUMN);
    }

    @Test
    void unparseableVersionDeadLettersOnlyItsRow() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("chat_id", "chat-bad");
        bad.put(VERSION_COLUMN, "not-a-timestamp");

        GroupMarkBatch batch = builder.build(PIPELINE, new ScanResponse(
                List.of(bad, row("chat-a", Instant.parse("2026-07-01T10:00:00Z"))), "c1", false, 0L));

        assertThat(batch.marks()).extracting(GroupStateEntity::getGroupKey).containsExactly("chat-a");
        assertThat(batch.dlqItems()).hasSize(1);
        assertThat(batch.dlqItems().getFirst().getStage()).isEqualTo(DlqStage.INPUT_MAP.dbValue());
        assertThat(batch.dlqItems().getFirst().getGrainKey()).isEqualTo("chat-bad");
    }

    /**
     * A version column that IS in the page but holds null is one row's data defect, and keeps the outcome
     * it always had: the group's dirtiness is genuinely unknowable for it, so it must surface rather than
     * become a group that never fires.
     */
    @Test
    void nullVersionValueInPresentColumnIsStillDeadLettered() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chat_id", "chat-a");
        row.put(VERSION_COLUMN, null);

        GroupMarkBatch batch = builder.build(PIPELINE, new ScanResponse(List.of(row), "c1", false, 0L));

        assertThat(batch.marks()).isEmpty();
        assertThat(batch.dlqItems()).hasSize(1);
        assertThat(batch.dlqItems().getFirst().getError()).contains(VERSION_COLUMN);
        assertThat(batch.accounting().coversEveryRow()).isTrue();
    }

    @Test
    void versionColumnNameComesFromThePipelineNotConfiguration() {
        // A second source may order its rows by a differently named column; the pipeline says which.
        PipelineDto otherSource = TestPipelines.builder("g-other")
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .grainKey("chat_id")
                .readyWhen(new ReadyWhenDto(null, "10m", null, null))
                .versionColumn("ingest_ts")
                .build();
        Instant version = Instant.parse("2026-07-01T10:00:00Z");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chat_id", "chat-a");
        row.put("ingest_ts", version.toString());

        GroupMarkBatch batch = builder.build(otherSource, new ScanResponse(List.of(row), "c1", false, 0L));

        assertThat(batch.dlqItems()).isEmpty();
        assertThat(batch.marks().getFirst().getGroupVersion()).isEqualTo(version.toEpochMilli());
    }

    @Test
    void adasVerdictMarksTheGroupSignalled() {
        GroupMarkBatch batch = builder.build(PIPELINE, page(List.of(
                signalRow("chat-a", Instant.parse("2026-07-01T10:00:00Z"), false),
                signalRow("chat-b", Instant.parse("2026-07-01T10:00:01Z"), true))));

        assertThat(batch.marks())
                .extracting(GroupStateEntity::getGroupKey, GroupStateEntity::isSignalled)
                .containsExactlyInAnyOrder(tuple("chat-a", false), tuple("chat-b", true));
    }

    @Test
    void signalSurvivesLaterUnsignalledMemberInTheSamePage() {
        // The signal is a fact about the group, not about its newest row: a member arriving after the
        // completion marker must not un-signal it, or the group would wait out its idle window after all.
        GroupMarkBatch batch = builder.build(PIPELINE, page(List.of(
                signalRow("chat-a", Instant.parse("2026-07-01T10:00:00Z"), true),
                signalRow("chat-a", Instant.parse("2026-07-01T10:00:05Z"), false))));

        assertThat(batch.marks()).hasSize(1);
        assertThat(batch.marks().getFirst().isSignalled()).isTrue();
        assertThat(batch.marks().getFirst().getGroupVersion())
                .isEqualTo(Instant.parse("2026-07-01T10:00:05Z").toEpochMilli());
    }

    @Test
    void verdictIsReadFromEitherSerializationAndAbsenceIsNoSignal() {
        // ADAS normalizes the verdict to a JSON boolean; a number (or its string form) is accepted so a
        // differently-serializing version degrades to reading the verdict rather than never signalling.
        Map<String, Object> numeric = row("chat-num", Instant.parse("2026-07-01T10:00:00Z"));
        numeric.put(ScanResponse.SIGNAL_COLUMN, 1);
        Map<String, Object> text = row("chat-text", Instant.parse("2026-07-01T10:00:01Z"));
        text.put(ScanResponse.SIGNAL_COLUMN, "true");
        Map<String, Object> absent = row("chat-absent", Instant.parse("2026-07-01T10:00:02Z"));

        GroupMarkBatch batch = builder.build(PIPELINE, page(List.of(numeric, text, absent)));

        assertThat(batch.marks())
                .extracting(GroupStateEntity::getGroupKey, GroupStateEntity::isSignalled)
                .containsExactlyInAnyOrder(tuple("chat-num", true), tuple("chat-text", true),
                        tuple("chat-absent", false));
    }

    @Test
    void samplingKeepsOrDropsWholeGroup() {
        // Two rows of one group with different grain keys: sampling by group key must treat them alike.
        PipelineDto sampled = TestPipelines.builder("g-sampled")
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .readyWhen(new ReadyWhenDto(null, "10m", null, null))
                .sampleFraction(0.0)
                .build();

        GroupMarkBatch dropped = builder.build(sampled, page(List.of(
                row("chat-a", Instant.parse("2026-07-01T10:00:00Z")),
                row("chat-a", Instant.parse("2026-07-01T10:00:01Z")))));

        assertThat(dropped.marks()).isEmpty();
        assertThat(dropped.rowCount()).isEqualTo(2);
    }

    @Test
    void emptyPageYieldsNothing() {
        GroupMarkBatch batch = builder.build(PIPELINE, new ScanResponse(null, "c1", false, null));

        assertThat(batch.marks()).isEmpty();
        assertThat(batch.dlqItems()).isEmpty();
        assertThat(batch.rowCount()).isZero();
    }

    private static ScanResponse page(List<Map<String, Object>> rows) {
        return new ScanResponse(rows, "c1", false, 0L);
    }

    private static Map<String, Object> row(String groupKey, Instant version) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chat_id", groupKey);
        row.put(VERSION_COLUMN, version.toString());
        return row;
    }

    /** A row of a pipeline declaring {@code ready_when.signal}: ADAS's verdict rides along with it. */
    private static Map<String, Object> signalRow(String groupKey, Instant version, boolean signalled) {
        Map<String, Object> row = row(groupKey, version);
        row.put(ScanResponse.SIGNAL_COLUMN, signalled);
        return row;
    }
}
