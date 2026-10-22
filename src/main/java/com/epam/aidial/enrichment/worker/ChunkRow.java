package com.epam.aidial.enrichment.worker;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * One row inside an enrich-chunk work item: the grain key plus the already-extracted transform input
 * variables. A mutable POJO so db-scheduler's Jackson serializer can round-trip it in the queue.
 *
 * <p>{@code partitionValue} is the scanned row's {@value #PARTITION_ECHO_MEMBER} member, kept as the string
 * the scan returned. ADAS returns it only for a row-trigger pipeline whose target enrichment is aligned
 * (partitioned like its source), and requires it back, unchanged, on every write-back row of such a pipeline.
 * It is null for every other pipeline, and for a work item queued before contract version 4 — which, being
 * absent from that JSON, deserializes as null. A null value is left out of the serialized payload, so a row
 * of an unaligned pipeline is stored exactly as before.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkRow {

    /** The scan-row and write-row member carrying the source row's partition value (ADAS {@code PartitionEcho}). */
    public static final String PARTITION_ECHO_MEMBER = "__partition_v";

    private String grainKey;
    private Map<String, Object> inputs;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String partitionValue;

    /** A row of a pipeline whose target is not aligned: no partition value to echo. */
    public ChunkRow(String grainKey, Map<String, Object> inputs) {
        this(grainKey, inputs, null);
    }
}
