package com.epam.aidial.enrichment.integration;

import com.epam.aidial.enrichment.web.security.JwtTestTokens;
import com.epam.aidial.enrichment.web.security.TestSecurityConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Opaque (non-JWT) Bearer authentication end to end: the userinfo introspection, the roles mapping onto
 * {@code FULL_ADMIN}, the bound on an unresponsive provider, and the fact that adding an opaque provider
 * leaves the JWT path alone. The provider is stubbed with WireMock; the runner's own PostgreSQL runs in
 * Testcontainers.
 *
 * <p>The introspection cache is deliberately <em>not</em> exercised here — each test asserts its own
 * introspection count, so the TTL is set to zero below. {@code CachingOpaqueTokenIntrospectorTest} covers
 * the cache.
 *
 * <p>MockMvc's request builders are used qualified because WireMock's {@code get} matcher is statically
 * imported under the same name.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class OpaqueAuthIntegrationTest {

    private static final WireMockServer PROVIDER = new WireMockServer(options().dynamicPort());
    private static final String USER_INFO = "/userinfo";
    private static final String PIPELINE_CACHE = "/v1/pipelines/cache";
    private static final int REQUEST_TIMEOUT_MS = 300;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startProvider() {
        PROVIDER.start();
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop();
    }

    @BeforeEach
    void resetStubs() {
        PROVIDER.resetAll();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
        // The JWT provider stays configured alongside, so the resolver split is exercised in both directions.
        TestSecurityConfig.registerOidcProvider(registry);
        registry.add("providers.opaque.user-info-endpoint", () -> PROVIDER.baseUrl() + USER_INFO);
        registry.add("providers.opaque.role-claims", () -> List.of("roles"));
        registry.add("providers.opaque.email-claims", () -> List.of("email"));
        registry.add("providers.opaque.principal-claim", () -> "sub");
        registry.add("providers.opaque.roles-mapping",
                () -> "{\"admin\":[\"FULL_ADMIN\"],\"viewer\":[\"READ_ONLY_ADMIN\"]}");
        // Each test asserts its own introspection count, so entries must not survive between them.
        registry.add("security.opaque-token.cache-ttl-seconds", () -> "0");
        // Well below the delay unresponsiveProviderDoesNotHangTheRequest() stubs, so that test bounds the
        // suite rather than the other way round. Every other stub here answers immediately.
        registry.add("security.opaque-token.request-timeout-ms", () -> String.valueOf(REQUEST_TIMEOUT_MS));
    }

    private void stubUserInfo(String role) {
        PROVIDER.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(okJson(
                "{\"sub\":\"user-1\",\"email\":\"someone@example.com\",\"roles\":[\"" + role + "\"]}")));
    }

    @Test
    void opaqueTokenMappingToFullAdminIsAuthorized() throws Exception {
        stubUserInfo("admin");

        mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines").exists());

        PROVIDER.verify(getRequestedFor(urlPathEqualTo(USER_INFO)));
    }

    @Test
    void opaqueTokenMappingToReadOnlyIsForbidden() throws Exception {
        stubUserInfo("viewer");

        mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("forbidden"))
                .andExpect(jsonPath("$.path").value(PIPELINE_CACHE));
    }

    @Test
    void opaqueTokenMappingToNoApplicationRoleIsForbidden() throws Exception {
        stubUserInfo("some-other-role");

        mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void tokenNoProviderAcceptsIsUnauthorized() throws Exception {
        PROVIDER.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(aResponse().withStatus(401)));

        mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.method").value("GET"));
    }

    @Test
    void rejectionResponseNeverEchoesTheCredential() throws Exception {
        PROVIDER.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(aResponse().withStatus(401)));

        String body = mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer super-secret-token"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("super-secret-token");
    }

    /**
     * A provider that accepts the connection and then never answers must not hold the serving thread for
     * as long as the container allows — that is what the userinfo timeout is for. The request is rejected
     * as unauthenticated, since a token the runner could not get introspected is a token no provider
     * accepted.
     */
    @Test
    void unresponsiveProviderDoesNotHangTheRequest() throws Exception {
        PROVIDER.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(okJson("{\"sub\":\"user-1\"}")
                .withFixedDelay(REQUEST_TIMEOUT_MS * 20)));

        mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    /** The resolver must not send a JWS down the introspection path now that opaque support exists. */
    @Test
    void jwtStillAuthenticatesWithAnOpaqueProviderConfigured() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.tokenWithRoles("admin")))
                .andExpect(status().isOk());

        PROVIDER.verify(0, getRequestedFor(urlPathEqualTo(USER_INFO)));
    }

    @Test
    void operationalEndpointsStayCredentialFree() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
