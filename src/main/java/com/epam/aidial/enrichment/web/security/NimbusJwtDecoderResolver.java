package com.epam.aidial.enrichment.web.security;

import com.epam.aidial.enrichment.web.security.IdentityProvidersProperties.ProviderConfig;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the {@link NimbusJwtDecoder} for a single provider. Extracted behind an interface so tests
 * can substitute a decoder backed by a shared secret (HMAC) in place of the production JWKS-backed
 * decoder, removing the need for a live JWKS endpoint.
 */
public interface NimbusJwtDecoderResolver {

    NimbusJwtDecoder resolve(ProviderConfig config);
}
