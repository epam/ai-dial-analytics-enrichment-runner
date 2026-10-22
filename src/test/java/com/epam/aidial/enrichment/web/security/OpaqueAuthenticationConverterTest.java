package com.epam.aidial.enrichment.web.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Turning an introspected principal into the authentication token: the email rules and the mapping from
 * provider roles onto application roles, which must behave exactly as the JWT path does so a caller's
 * entitlements do not depend on which token shape their provider issues.
 */
class OpaqueAuthenticationConverterTest {

    private static final String TOKEN = "opaque-token";
    private static final Map<String, Set<UserRole>> ROLES_MAPPING = Map.of(
            "admin", Set.of(UserRole.FULL_ADMIN),
            "viewer", Set.of(UserRole.READ_ONLY_ADMIN));

    private static OpaqueAuthenticationConverter converter(Set<String> emailClaims, boolean requireEmail) {
        return new OpaqueAuthenticationConverter(new UserRolesResolver(ROLES_MAPPING), emailClaims, requireEmail);
    }

    private static OAuth2AuthenticatedPrincipal principal(Map<String, Object> attributes, String... providerRoles) {
        Map<String, Object> withIdp = new LinkedHashMap<>(attributes);
        withIdp.put(DefaultOpaqueTokenIntrospector.IDP_CLAIM, "google");
        List<GrantedAuthority> authorities = Stream.of(providerRoles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role))
                .toList();
        return new OAuth2IntrospectionAuthenticatedPrincipal("user-1", withIdp, authorities);
    }

    @Test
    void mapsProviderRolesOntoApplicationRoles() {
        Authentication authentication = converter(Set.of("email"), false)
                .convert(TOKEN, principal(Map.of("email", "someone@example.com"), "admin"));

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly(UserRole.FULL_ADMIN.name());
        assertThat(authentication.getDetails()).isEqualTo(new UserSecurityDetails("someone@example.com"));
    }

    /**
     * Unlike ADAS, the token carries mapped application roles only — the runner has no per-target
     * authorization for a raw provider role name to match against.
     */
    @Test
    void unmappedProviderRoleGrantsNoAuthority() {
        Authentication authentication = converter(Set.of("email"), false)
                .convert(TOKEN, principal(Map.of("email", "someone@example.com"), "some-other-role"));

        assertThat(authentication.getAuthorities()).isEmpty();
        assertThat(authentication.isAuthenticated()).isTrue();
    }

    @Test
    void readsEmailClaimsInTheConfiguredOrder() {
        Set<String> ordered = new LinkedHashSet<>(List.of("preferred_email", "email"));

        Authentication authentication = converter(ordered, true).convert(TOKEN, principal(
                new LinkedHashMap<>(Map.of("email", "fallback@example.com",
                        "preferred_email", "first@example.com")), "admin"));

        assertThat(authentication.getDetails()).isEqualTo(new UserSecurityDetails("first@example.com"));
    }

    @Test
    void emailIsNullWhenNoConfiguredClaimResolvesAndNoneIsRequired() {
        Authentication authentication = converter(Set.of("email"), false)
                .convert(TOKEN, principal(Map.of("sub", "user-1"), "admin"));

        assertThat(authentication.getDetails()).isEqualTo(new UserSecurityDetails(null));
    }

    /**
     * An invalid-token exception, not ADAS's {@code AuthenticationServiceException}: the token is missing a
     * required claim, which is the caller's problem rather than a failure of this service. Both render 401
     * here, but the two are told apart on the api-key path, where a service exception means 503.
     */
    @Test
    void requiredEmailThatDoesNotResolveRejectsTheToken() {
        var converter = converter(Set.of("email"), true);
        var principal = principal(Map.of("sub", "user-1"), "admin");

        assertThatThrownBy(() -> converter.convert(TOKEN, principal))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasMessageContaining("Email claim is required");
    }
}
