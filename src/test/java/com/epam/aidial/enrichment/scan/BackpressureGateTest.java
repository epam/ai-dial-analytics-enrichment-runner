package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.configuration.ScanProperties;
import com.epam.aidial.enrichment.support.CapturingAppender;
import com.epam.aidial.enrichment.worker.WorkTasks;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The gate both enqueue paths ask, so what it counts is pinned once here rather than in each of them.
 */
class BackpressureGateTest {

    private static final String PIPELINE_NAME = "chat-topic-live";

    private final InflightCounter inflightCounter = mock(InflightCounter.class);
    private final ScanProperties properties = new ScanProperties(1000, 10_000, 25, 1000, 5);
    private final BackpressureGate gate = new BackpressureGate(inflightCounter, properties);

    /**
     * A pipeline whose queued work is entirely write items still has to be throttled: a count naming
     * {@code enrich-chunk} alone reads a backed-up write queue as an idle pipeline.
     */
    @Test
    void countsEveryPipelineScopedTaskUnderThePipelinePrefix() {
        when(inflightCounter.countByTasksAndInstancePrefix(
                WorkTasks.PIPELINE_SCOPED, DeterministicId.pipelinePrefix(PIPELINE_NAME)))
                .thenReturn(properties.maxInflight() + 1L);

        assertThat(gate.isBackpressured(PIPELINE_NAME)).isTrue();
    }

    /** At the threshold exactly the pipeline still runs; the gate trips strictly above it. */
    @Test
    void theThresholdItselfIsNotBackpressure() {
        when(inflightCounter.countByTasksAndInstancePrefix(
                WorkTasks.PIPELINE_SCOPED, DeterministicId.pipelinePrefix(PIPELINE_NAME)))
                .thenReturn((long) properties.maxInflight());

        assertThat(gate.isBackpressured(PIPELINE_NAME)).isFalse();
    }

    /** On and off are logged once each, with the depth against the threshold; the fires in between are silent. */
    @Test
    void backpressureLogsOnlyItsTransitions() {
        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            stubInflight(properties.maxInflight() + 5L);
            gate.isBackpressured(PIPELINE_NAME);
            gate.isBackpressured(PIPELINE_NAME);
            stubInflight(3L);
            gate.isBackpressured(PIPELINE_NAME);
            gate.isBackpressured(PIPELINE_NAME);

            assertThat(appender.messages().stream().filter(m -> m != null && m.startsWith("backpressure")).toList())
                    .containsExactly(
                            "backpressure on: pipeline=" + PIPELINE_NAME + " inflight=1005 > scan.max-inflight=1000",
                            "backpressure off: pipeline=" + PIPELINE_NAME + " inflight=3 <= scan.max-inflight=1000");
        } finally {
            appender.detach(Level.INFO);
        }
    }

    private void stubInflight(long inflight) {
        when(inflightCounter.countByTasksAndInstancePrefix(
                WorkTasks.PIPELINE_SCOPED, DeterministicId.pipelinePrefix(PIPELINE_NAME))).thenReturn(inflight);
    }
}
