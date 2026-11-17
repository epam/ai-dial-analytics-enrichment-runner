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

/** {@code runner.group.input.budget.cut}: one series per pipeline, since budgets differ per pipeline, and evicted with it. */
class InputBudgetCutMetricTest {

    private static final String METER = "runner.group.input.budget.cut";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RunnerMetrics metrics = new RunnerMetrics(registry, mock(InflightCounter.class), new PipelineCache(),
            mock(DlqItemRepository.class), mock(GroupStateRepository.class),
            new PipelinePauseRegistry(Clock.systemUTC()), new AdasCompatibilityState());

    @Test
    void cutsAreAttributedToTheirPipeline() {
        metrics.recordInputBudgetCut("g1");
        metrics.recordInputBudgetCut("g1");
        metrics.recordInputBudgetCut("g2");

        assertThat(count("g1")).isEqualTo(2);
        assertThat(count("g2")).isEqualTo(1);
    }

    @Test
    void removedPipelinesSeriesIsEvicted() {
        metrics.recordInputBudgetCut("g1");

        metrics.evictPipeline("g1");

        assertThat(registry.find(METER).tag("pipeline", "g1").counter()).isNull();
    }

    private double count(String pipeline) {
        Counter counter = registry.find(METER).tag("pipeline", pipeline).counter();
        return counter == null ? 0 : counter.count();
    }
}
