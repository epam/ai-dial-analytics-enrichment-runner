package com.epam.aidial.enrichment.worker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The work-item payload for one enrich-chunk: the pipeline it belongs to, the pipeline {@code generation}
 * it was built against (for the worker's stale-declaration guard), and the chunk's rows. A mutable POJO
 * so db-scheduler's Jackson serializer can round-trip it in the queue.
 *
 * <p>{@code pipelineGeneration} replaced an {@code evaluatorVersion} {@code int} at the evaluator fold, and
 * the member name is what lets an archived pre-fold payload be told apart from a current one on requeue —
 * see {@code DlqRequeueService}. It is a {@code long} because a generation is a 64-bit counter in ADAS.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkPayload {

    private String pipelineName;
    private long pipelineGeneration;
    private List<ChunkRow> rows;
}
