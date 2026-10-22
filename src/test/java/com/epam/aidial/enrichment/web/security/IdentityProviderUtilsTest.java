package com.epam.aidial.enrichment.web.security;

import com.epam.aidial.enrichment.configuration.JsonMapperConfiguration;
import com.epam.aidial.enrichment.web.security.IdentityProvidersProperties.ProviderConfig;
import com.epam.aidial.enrichment.web.security.SecurityProperties.Defaults;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provider resolution and the {@code oidc}-mode fail-fast. The fail-fast is what stops an {@code oidc}
 * deployment from starting while silently accepting no credential, so it is asserted at the level where
 * it lives rather than by attempting a doomed context load.
 */
class IdentityProviderUtilsTest {

    private static final JsonMapper MAPPER = new JsonMapperConfiguration().jsonMapper();

    private static SecurityProperties oidcProperties(String rolesMapping) {
        return new SecurityProperties(SecurityProperties.MODE_OIDC, false, false,
                new Defaults(rolesMapping, "unique_name", "oid"));
    }

    private static ProviderConfig provider(String issuer, String jwkSetUri) {
        return new ProviderConfig(issuer, jwkSetUri, null, List.of("aud"), null, List.of("roles"), null, null, null);
    }

    /** An opaque provider: introspected against its userinfo endpoint, so no issuer and no audiences. */
    private static ProviderConfig opaqueProvider(String userInfoEndpoint, List<String> roleClaims) {
        return new ProviderConfig(null, null, userInfoEndpoint, null, null, roleClaims, null, null, null);
    }

    @Test
    void failsFastWhenNoProviderIsConfigured() {
        IdentityProvidersProperties none = new IdentityProvidersProperties(Map.of());

        assertThatThrownBy(() -> new IdentityProviderUtils(MAPPER, oidcProperties("{}"), none))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no usable identity providers")
                .hasMessageContaining("jwk-set-uri");
    }

    @Test
    void failsFastWhenProvidersMapIsAbsent() {
        IdentityProvidersProperties absent = new IdentityProvidersProperties(null);

        assertThatThrownBy(() -> new IdentityProviderUtils(MAPPER, oidcProperties("{}"), absent))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastWhenProviderHasNoJwkSetUri() {
        IdentityProvidersProperties unusable = new IdentityProvidersProperties(
                Map.of("azure", provider("https://issuer.example", null)));

        assertThatThrownBy(() -> new IdentityProviderUtils(MAPPER, oidcProperties("{}"), unusable))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastWhenProviderHasNoIssuer() {
        IdentityProvidersProperties unusable = new IdentityProvidersProperties(
                Map.of("azure", provider(null, "https://issuer.example/jwks")));

        assertThatThrownBy(() -> new IdentityProviderUtils(MAPPER, oidcProperties("{}"), unusable))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * A provider that satisfies {@code isUsable()} can still be incapable of admitting anyone. Each case
     * below would otherwise start cleanly and reject every caller uniformly — 401 for the issuer and
     * audience cases, 403 for the role-claims one — with only a per-request log line to say why.
     */
    @Test
    void failsFastWhenTenantIdIssuerHasNoAliasesToExpandIt() {
        ProviderConfig tenantWithoutAliases = new ProviderConfig("tenant-123", "https://issuer.example/jwks",
                null, List.of("aud"), null, List.of("roles"), null, null, null);

        assertThatThrownBy(() -> new IdentityProviderUtils(MAPPER, oidcProperties("{}"),
                new IdentityProvidersProperties(Map.of("azure", tenantWithoutAliases))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accepts no issuer")
                .hasMessageContaining("providers.azure.aliases");
    }

    @Test
    void failsFastWhenProviderDeclaresNoAudiences() {
        ProviderConfig noAudiences = new ProviderConfig("https://issuer.example", "https://issuer.example/jwks",
                null, null, null, List.of("roles"), null, null, null);

        assertThatThrownBy(() -> new IdentityProviderUtils(MAPPER, oidcProperties("{}"),
                new IdentityProvidersProperties(Map.of("azure", noAudiences))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("providers.azure.audiences");
    }

    @Test
    void failsFastWhenProviderDeclaresNoRoleClaims() {
        ProviderConfig noRoleClaims = new ProviderConfig("https://issuer.example", "https://issuer.example/jwks",
                null, List.of("aud"), null, List.of(" "), null, null, null);

        assertThatThrownBy(() -> new IdentityProviderUtils(MAPPER, oidcProperties("{}"),
                new IdentityProvidersProperties(Map.of("azure", noRoleClaims))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("providers.azure.role-claims");
    }

    /**
     * Both lookup tables are keyed by issuer, so a shared issuer means one provider silently overwrites the
     * other — and which one wins depends on map iteration order.
     */
    @Test
    void failsFastWhenTwoProvidersAcceptTheSameIssuer() {
        IdentityProvidersProperties collision = new IdentityProvidersProperties(Map.of(
                "first", provider("https://issuer.example", "https://first.example/jwks"),
                "second", provider("https://issuer.example", "https://second.example/jwks")));

        assertThatThrownBy(() -> new IdentityProviderUtils(MAPPER, oidcProperties("{}"), collision))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both accept the issuer 'https://issuer.example'");
    }

    @Test
    void acceptsUsableProvider() {
        IdentityProvidersProperties usable = new IdentityProvidersProperties(
                Map.of("azure", provider("https://issuer.example", "https://issuer.example/jwks")));

        IdentityProviderUtils utils = new IdentityProviderUtils(MAPPER, oidcProperties("{}"), usable);

        assertThat(utils.getAcceptedIssuers(usable.providers().get("azure")))
                .containsExactly("https://issuer.example");
    }

    @Test
    void expandsAzureTenantIdIntoV1AndV2Issuers() {
        ProviderConfig tenant = new ProviderConfig("tenant-123", "https://issuer.example/jwks",
                null, List.of("aud"), List.of("login.microsoftonline.com"), List.of("roles"), null, null, null);
        IdentityProvidersProperties properties = new IdentityProvidersProperties(Map.of("azure", tenant));

        IdentityProviderUtils utils = new IdentityProviderUtils(MAPPER, oidcProperties("{}"), properties);

        assertThat(utils.getAcceptedIssuers(tenant)).containsExactlyInAnyOrder(
                "https://login.microsoftonline.com/tenant-123/",
                "https://login.microsoftonline.com/tenant-123/v2.0");
    }

    @Test
    void providerRolesMappingWinsOverTheDefault() {
        ProviderConfig withOwnMapping = new ProviderConfig("https://issuer.example",
                "https://issuer.example/jwks", null, List.of("aud"), null, List.of("roles"), null, null,
                "{\"admin\":[\"READ_ONLY_ADMIN\"]}");
        IdentityProvidersProperties properties = new IdentityProvidersProperties(Map.of("p", withOwnMapping));

        IdentityProviderUtils utils = new IdentityProviderUtils(MAPPER,
                oidcProperties("{\"admin\":[\"FULL_ADMIN\"],\"ops\":[\"FULL_ADMIN\"]}"), properties);

        Map<String, Set<UserRole>> mapping = utils.getRolesMapping(withOwnMapping);

        assertThat(mapping).containsEntry("admin", Set.of(UserRole.READ_ONLY_ADMIN));
        // Default entries the provider does not override survive the merge.
        assertThat(mapping).containsEntry("ops", Set.of(UserRole.FULL_ADMIN));
    }

    @Test
    void readOnlyAdminIsAcceptedAsMappingTargetSoAdasShapedConfigBinds() {
        IdentityProvidersProperties properties = new IdentityProvidersProperties(
                Map.of("p", provider("https://issuer.example", "https://issuer.example/jwks")));

        IdentityProviderUtils utils = new IdentityProviderUtils(MAPPER,
                oidcProperties("{\"ConfigAdmin\":[\"FULL_ADMIN\"],\"viewer\":[\"READ_ONLY_ADMIN\"]}"), properties);

        assertThat(utils.getRolesMapping(properties.providers().get("p")))
                .containsEntry("viewer", Set.of(UserRole.READ_ONLY_ADMIN));
    }

    /**
     * The completeness checks branch on provider kind. An opaque provider matches no issuer and performs no
     * local audience check, so demanding either of it would reject the ADAS-shaped configuration this
     * service exists to accept unchanged.
     */
    @Test
    void opaqueProviderNeedsNeitherIssuerNorAudiences() {
        IdentityProvidersProperties properties = new IdentityProvidersProperties(
                Map.of("google", opaqueProvider("https://provider.example/userinfo", List.of("roles"))));

        IdentityProviderUtils utils = new IdentityProviderUtils(MAPPER, oidcProperties("{}"), properties);

        assertThat(utils.getRoleClaims(properties.providers().get("google"))).containsExactly("roles");
        assertThat(IdentityProviderUtils.opaqueProviders(properties)).containsOnlyKeys("google");
        assertThat(IdentityProviderUtils.jwtProviders(properties)).isEmpty();
    }

    @Test
    void opaqueProviderStillNeedsRoleClaims() {
        IdentityProvidersProperties properties = new IdentityProvidersProperties(
                Map.of("google", opaqueProvider("https://provider.example/userinfo", null)));

        assertThatThrownBy(() -> new IdentityProviderUtils(MAPPER, oidcProperties("{}"), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("providers.google.role-claims");
    }

    /**
     * A converter name is fixed configuration, so an unknown one refuses every caller of that provider
     * uniformly. Failing at startup puts the mistake where it can be seen.
     */
    @Test
    void failsFastWhenOpaqueProviderNamesAnUnknownCustomConverter() {
        IdentityProvidersProperties properties = new IdentityProvidersProperties(
                Map.of("google", opaqueProvider("https://provider.example/userinfo", List.of("fn:nope"))));

        assertThatThrownBy(() -> new IdentityProviderUtils(MAPPER, oidcProperties("{}"), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown custom role converter 'fn:nope'")
                .hasMessageContaining(OpaqueTokenCustomGrantedAuthoritiesConverters.GOOGLE_WORKSPACE_GROUPS);
    }

    /**
     * A converter derives roles by calling an API with the caller's token during introspection, which the
     * JWT path never performs — it would read {@code fn:...} as a claim path, match nothing, and refuse
     * every caller with 403 after authenticating them.
     */
    @Test
    void failsFastWhenJwtProviderSelectsCustomConverter() {
        ProviderConfig jwtWithConverter = new ProviderConfig("https://issuer.example",
                "https://issuer.example/jwks", null, List.of("aud"), null,
                List.of(OpaqueTokenCustomGrantedAuthoritiesConverters.GOOGLE_WORKSPACE_GROUPS), null, null, null);
        IdentityProvidersProperties properties =
                new IdentityProvidersProperties(Map.of("azure", jwtWithConverter));

        assertThatThrownBy(() -> new IdentityProviderUtils(MAPPER, oidcProperties("{}"), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("providers.azure.user-info-endpoint");
    }

    @Test
    void acceptsOpaqueProviderNamingKnownCustomConverter() {
        IdentityProvidersProperties properties = new IdentityProvidersProperties(Map.of("google", opaqueProvider(
                "https://provider.example/userinfo",
                List.of(OpaqueTokenCustomGrantedAuthoritiesConverters.GOOGLE_WORKSPACE_GROUPS))));

        assertThat(new IdentityProviderUtils(MAPPER, oidcProperties("{}"), properties)).isNotNull();
    }

    @Test
    void jwtAndOpaqueProvidersCoexist() {
        IdentityProvidersProperties properties = new IdentityProvidersProperties(Map.of(
                "azure", provider("https://issuer.example", "https://issuer.example/jwks"),
                "google", opaqueProvider("https://provider.example/userinfo", List.of("roles"))));

        IdentityProviderUtils utils = new IdentityProviderUtils(MAPPER, oidcProperties("{}"), properties);

        assertThat(utils).isNotNull();
        assertThat(IdentityProviderUtils.jwtProviders(properties)).containsOnlyKeys("azure");
        assertThat(IdentityProviderUtils.opaqueProviders(properties)).containsOnlyKeys("google");
        assertThat(IdentityProviderUtils.usableProviders(properties)).containsOnlyKeys("azure", "google");
    }

    /**
     * Opaque providers match no issuer, so they cannot collide on one. Two of them would otherwise trip the
     * distinct-issuer check on their shared empty issuer.
     */
    @Test
    void issuerCollisionCheckIgnoresOpaqueProviders() {
        IdentityProvidersProperties properties = new IdentityProvidersProperties(Map.of(
                "first", opaqueProvider("https://first.example/userinfo", List.of("roles")),
                "second", opaqueProvider("https://second.example/userinfo", List.of("roles"))));

        assertThat(new IdentityProviderUtils(MAPPER, oidcProperties("{}"), properties)).isNotNull();
    }

    @Test
    void emailAndPrincipalClaimsFallBackToTheDefaults() {
        IdentityProvidersProperties properties = new IdentityProvidersProperties(
                Map.of("p", provider("https://issuer.example", "https://issuer.example/jwks")));

        IdentityProviderUtils utils = new IdentityProviderUtils(MAPPER, oidcProperties("{}"), properties);
        ProviderConfig config = properties.providers().get("p");

        assertThat(utils.getEmailClaims(config)).containsExactly("unique_name");
        assertThat(utils.getPrincipalClaim(config)).isEqualTo("oid");
    }
}
