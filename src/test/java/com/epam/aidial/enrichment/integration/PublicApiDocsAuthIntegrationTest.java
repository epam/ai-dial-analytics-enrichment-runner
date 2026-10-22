package com.epam.aidial.enrichment.integration;

import com.epam.aidial.enrichment.web.security.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code security.disable-swagger-authorization=true} serves the API description without a credential in
 * {@code oidc} mode, and opens nothing else.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class PublicApiDocsAuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
        TestSecurityConfig.registerOidcProvider(registry);
        registry.add("security.disable-swagger-authorization", () -> "true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/swagger-ui/index.html"})
    void apiDocsAreServedWithoutCredential(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().is2xxSuccessful());
    }

    /** The legacy entry point redirects to the UI, so a redirect is as good as the page itself. */
    @Test
    void swaggerUiEntryPointIsServedOrRedirectedWithoutCredential() throws Exception {
        int status = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();

        assertThat(HttpStatus.valueOf(status).is2xxSuccessful() || HttpStatus.valueOf(status).is3xxRedirection())
                .as("/swagger-ui.html answered %d", status)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/pipelines/cache", "/v1/dlq"})
    void functionalEndpointsStillRequireCredential(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
}
