package com.epam.aidial.enrichment.scan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicIdGroupTest {

    @Test
    void sameGroupAndVersionYieldTheSameId() {
        assertThat(DeterministicId.groupInstanceId("r1", 3, "chat-a", 100L))
                .isEqualTo(DeterministicId.groupInstanceId("r1", 3, "chat-a", 100L));
    }

    @Test
    void higherVersionYieldsDifferentId() {
        // This is what makes a changed group re-fire while an unchanged one is deduped.
        assertThat(DeterministicId.groupInstanceId("r1", 3, "chat-a", 101L))
                .isNotEqualTo(DeterministicId.groupInstanceId("r1", 3, "chat-a", 100L));
    }

    @Test
    void groupKeyEvaluatorVersionAndPipelineAllDiscriminate() {
        String base = DeterministicId.groupInstanceId("r1", 3, "chat-a", 100L);
        assertThat(DeterministicId.groupInstanceId("r1", 3, "chat-b", 100L)).isNotEqualTo(base);
        assertThat(DeterministicId.groupInstanceId("r1", 4, "chat-a", 100L)).isNotEqualTo(base);
        assertThat(DeterministicId.groupInstanceId("r2", 3, "chat-a", 100L)).isNotEqualTo(base);
    }

    @Test
    void idKeepsThePipelinePrefixBackpressureCountsBy() {
        assertThat(DeterministicId.groupInstanceId("r1", 3, "chat-a", 100L))
                .startsWith(DeterministicId.pipelinePrefix("r1"));
    }

    @Test
    void groupIdsDoNotCollideWithChunkIds() {
        // Both are namespaced under the same pipeline prefix, so the discriminator must be inside the id.
        assertThat(DeterministicId.groupInstanceId("r1", 3, "chat-a", 100L))
                .isNotEqualTo(DeterministicId.chunkInstanceId("r1", 3, "chat-a", 100));
    }
}
