package com.epam.aidial.enrichment.integration;

import com.epam.aidial.enrichment.web.security.JwtTestTokens;
import com.epam.aidial.enrichment.web.security.TestSecurityConfig;
import com.epam.aidial.enrichment.web.security.apikey.CoreApiKeyIntrospector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The operational surface stays reachable without a credential while authentication is enforced.
 *
 * <p>This is the test that protects the deployment's probe wiring: gating a liveness probe on the
 * identity provider would make an IdP outage indistinguishable from a dead process, which Kubernetes
 * resolves by restarting the pod in a loop. Both probe groups are asserted separately from the aggregate
 * endpoint, since a pattern covering only {@code /actuator/health} would silently close the groups.
 *
 * <p>Note the distinction being tested is <em>reachability</em>, not health. The aggregate endpoint and
 * the readiness group report DOWN (503) here because no pipeline sync has run in this context; what matters
 * is that they answer with their status rather than {@code 401}.
 *
 * <p>What is <em>not</em> public is asserted alongside: the API description needs {@code FULL_ADMIN} unless
 * configured otherwise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class PublicEndpointsAuthIntegrationTest {

    private static final String FULL_ADMIN_TOKEN = JwtTestTokens.tokenWithRoles("admin");

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
        TestSecurityConfig.registerOidcProvider(registry);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/actuator/health",
        "/actuator/health/liveness",
        "/actuator/health/readiness",
        "/actuator/prometheus",
        "/actuator/info",
    })
    void operationalEndpointsAreNeverRefusedForWantOfCredential(String path) throws Exception {
        int status = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();

        assertThat(status)
                .as("%s must not be gated on authentication", path)
                .isNotIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }

    @Test
    void livenessProbeIsUpWithoutCredential() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }

    @Test
    void metricsAreScrapeableWithoutCredential() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    /**
     * The aggregate endpoint includes the {@code db} and {@code pipelineSync} contributors, so it reports DOWN
     * until the first pipeline sync succeeds — unlike the liveness group, which is process state only. Asserted
     * because the documented smoke test and probe guidance depend on the difference: pointing a liveness
     * probe at the aggregate would restart the container over a dependency that has not warmed up yet.
     */
    @Test
    void aggregateHealthIsDownBeforeTheFirstPipelineSync() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.pipelineSync.status").value("DOWN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html"})
    void apiDocsRequireCredentialByDefault(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    /** Authenticating is not enough: the API description needs {@code FULL_ADMIN}, which a read-only role lacks. */
    @Test
    void apiDocsAreForbiddenToTokenWithoutFullAdmin() throws Exception {
        mockMvc.perform(get("/v3/api-docs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.tokenWithRoles("viewer")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    /**
     * The OpenAPI description carries the declared security schemes — which is what puts the "Authorize"
     * button in the Swagger UI. Asserted here rather than in {@code OpenApiConfigurationTest} because only a
     * running springdoc proves the bean reaches the served document; the shape of the schemes is pinned there.
     */
    @Test
    void apiDocsCarryTheDeclaredSchemes() throws Exception {
        mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.ApiKeyAuth.name")
                        .value(CoreApiKeyIntrospector.API_KEY_HEADER))
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.security.length()").value(2));
    }

    /**
     * A path that is neither functional nor explicitly public is denied by the {@code anyRequest} pipeline,
     * so a future endpoint added outside {@code /v1} is closed until someone decides otherwise.
     */
    @Test
    void unlistedPathsAreDeniedByDefault() throws Exception {
        int status = mockMvc.perform(get("/actuator/env")).andReturn().getResponse().getStatus();

        assertThat(status).isIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }
}
