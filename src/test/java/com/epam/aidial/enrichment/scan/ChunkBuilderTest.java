package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ScanResponse;
import com.epam.aidial.enrichment.expression.JsonataEvaluator;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.worker.ChunkRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChunkBuilderTest {

    private final RunnerMetrics runnerMetrics = mock(RunnerMetrics.class);

    private final ChunkBuilder chunkBuilder =
            new ChunkBuilder(new Sampler(), new InputMapper(new JsonataEvaluator()), runnerMetrics);

    @Test
    @Timeout(5)
    void clampsNonPositiveChunkSizeInsteadOfLoopingForever() {
        PipelineDto pipeline = TestPipelines.builder("r1").build(); // grainKey = event_id, no sample fraction, no inputs
        ScanResponse page = new ScanResponse(
                List.of(Map.of("event_id", "a"), Map.of("event_id", "b"), Map.of("event_id", "c")),
                "c1", false, 0L);

        // Without the Math.max(1, ...) guard this loops forever appending empty chunks until OOM.
        ChunkBatch batch = chunkBuilder.build(pipeline, 0, page);

        assertThat(batch.chunks()).hasSize(3); // clamped to chunk size 1: one chunk per kept row
        assertThat(batch.chunks()).allSatisfy(chunk -> assertThat(chunk.getRows()).hasSize(1));
        assertThat(batch.rowCount()).isEqualTo(3);
        assertThat(batch.dlqItems()).isEmpty();
    }

    @Test
    void chunksKeptRowsBySize() {
        PipelineDto pipeline = TestPipelines.builder("r1").build();
        ScanResponse page = new ScanResponse(
                List.of(Map.of("event_id", "a"), Map.of("event_id", "b"), Map.of("event_id", "c")),
                "c1", false, 0L);

        ChunkBatch batch = chunkBuilder.build(pipeline, 2, page);

        assertThat(batch.chunks()).hasSize(2); // 2 + 1
        assertThat(batch.chunks().get(0).getRows()).hasSize(2);
        assertThat(batch.chunks().get(1).getRows()).hasSize(1);
    }

    @Test
    void carriesThePartitionValueOntoTheChunkRowUnchanged() {
        PipelineDto pipeline = TestPipelines.builder("r1").build();
        ScanResponse page = new ScanResponse(
                List.of(Map.of("event_id", "a", "__partition_v", "2026-10-15T10:00:00.000Z")), "c1", false, 0L);

        ChunkBatch batch = chunkBuilder.build(pipeline, 10, page);

        assertThat(batch.chunks()).singleElement().satisfies(chunk -> assertThat(chunk.getRows()).singleElement()
                .satisfies(row -> assertThat(row.getPartitionValue()).isEqualTo("2026-10-15T10:00:00.000Z")));
    }

    @Test
    void leavesThePartitionValueNullWhenTheScanRowCarriesNone() {
        PipelineDto pipeline = TestPipelines.builder("r1").build();
        ScanResponse page = new ScanResponse(List.of(Map.of("event_id", "a")), "c1", false, 0L);

        ChunkBatch batch = chunkBuilder.build(pipeline, 10, page);

        assertThat(batch.chunks()).singleElement().satisfies(chunk -> assertThat(chunk.getRows()).singleElement()
                .extracting(ChunkRow::getPartitionValue).isNull());
    }
}
