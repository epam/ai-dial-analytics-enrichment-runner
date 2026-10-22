package com.epam.aidial.enrichment.web.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.List;

/**
 * Test wiring that replaces the production JWKS-backed {@link NimbusJwtDecoderResolver} with one that
 * verifies tokens against the shared HMAC secret from {@link JwtTestTokens}. Imported explicitly by
 * auth slice tests; not component-scanned.
 */
@TestConfiguration
public class TestSecurityConfig {

    /**
     * Registers {@code oidc} mode with a single provider matching {@link JwtTestTokens}. The
     * {@code jwk-set-uri} is never fetched — {@link #testNimbusJwtDecoderResolver()} replaces the
     * decoder — but it must be present for the provider to count as usable.
     *
     * <p>The provider-level roles mapping deliberately differs from the service-wide default so the
     * tests also cover provider-over-default precedence, and so a {@code READ_ONLY_ADMIN} caller exists
     * to prove reads are not exempt from the {@code FULL_ADMIN} rule.
     */
    public static void registerOidcProvider(DynamicPropertyRegistry registry) {
        registry.add("security.mode", () -> "oidc");
        registry.add("providers.test.issuer", () -> JwtTestTokens.ISSUER);
        registry.add("providers.test.jwk-set-uri", () -> "https://unused.example/.well-known/jwks.json");
        registry.add("providers.test.audiences", () -> List.of(JwtTestTokens.AUDIENCE));
        registry.add("providers.test.role-claims", () -> List.of("roles"));
        registry.add("providers.test.roles-mapping",
                () -> "{\"admin\":[\"FULL_ADMIN\"],\"viewer\":[\"READ_ONLY_ADMIN\"]}");
    }

    @Bean
    @Primary
    public NimbusJwtDecoderResolver testNimbusJwtDecoderResolver() {
        return config -> NimbusJwtDecoder.withSecretKey(JwtTestTokens.hmacSecretKey()).build();
    }
}
