package com.epam.aidial.enrichment.configuration;

import com.epam.aidial.enrichment.web.security.apikey.CoreApiKeyIntrospector;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

/**
 * OpenAPI document for the Swagger UI. Declares the two ways a caller may authenticate in
 * {@code oidc} mode so the "Authorize" dialog can carry credentials on try-it-out requests:
 * an {@code Api-Key} header (DIAL API key introspected against Core) and a Bearer JWT/opaque token
 * in the {@code Authorization} header. Without this the UI has no Authorize button at all, and every
 * try-it-out call against {@code /v1/**} goes out bare — a 401 as soon as authentication is enforced.
 *
 * <p>Both are declared as global, independent requirements — the UI offers each on its own, matching
 * the runtime where either header authenticates a request and the Bearer header takes precedence when
 * both are present. Two separate {@code addSecurityItem} calls are what express that OR; collapsing
 * them into one requirement listing both schemes would mean "present both", which is not the contract.
 *
 * <p>The schemes are documentation only — they enforce nothing, so declaring them unconditionally is
 * safe even in {@code none} mode, and keeps the button present in the local setup where the mode gets
 * flipped back and forth.
 *
 * <p>The header name is read from {@link CoreApiKeyIntrospector#API_KEY_HEADER} rather than repeated as
 * a literal, so what the document advertises cannot drift from what
 * {@link com.epam.aidial.enrichment.web.security.apikey.ApiKeyAuthenticationFilter} actually reads.
 */
@Configuration
public class OpenApiConfiguration {

    private static final String API_KEY_SCHEME = "ApiKeyAuth";
    private static final String BEARER_SCHEME = "BearerAuth";

    @Bean
    public OpenAPI runnerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Analytics Enrichment Runner API")
                        .description("Runs enrichment for DIAL Analytics 2.0 over ADAS-managed source and side-tables.")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(CoreApiKeyIntrospector.API_KEY_HEADER)
                                .description("DIAL API key sent in the " + CoreApiKeyIntrospector.API_KEY_HEADER
                                        + " header (active in oidc mode when security.api-key.enabled=true)."))
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .in(SecurityScheme.In.HEADER)
                                .name(HttpHeaders.AUTHORIZATION)
                                .description("OIDC bearer token (JWT or opaque) sent in the Authorization header.")))
                // Two separate requirements express OR: a request may authenticate with either scheme.
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
