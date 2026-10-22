package com.epam.aidial.enrichment.observability;

import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M7 ops surface: pipeline-cache introspection, Prometheus exposure of the runner's own metrics, and the
 * readiness gate turning UP after a successful pipeline sync. PostgreSQL runs in Testcontainers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PipelineCache pipelineCache;

    @Autowired
    private ReadinessState readinessState;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
    }

    @Test
    void pipelineCacheEndpointListsCachedPipelines() throws Exception {
        pipelineCache.replaceAll(Map.of("r1", TestPipelines.schedule("r1", 7, TestPipelines.EVERY_30_SECONDS)));

        mockMvc.perform(get("/v1/pipelines/cache"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[0].name").value("r1"))
                // One identifier: the cache entry no longer carries a separate id.
                .andExpect(jsonPath("$.pipelines[0].id").doesNotExist())
                .andExpect(jsonPath("$.pipelines[0].trigger_kind").value("schedule"))
                .andExpect(jsonPath("$.pipelines[0].generation").value(7));
    }

    @Test
    void prometheusExposesRunnerMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("runner_pipelines_cached")))
                // The auto-pause gauge: a breaker trip is otherwise only a log line, while the condition it
                // announces lasts until an operator resumes the pipeline.
                .andExpect(content().string(containsString("runner_pipelines_paused")));
    }

    @Test
    void readinessIsUpAfterPipelineSyncSucceeds() throws Exception {
        readinessState.markPipelineSyncSucceeded();

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * The aggregate endpoint reports the union of every contributor, so it only reads UP once the pipeline-sync
     * gate is satisfied too — the reason documentation must not present it as a fresh-instance smoke test.
     */
    @Test
    void aggregateHealthIsUpOnceEveryDependencyIsSatisfied() throws Exception {
        readinessState.markPipelineSyncSucceeded();

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.pipelineSync.status").value("UP"));
    }

    /**
     * The readiness group's {@code include} list names the health contributor by bean name, so renaming the
     * bean without the list — or the list without the bean — drops a contributor rather than failing. The
     * failure mode is the exact one the group exists to prevent: a pod that reports ready before its first
     * successful sync. Asserting the membership <b>exactly</b> is what makes that loud.
     */
    @Test
    void readinessReportsExactlyItsThreeContributors() throws Exception {
        readinessState.markPipelineSyncSucceeded();

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.readinessState").exists())
                .andExpect(jsonPath("$.components.db").exists())
                .andExpect(jsonPath("$.components.pipelineSync").exists())
                .andExpect(jsonPath("$.components.ruleSync").doesNotExist())
                .andExpect(jsonPath("$.components.length()").value(3));
    }

    /**
     * The pipeline-sync gate is a one-way latch, which is what stops a later sync failure or an upstream ADAS
     * restart from withdrawing a healthy instance — or restarting it, if a probe were pointed here.
     */
    @Test
    void pipelineSyncGateNeverReportsDownOnceItHasSucceeded() throws Exception {
        readinessState.markPipelineSyncSucceeded();

        // Nothing in the runner's surface can un-succeed the gate: no reset path exists, and re-marking is
        // idempotent. Re-reading the probe after further activity must still report UP.
        readinessState.markPipelineSyncSucceeded();

        assertThat(readinessState.isPipelineSyncSucceeded()).isTrue();
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
