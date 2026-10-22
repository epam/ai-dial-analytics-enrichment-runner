package com.epam.aidial.enrichment.configuration;

import com.epam.aidial.enrichment.web.security.apikey.CoreApiKeyIntrospector;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The declared security schemes are what the Swagger UI's "Authorize" dialog is built from, and two details
 * of the declaration are easy to get wrong in ways nothing else catches: the advertised header name, and the
 * OR shape. A wrong header name sends the credential somewhere the API-key filter never reads; collapsing
 * the two requirements into one would make the dialog demand both credentials at once, when either alone
 * authenticates. Both are pinned here.
 */
class OpenApiConfigurationTest {

    private final OpenAPI openApi = new OpenApiConfiguration().runnerOpenApi();

    @Test
    void apiKeySchemeAdvertisesTheHeaderTheFilterReads() {
        SecurityScheme scheme = openApi.getComponents().getSecuritySchemes().get("ApiKeyAuth");

        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(scheme.getIn()).isEqualTo(SecurityScheme.In.HEADER);
        assertThat(scheme.getName()).isEqualTo(CoreApiKeyIntrospector.API_KEY_HEADER);
    }

    @Test
    void bearerSchemeIsDeclaredOnTheAuthorizationHeader() {
        SecurityScheme scheme = openApi.getComponents().getSecuritySchemes().get("BearerAuth");

        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        assertThat(scheme.getBearerFormat()).isEqualTo("JWT");
        assertThat(scheme.getName()).isEqualTo(HttpHeaders.AUTHORIZATION);
    }

    /**
     * Two requirements of one scheme each — not one requirement of two — because either header alone
     * authenticates a request at runtime.
     */
    @Test
    void eitherCredentialAloneSatisfiesTheDocument() {
        List<SecurityRequirement> security = openApi.getSecurity();

        assertThat(security).hasSize(2);
        assertThat(security).allSatisfy(requirement -> assertThat(requirement).hasSize(1));
        assertThat(security.stream().flatMap(requirement -> requirement.keySet().stream()))
                .containsExactlyInAnyOrder("ApiKeyAuth", "BearerAuth");
    }
}
