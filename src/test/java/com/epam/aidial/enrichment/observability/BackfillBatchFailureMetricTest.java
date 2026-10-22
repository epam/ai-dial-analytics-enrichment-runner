package com.epam.aidial.enrichment.observability;

import com.epam.aidial.enrichment.adas.AdasCompatibilityState;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.DlqItemRepository;
import com.epam.aidial.enrichment.persistence.GroupStateRepository;
import com.epam.aidial.enrichment.scan.InflightCounter;
import com.epam.aidial.enrichment.sync.PipelineCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** {@code runner.backfill.batch.failures}: one series per pipeline, and evicted with it. */
class BackfillBatchFailureMetricTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RunnerMetrics metrics = new RunnerMetrics(registry, mock(InflightCounter.class), new PipelineCache(),
            mock(DlqItemRepository.class), mock(GroupStateRepository.class),
            new PipelinePauseRegistry(Clock.systemUTC()), new AdasCompatibilityState());

    @Test
    void failuresAreCountedPerPipeline() {
        metrics.incrementBackfillBatchFailures("p1");
        metrics.incrementBackfillBatchFailures("p1");
        metrics.incrementBackfillBatchFailures("p2");

        assertThat(count("p1")).isEqualTo(2);
        assertThat(count("p2")).isEqualTo(1);
    }

    @Test
    void removedPipelinesSeriesIsEvicted() {
        metrics.incrementBackfillBatchFailures("p1");

        metrics.evictPipeline("p1");

        assertThat(registry.find("runner.backfill.batch.failures").tag("pipeline", "p1").counter()).isNull();
    }

    private double count(String pipeline) {
        Counter counter = registry.find("runner.backfill.batch.failures").tag("pipeline", pipeline).counter();
        return counter == null ? 0 : counter.count();
    }
}
