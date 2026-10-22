package com.epam.aidial.enrichment.integration;

import com.epam.aidial.enrichment.web.security.JwtTestTokens;
import com.epam.aidial.enrichment.web.security.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bearer-JWT authentication and the {@code FULL_ADMIN} authorization pipeline on every functional endpoint,
 * with the {@code ErrorView} shape of the 401 and 403 bodies. PostgreSQL runs in Testcontainers; the
 * JWKS-backed decoder is replaced by an HMAC one via {@link TestSecurityConfig}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class JwtAuthIntegrationTest {

    private static final String FULL_ADMIN_TOKEN = JwtTestTokens.tokenWithRoles("admin");
    private static final String READ_ONLY_TOKEN = JwtTestTokens.tokenWithRoles("viewer");
    private static final String UNMAPPED_TOKEN = JwtTestTokens.tokenWithRoles("some-other-role");

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

    /** Every functional endpoint family, so no controller is accidentally left ungated. */
    @ParameterizedTest
    @ValueSource(strings = {"/v1/pipelines/cache", "/v1/pipelines/paused", "/v1/dlq", "/v1/pipelines/r1/groups",
        "/v1/pipelines/r1/runtime"})
    void readEndpointsRequireCredential(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void mutatingEndpointsRequireCredential() throws Exception {
        mockMvc.perform(post("/v1/pipelines/r1/pause")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/dlq/requeue").param("pipeline_name", "r1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/backfills/run-1/cancel")).andExpect(status().isUnauthorized());
    }

    @Test
    void missingCredentialReturnsTheErrorViewShape() throws Exception {
        mockMvc.perform(get("/v1/pipelines/cache"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/v1/pipelines/cache"))
                .andExpect(jsonPath("$.method").value("GET"));
    }

    @Test
    void fullAdminMayInvokeFunctionalEndpoint() throws Exception {
        mockMvc.perform(get("/v1/pipelines/cache").header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines").exists());
    }

    @Test
    void fullAdminMayInvokeMutatingEndpoint() throws Exception {
        // Pipeline r1 is not cached, so this resolves to a 404 from the controller — the point is that it got
        // past authorization rather than being refused with a 401 or 403.
        mockMvc.perform(post("/v1/pipelines/r1/pause").header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_ADMIN_TOKEN))
                .andExpect(status().is(not(401)))
                .andExpect(status().is(not(403)));
    }

    @Test
    void readOnlyAdminIsForbiddenEvenOnReads() throws Exception {
        mockMvc.perform(get("/v1/pipelines/cache").header(HttpHeaders.AUTHORIZATION, "Bearer " + READ_ONLY_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("forbidden"))
                .andExpect(jsonPath("$.path").value("/v1/pipelines/cache"))
                .andExpect(jsonPath("$.method").value("GET"));
    }

    @Test
    void tokenMappingToNoApplicationRoleIsForbidden() throws Exception {
        mockMvc.perform(get("/v1/pipelines/cache").header(HttpHeaders.AUTHORIZATION, "Bearer " + UNMAPPED_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void malformedTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/pipelines/cache").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void expiredTokenIsUnauthorized() throws Exception {
        String expired = JwtTestTokens.expiredToken(JwtTestTokens.ISSUER, JwtTestTokens.AUDIENCE,
                Map.of("oid", "test-principal", "roles", List.of("admin")));

        mockMvc.perform(get("/v1/pipelines/cache").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenFromUnconfiguredIssuerIsUnauthorized() throws Exception {
        String foreign = JwtTestTokens.token("https://someone-else.example", JwtTestTokens.AUDIENCE,
                Map.of("oid", "test-principal", "roles", List.of("admin")));

        mockMvc.perform(get("/v1/pipelines/cache").header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithUnacceptedAudienceIsUnauthorized() throws Exception {
        String wrongAudience = JwtTestTokens.token(JwtTestTokens.ISSUER, "some-other-service",
                Map.of("oid", "test-principal", "roles", List.of("admin")));

        mockMvc.perform(get("/v1/pipelines/cache").header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongAudience))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void failureMessageNeverEchoesTheCredential() throws Exception {
        String body = mockMvc.perform(get("/v1/pipelines/cache")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + READ_ONLY_TOKEN))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain(READ_ONLY_TOKEN);
    }
}
