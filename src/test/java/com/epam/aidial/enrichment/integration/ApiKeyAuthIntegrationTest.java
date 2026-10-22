package com.epam.aidial.enrichment.integration;

import com.epam.aidial.enrichment.web.security.JwtTestTokens;
import com.epam.aidial.enrichment.web.security.TestSecurityConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DIAL {@code Api-Key} authentication layered onto {@code oidc} mode: both key shapes Core returns,
 * Bearer precedence, the 401-versus-503 split, and the introspection cache. DIAL Core is stubbed with
 * WireMock; the runner's own PostgreSQL runs in Testcontainers.
 *
 * <p>MockMvc's request builders are used qualified because WireMock's {@code get} matcher is statically
 * imported under the same name.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class ApiKeyAuthIntegrationTest {

    private static final WireMockServer CORE = new WireMockServer(options().dynamicPort());
    private static final String USER_INFO = "/v1/user/info";
    private static final String API_KEY_HEADER = "Api-Key";
    private static final String PIPELINE_CACHE = "/v1/pipelines/cache";
    private static final String API_DOCS = "/v3/api-docs";

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startCore() {
        CORE.start();
    }

    @AfterAll
    static void stopCore() {
        CORE.stop();
    }

    @BeforeEach
    void resetStubs() {
        CORE.resetAll();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
        TestSecurityConfig.registerOidcProvider(registry);
        registry.add("security.api-key.enabled", () -> "true");
        registry.add("security.api-key.core-url", CORE::baseUrl);
        registry.add("security.api-key.roles-mapping", () -> "{\"project-admin\":[\"FULL_ADMIN\"]}");
        // The startup probe is exercised in CoreApiKeyIntrospectorTest; here it would only couple
        // context startup to stub registration order.
        registry.add("security.api-key.startup-probe", () -> "false");
    }

    private static void stubProjectKey(String roles) {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO))
                .willReturn(okJson("{\"project\":\"analytics\",\"roles\":[" + roles + "]}")));
    }

    private static void stubAdminUserClaimsKey() {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO))
                .willReturn(okJson("{\"userClaims\":{\"oid\":[\"user-1\"],\"unique_name\":[\"u@example.com\"]},"
                        + "\"roles\":[\"admin\"]}")));
    }

    @Test
    void validProjectKeyAuthenticatesAndIsAuthorized() throws Exception {
        stubProjectKey("\"project-admin\"");

        mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE).header(API_KEY_HEADER, "a-valid-project-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines").exists());
    }

    @Test
    void projectKeyWithUnmappedRoleIsForbidden() throws Exception {
        stubProjectKey("\"some-other-role\"");

        mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE).header(API_KEY_HEADER, "mapped-to-nothing"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void jwtRootedKeyUsesTheDefaultRolesMapping() throws Exception {
        // "admin" is absent from api-key.roles-mapping but present in security.defaults.roles-mapping,
        // so authorizing on it proves the userClaims path used the default mapping.
        stubAdminUserClaimsKey();

        mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE).header(API_KEY_HEADER, "a-jwt-rooted-key"))
                .andExpect(status().isOk());
    }

    @Test
    void jwtRootedKeyMissingThePrincipalClaimIsUnauthorized() throws Exception {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO))
                .willReturn(okJson("{\"userClaims\":{\"unique_name\":[\"u@example.com\"]},\"roles\":[\"admin\"]}")));

        mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE).header(API_KEY_HEADER, "no-principal"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void keyCoreRejectsIsUnauthorized() throws Exception {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(aResponse().withStatus(401)));

        mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE).header(API_KEY_HEADER, "revoked"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.path").value(PIPELINE_CACHE));
    }

    @Test
    void unreachableCoreIsServiceUnavailableRatherThanUnauthorized() throws Exception {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE).header(API_KEY_HEADER, "cannot-be-checked"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("service_unavailable"));
    }

    @Test
    void authorizationHeaderTakesPrecedenceOverApiKey() throws Exception {
        stubProjectKey("\"project-admin\"");

        mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.tokenWithRoles("admin"))
                        .header(API_KEY_HEADER, "should-be-ignored"))
                .andExpect(status().isOk());

        CORE.verify(0, getRequestedFor(urlPathEqualTo(USER_INFO)));
    }

    @Test
    void validKeyReachesCoreAtMostOncePerCacheInterval() throws Exception {
        stubProjectKey("\"project-admin\"");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE).header(API_KEY_HEADER, "a-repeatedly-used-key"))
                    .andExpect(status().isOk());
        }

        CORE.verify(1, getRequestedFor(urlPathEqualTo(USER_INFO)));
    }

    @Test
    void rejectedKeyIsNotCached() throws Exception {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(aResponse().withStatus(401)));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE).header(API_KEY_HEADER, "always-rejected"))
                    .andExpect(status().isUnauthorized());
        }

        CORE.verify(2, getRequestedFor(urlPathEqualTo(USER_INFO)));
    }

    /** The API description is not a public path by default, so the filter authenticates the key as anywhere else. */
    @Test
    void apiDocsAreServedToFullAdminKey() throws Exception {
        stubProjectKey("\"project-admin\"");

        mockMvc.perform(MockMvcRequestBuilders.get(API_DOCS).header(API_KEY_HEADER, "a-docs-reading-key"))
                .andExpect(status().isOk());
    }

    @Test
    void apiDocsRequireKeyByDefault() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(API_DOCS))
                .andExpect(status().isUnauthorized());

        CORE.verify(0, getRequestedFor(urlPathEqualTo(USER_INFO)));
    }

    @Test
    void noCredentialAtAllIsUnauthorized() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(PIPELINE_CACHE))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The operational surface is permitted regardless of credential, so introspecting a key there could only
     * turn a probe or a scrape into a 401 — or a 503 while Core is unreachable — for a caller that happened
     * to send a bad key. Core is stubbed as unreachable to prove the filter never runs at all.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/prometheus", "/actuator/info"})
    void publicPathsIgnoreTheApiKeyHeaderEntirely(String path) throws Exception {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        mockMvc.perform(MockMvcRequestBuilders.get(path).header(API_KEY_HEADER, "a-rejected-key"))
                .andExpect(status().isOk());

        CORE.verify(0, getRequestedFor(urlPathEqualTo(USER_INFO)));
    }
}
