package com.epam.aidial.enrichment.integration;

import com.epam.aidial.enrichment.web.security.TestSecurityConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With {@code security.disable-swagger-authorization=true} the API description joins the public paths, so the
 * {@code Api-Key} filter leaves it alone: a caller sending a key Core cannot check still gets the document rather
 * than a {@code 503}. Core is stubbed as unreachable throughout, which a functional path proves is still enforced.
 *
 * <p>MockMvc's request builders are used qualified because WireMock's {@code get} matcher is statically
 * imported under the same name.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class ApiKeyPublicApiDocsIntegrationTest {

    private static final WireMockServer CORE = new WireMockServer(options().dynamicPort());
    private static final String USER_INFO = "/v1/user/info";
    private static final String API_KEY_HEADER = "Api-Key";

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
    void stubUnreachableCore() {
        CORE.resetAll();
        CORE.stubFor(get(urlPathEqualTo(USER_INFO))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
        TestSecurityConfig.registerOidcProvider(registry);
        registry.add("security.disable-swagger-authorization", () -> "true");
        registry.add("security.api-key.enabled", () -> "true");
        registry.add("security.api-key.core-url", CORE::baseUrl);
        registry.add("security.api-key.roles-mapping", () -> "{\"project-admin\":[\"FULL_ADMIN\"]}");
        registry.add("security.api-key.startup-probe", () -> "false");
    }

    @Test
    void publicApiDocsIgnoreTheApiKeyHeader() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v3/api-docs").header(API_KEY_HEADER, "a-key-core-cannot-check"))
                .andExpect(status().isOk());

        CORE.verify(0, getRequestedFor(urlPathEqualTo(USER_INFO)));
    }

    @Test
    void functionalPathsStillIntrospectTheKey() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dlq").header(API_KEY_HEADER, "a-key-core-cannot-check"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("service_unavailable"));
    }
}
