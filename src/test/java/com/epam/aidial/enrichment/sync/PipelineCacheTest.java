package com.epam.aidial.enrichment.sync;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.exception.PipelineCacheColdException;
import com.epam.aidial.enrichment.support.TestPipelines;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineCacheTest {

    @Test
    void replaceAllAddsUpdatesAndDropsPipelines() {
        PipelineCache cache = new PipelineCache();

        cache.replaceAll(Map.of(
                "a", TestPipelines.schedule("a", 1, TestPipelines.EVERY_30_SECONDS),
                "b", TestPipelines.schedule("b", 1, TestPipelines.EVERY_30_SECONDS)));
        assertThat(cache.size()).isEqualTo(2);

        // "a" updated to a new generation, "b" absent -> dropped, no pipeline "c" yet -> added.
        cache.replaceAll(Map.of(
                "a", TestPipelines.schedule("a", 2, TestPipelines.EVERY_30_SECONDS),
                "c", TestPipelines.schedule("c", 1, TestPipelines.EVERY_30_SECONDS)));

        assertThat(cache.size()).isEqualTo(2);
        PipelineDto a = cache.get("a");
        assertThat(a).isNotNull();
        assertThat(a.generation()).isEqualTo(2);
        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("c")).isNotNull();
    }

    /**
     * "Never synced" and "synced, and this pipeline is not in it" are different facts, and only the second
     * is an answer. A fresh process reads an empty cache while its first registry poll is still pending —
     * the job scheduler starts with the context, the poll is a scheduled task behind it — and a work item
     * that becomes due there used to read that emptiness as "disabled or deleted".
     */
    @Test
    void cacheThatHasNeverSyncedRefusesToAnswer() {
        PipelineCache cache = new PipelineCache();

        assertThat(cache.isPopulated()).isFalse();
        assertThatThrownBy(() -> cache.requirePopulated("chunk c0"))
                .isInstanceOf(PipelineCacheColdException.class)
                .hasMessageContaining("chunk c0");
    }

    @Test
    void reconcileToEmptyCountsAsPopulated() {
        // An empty registry is a real answer about it. Withholding the flag for that case would hold every
        // lane forever against a legitimately empty feed.
        PipelineCache cache = new PipelineCache();

        cache.replaceAll(Map.of());

        assertThat(cache.isPopulated()).isTrue();
        assertThatCode(() -> cache.requirePopulated("chunk c0")).doesNotThrowAnyException();
        assertThat(cache.get("a")).isNull();
    }
}
