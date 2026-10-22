package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.worker.group.GroupPayload;
import com.epam.aidial.enrichment.worker.upsert.UpsertPayload;
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hazard every lane's pre-fold arm exists for, pinned at its source: a work item enqueued by the image
 * that predates ADAS's evaluator fold does <strong>not</strong> fail to deserialize under the new build.
 * db-scheduler's {@link JacksonSerializer} disables {@code FAIL_ON_UNKNOWN_PROPERTIES}, so the retired
 * {@code evaluatorVersion} member is ignored and the new {@code pipelineGeneration} is left at its default
 * of 0 — which is why an explicit arm is needed rather than a parse failure to lean on.
 *
 * <p>Asserted through {@code JacksonSerializer} itself rather than through this repository's own
 * {@code JsonMapper}: the queue is what deserializes these payloads at runtime, and a mapper configured
 * differently here would prove nothing about it.
 */
class PreFoldWorkItemTest {

    private static final JacksonSerializer QUEUE_SERIALIZER = new JacksonSerializer();

    private static <T> T fromQueue(String json, Class<T> type) {
        return QUEUE_SERIALIZER.deserialize(type, json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void preFoldChunkPayloadDeserializesSilentlyAtGenerationZero() {
        ChunkPayload payload = fromQueue("""
                {"pipelineName":"chat-topic-live","evaluatorVersion":4,
                 "rows":[{"grainKey":"e0","inputs":{"prompt":"hi"}}]}""", ChunkPayload.class);

        // Not an exception, not a null — a usable object carrying a generation ADAS never served. This is
        // the line that made the silent-drop bug possible, so it is the line worth pinning.
        assertThat(payload.getPipelineGeneration()).isZero();
        assertThat(PreFoldWorkItem.isPreFold(payload.getPipelineGeneration())).isTrue();
        assertThat(payload.getRows()).hasSize(1);
    }

    @Test
    void preFoldGroupPayloadDeserializesSilentlyAtGenerationZero() {
        GroupPayload payload = fromQueue(
                "{\"pipelineName\":\"chat-topic-live\",\"evaluatorVersion\":4,"
                        + "\"groupKey\":\"chat-42\",\"groupVersion\":1756000000000}", GroupPayload.class);

        assertThat(payload.getPipelineGeneration()).isZero();
        assertThat(payload.getGroupKey()).isEqualTo("chat-42");
    }

    @Test
    void preFoldUpsertPayloadDeserializesSilentlyAtGenerationZero() {
        UpsertPayload payload = fromQueue("""
                {"pipelineName":"chat-topic-live","evaluatorVersion":4,
                 "rows":[{"event_id":"e0","topic":"billing","evaluator_version":4}]}""", UpsertPayload.class);

        assertThat(payload.getPipelineGeneration()).isZero();
        // The rows survive, which is why this lane adopts rather than drops: they are the paid-for result.
        // They also carry the retired provenance spelling ADAS now refuses with 422, which is why the write
        // lane strips it — see UpsertServiceTest.
        assertThat(payload.getRows()).singleElement()
                .satisfies(row -> assertThat(row).containsKey(ProvenanceColumns.RETIRED_EVALUATOR_VERSION));
    }

    /** A generation the feed actually serves is never mistaken for a pre-fold one. */
    @Test
    void currentGenerationIsNotPreFold() {
        assertThat(PreFoldWorkItem.isPreFold(1L)).isFalse();
        assertThat(PreFoldWorkItem.isPreFold(17L)).isFalse();
    }

    /** The lane tag is the metric's only dimension, so its spelling is part of the contract. */
    @Test
    void laneTagsAreTheLowercaseLaneNames() {
        assertThat(PreFoldWorkItem.Lane.CHUNK.tag()).isEqualTo("chunk");
        assertThat(PreFoldWorkItem.Lane.GROUP.tag()).isEqualTo("group");
        assertThat(PreFoldWorkItem.Lane.UPSERT.tag()).isEqualTo("upsert");
    }
}
