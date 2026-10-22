package com.epam.aidial.enrichment.web.security;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Typed binding for the identity providers map, configured under the top-level {@code providers.*}
 * key (matching ADAS and the ai-dial-admin-backend reference); property descriptions live in
 * {@code docs/configuration.md}. Scalar authentication settings live under {@code security.*} — see
 * {@link SecurityProperties}.
 *
 * <p>This holder is bound in every mode (it is harmless when {@code security.mode} is {@code none});
 * the {@code oidc} wiring in {@link OidcSecurityConfiguration} validates that at least one usable
 * provider exists.
 *
 * <p>Both token shapes ADAS accepts are supported, and the two kinds may be configured side by side.
 * A <b>JWT provider</b> declares an issuer and a JWKS endpoint, and its tokens are verified locally.
 * An <b>opaque provider</b> declares a userinfo endpoint, and its tokens — which carry no issuer to
 * match on — are introspected by calling that endpoint. A provider declaring neither is ignored.
 *
 * @param providers identity providers keyed by an arbitrary local name; see {@link ProviderConfig}
 */
@ConfigurationProperties
public record IdentityProvidersProperties(
        Map<String, ProviderConfig> providers) {

    /**
     * A single identity provider, of one of two kinds. It is a JWT provider when it has both an
     * {@code issuer} and a {@code jwkSetUri}: tokens are verified locally against the JWKS. It is an
     * opaque provider when it has a {@code userInfoEndpoint}: tokens are introspected against it. A
     * provider that is neither is ignored.
     *
     * @param issuer           expected {@code iss} claim value (a URL), or a tenant id when {@code aliases} is set
     * @param jwkSetUri        JWKS endpoint used to fetch the provider's signing keys; marks a JWT provider
     * @param userInfoEndpoint OIDC userinfo endpoint introspected for opaque tokens; marks an opaque provider
     * @param audiences        accepted {@code aud} claim values (an {@code api://} prefixed variant is also
     *                         accepted); JWT providers only — there is no local audience check to make for an
     *                         opaque token
     * @param aliases          optional host aliases used to expand Azure v1/v2 issuer URLs from a tenant id
     * @param roleClaims       dotted claim paths to read provider role names from (e.g.
     *                         {@code resource_access.roles}), or a single {@code fn:}-prefixed entry naming a
     *                         custom converter; see {@link OpaqueTokenCustomGrantedAuthoritiesConverters}
     * @param emailClaims      claim names to read the caller's email from, tried in order
     * @param principalClaim   claim used as the authenticated principal name; falls back to the default when blank
     * @param rolesMapping     JSON object mapping this provider's role names to lists of {@link UserRole}
     */
    public record ProviderConfig(
            String issuer,
            String jwkSetUri,
            String userInfoEndpoint,
            List<String> audiences,
            List<String> aliases,
            List<String> roleClaims,
            List<String> emailClaims,
            String principalClaim,
            String rolesMapping) {

        public boolean hasJwkSetUri() {
            return StringUtils.isNotBlank(jwkSetUri);
        }

        public boolean hasUserInfoEndpoint() {
            return StringUtils.isNotBlank(userInfoEndpoint);
        }

        /** Whether this provider verifies tokens locally: a JWKS endpoint and an issuer to match on. */
        public boolean isJwtProvider() {
            return hasJwkSetUri() && StringUtils.isNotBlank(issuer);
        }

        /** Whether this provider verifies tokens by introspection against its userinfo endpoint. */
        public boolean isOpaqueProvider() {
            return hasUserInfoEndpoint();
        }

        /** Whether this provider can verify tokens at all, by either route. */
        public boolean isUsable() {
            return isJwtProvider() || isOpaqueProvider();
        }
    }
}
