package com.epam.aidial.enrichment;

import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1 smoke test: the full application context boots against a real PostgreSQL, Flyway migrates the
 * runner-owned schema (db-scheduler + runner state), and Actuator health reports UP.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AnalyticsEnrichmentRunnerApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
    }

    @Test
    void contextLoadsAndLivenessIsUp() throws Exception {
        // Liveness is process health; readiness is separately gated on the first successful pipeline sync.
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void flywayProvisionedTheRunnerSchema() {
        assertThat(tableExists("scheduled_tasks")).isTrue();
        assertThat(tableExists("pipeline_cursor")).isTrue();
        assertThat(tableExists("run_history")).isTrue();
        assertThat(tableExists("dlq_item")).isTrue();
        assertThat(tableExists("spend_counter")).isTrue();
    }

    private boolean tableExists(String table) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = ?)",
                Boolean.class, table);
        return Boolean.TRUE.equals(exists);
    }
}
