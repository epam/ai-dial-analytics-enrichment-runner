package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ScanResponse;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.InputMappingException;
import com.epam.aidial.enrichment.exception.ScanPageColumnException;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.worker.ChunkPayload;
import com.epam.aidial.enrichment.worker.ChunkRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a scanned page into chunk work items: deterministic sampling, per-row input mapping (with
 * input-map failures routed to DLQ items), and chunking of the kept rows. Shared by the live scan and
 * the backfill run so both apply identical sampling/mapping/chunking.
 *
 * <p>Every row it reads reaches exactly one outcome — chunked, dead-lettered, or skipped for a declared
 * reason — and the tally travels back on {@link ChunkBatch}, where the committer refuses to advance the
 * cursor unless it covers the page. That is what this method owes the cursor: the counts are not
 * instrumentation, they are the evidence the advance is checked against.
 *
 * <p>The grain column is read by {@code containsKey} first, because absence and null are different facts:
 * a page that does not carry the column at all is not the projection this lane asked for and fails the tick
 * (see {@link ScanPageColumnException}), while a column present and null is one row's data and is skipped
 * and counted.
 *
 * <p>A row's {@code __partition_v}, when ADAS returned one, is carried onto its {@link ChunkRow} unchanged so the
 * write-back can echo it (see {@link ChunkRow}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkBuilder {

    private final Sampler sampler;
    private final InputMapper inputMapper;
    private final RunnerMetrics runnerMetrics;

    public ChunkBatch build(PipelineDto pipeline, int chunkSize, ScanResponse page) {
        int safeChunkSize = Math.max(1, chunkSize); // guard: a pipeline with rows_per_call <= 0 must not loop forever
        List<ChunkRow> kept = new ArrayList<>();
        List<DlqItemEntity> dlqItems = new ArrayList<>();
        List<Map<String, Object>> rows = page.rows() != null ? page.rows() : List.of();
        PageAccountant accountant = new PageAccountant();

        for (Map<String, Object> row : rows) {
            if (!row.containsKey(pipeline.grainKey())) {
                // Loud and cursor-preserving. Before this test existed the same condition read as a null
                // grain value on every row, so the page produced no chunk and no dead letter and the cursor
                // advanced over it anyway — with scan lag falling to zero as it caught up, which is what made
                // it look like a pipeline that had finished rather than one that had lost its backlog.
                runnerMetrics.recordRefusedPage(pipeline.name(), RunnerMetrics.RefusalReason.MISSING_COLUMN);
                log.error("Scan page for pipeline {} does not carry its grain column '{}'; failing the tick and "
                        + "holding the cursor", pipeline.name(), pipeline.grainKey());
                throw new ScanPageColumnException(pipeline.name(), pipeline.grainKey(), "grain");
            }
            Object grainValue = row.get(pipeline.grainKey());
            if (grainValue == null) {
                // Data, not skew: the column is there and this row's value is null, so there is no key to
                // write a row at. Counted, because the cursor advances past it and nothing else would say so.
                accountant.countSkip(RunnerMetrics.SkipReason.NULL_GRAIN_KEY);
                continue;
            }
            String grainKey = String.valueOf(grainValue);
            if (!sampler.keep(grainKey, pipeline.sampleFraction())) {
                accountant.countSkip(RunnerMetrics.SkipReason.SAMPLING);
                continue;
            }
            try {
                kept.add(new ChunkRow(grainKey, inputMapper.map(pipeline, row), partitionValue(row)));
                accountant.countWork();
            } catch (InputMappingException e) {
                accountant.countDeadLetter();
                dlqItems.add(DlqItemEntity.builder()
                        .pipelineName(pipeline.name())
                        .grainKey(grainKey)
                        .pipelineGeneration(pipeline.generation())
                        .stage(DlqStage.INPUT_MAP.dbValue())
                        .error(e.getMessage())
                        .build());
            }
        }

        List<ChunkPayload> chunks = new ArrayList<>();
        for (int i = 0; i < kept.size(); i += safeChunkSize) {
            List<ChunkRow> slice = kept.subList(i, Math.min(i + safeChunkSize, kept.size()));
            chunks.add(new ChunkPayload(pipeline.name(), pipeline.generation(), new ArrayList<>(slice)));
        }
        if (!dlqItems.isEmpty()) {
            // The per-row catch above is silent, and a page whose every kept row fails to map produces no chunk and
            // so no worker line either. Counts and the stage only: the mapping error can quote the row.
            log.warn("Scan page for pipeline {} dead-lettered {} of {} row(s) at stage(s) {}", pipeline.name(),
                    dlqItems.size(), rows.size(), DlqStage.INPUT_MAP.dbValue());
        }
        PageAccounting accounting = accountant.accountFor(rows.size());
        publishSkips(pipeline.name(), accounting);
        return new ChunkBatch(chunks, dlqItems, accounting);
    }

    /**
     * The row's {@link ChunkRow#PARTITION_ECHO_MEMBER}, as the string the scan returned, or null when the row does
     * not carry it. Picked up here because this is the last place the full scanned row exists: ADAS returns the
     * member only for a pipeline whose target is aligned and requires it back on the write-back, so it travels
     * with the row through the chunk to {@code WorkerService}. Absence is not an error: an unaligned target's
     * scan never carries it, and ADAS, not the runner, decides which targets are aligned.
     */
    private static String partitionValue(Map<String, Object> row) {
        Object value = row.get(ChunkRow.PARTITION_ECHO_MEMBER);
        return value == null ? null : value.toString();
    }

    /**
     * Publishes the page's skips per reason. Recorded here rather than by the committer so a page whose
     * advance is then refused still says what it did with the rows it did account for.
     */
    private void publishSkips(String pipelineName, PageAccounting accounting) {
        accounting.skippedRows().forEach((reason, count) ->
                runnerMetrics.recordSkippedRows(pipelineName, reason, count));
    }
}
