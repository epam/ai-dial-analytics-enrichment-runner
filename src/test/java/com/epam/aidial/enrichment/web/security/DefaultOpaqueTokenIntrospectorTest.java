package com.epam.aidial.enrichment.web.security;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Introspection of a single opaque provider: the userinfo call, the claims it yields, and the failure
 * modes. The provider is stubbed with WireMock rather than mocked, so the actual HTTP shape — the bearer
 * header the endpoint receives, and how a non-2xx or empty body surfaces — is what is asserted.
 */
class DefaultOpaqueTokenIntrospectorTest {

    private static final WireMockServer PROVIDER = new WireMockServer(options().dynamicPort());
    private static final String USER_INFO = "/userinfo";
    private static final String TOKEN = "opaque-token-value";

    @BeforeAll
    static void startProvider() {
        PROVIDER.start();
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop();
    }

    @BeforeEach
    void resetStubs() {
        PROVIDER.resetAll();
    }

    private static DefaultOpaqueTokenIntrospector introspector(List<String> roleClaims) {
        MultiPathGrantedAuthoritiesConverter<ClaimAccessor> authoritiesConverter =
                new MultiPathGrantedAuthoritiesConverter<>();
        authoritiesConverter.setAuthoritiesPaths(roleClaims);
        authoritiesConverter.setAuthorityPrefix("");
        return new DefaultOpaqueTokenIntrospector(
                RestClient.create(), "google", PROVIDER.baseUrl() + USER_INFO, authoritiesConverter,
                "sub", roleClaims, Set.of("email"));
    }

    @Test
    void introspectsUserInfoIntoPrincipalWithRoles() {
        PROVIDER.stubFor(get(urlPathEqualTo(USER_INFO))
                .willReturn(okJson("""
                        {"sub":"user-1","email":"someone@example.com","roles":["admin","viewer"]}""")));

        OAuth2AuthenticatedPrincipal principal = introspector(List.of("roles")).introspect(TOKEN);

        assertThat(principal.getName()).isEqualTo("user-1");
        assertThat(principal.<String>getAttribute("email")).isEqualTo("someone@example.com");
        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("admin", "viewer");
    }

    /** The provider name travels with the principal so the right per-provider converter is chosen later. */
    @Test
    void stampsTheOriginatingProviderName() {
        PROVIDER.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(okJson("""
                {"sub":"user-1","roles":["admin"]}""")));

        OAuth2AuthenticatedPrincipal principal = introspector(List.of("roles")).introspect(TOKEN);

        assertThat(principal.<String>getAttribute(DefaultOpaqueTokenIntrospector.IDP_CLAIM)).isEqualTo("google");
    }

    @Test
    void presentsTheTokenAsBearerCredential() {
        PROVIDER.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(okJson("""
                {"sub":"user-1","roles":["admin"]}""")));

        introspector(List.of("roles")).introspect(TOKEN);

        PROVIDER.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo(USER_INFO))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
    }

    /** Without the principal claim there is still a stable name to log and authorize against. */
    @Test
    void fallsBackToTheProviderNameWhenThePrincipalClaimIsAbsent() {
        PROVIDER.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(okJson("""
                {"roles":["admin"]}""")));

        assertThat(introspector(List.of("roles")).introspect(TOKEN).getName()).isEqualTo("google");
    }

    @Test
    void rejectsTokenTheProviderRefuses() {
        PROVIDER.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> introspector(List.of("roles")).introspect(TOKEN))
                .isInstanceOf(OAuth2IntrospectionException.class)
                .hasMessageContaining("google");
    }

    /** An empty body carries no identity, so it must fail rather than authenticate an anonymous principal. */
    @Test
    void rejectsAnEmptyUserInfoResponse() {
        PROVIDER.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("")));

        assertThatThrownBy(() -> introspector(List.of("roles")).introspect(TOKEN))
                .isInstanceOf(OAuth2IntrospectionException.class);
    }

    @Test
    void readsNestedRoleClaimPaths() {
        PROVIDER.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(okJson("""
                {"sub":"user-1","resource_access":{"roles":["admin"]}}""")));

        OAuth2AuthenticatedPrincipal principal = introspector(List.of("resource_access.roles")).introspect(TOKEN);

        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("admin");
    }
}
