package com.epam.aidial.enrichment.web.security;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code fn:} converter registry and the Google Workspace group converter. The Cloud Identity call is
 * stubbed by a {@link RestClient} bound to a local handler, so the response shapes the converter must cope
 * with — nested {@code groupKey.id}, an absent {@code memberships}, a paginated first page — are exercised
 * without reaching Google.
 */
class OpaqueTokenCustomGrantedAuthoritiesConvertersTest {

    private static final String EMAIL = "someone@example.com";

    @Test
    void recognisesSingleFnEntryAsConverterSelection() {
        assertThat(OpaqueTokenCustomGrantedAuthoritiesConverters.isCustomConverterSelection(
                List.of(OpaqueTokenCustomGrantedAuthoritiesConverters.GOOGLE_WORKSPACE_GROUPS))).isTrue();
    }

    /** Mixing a converter with claim paths is not supported: the whole list is read as claim paths. */
    @Test
    void doesNotTreatMixedListAsConverterSelection() {
        assertThat(OpaqueTokenCustomGrantedAuthoritiesConverters.isCustomConverterSelection(
                List.of(OpaqueTokenCustomGrantedAuthoritiesConverters.GOOGLE_WORKSPACE_GROUPS, "roles"))).isFalse();
        assertThat(OpaqueTokenCustomGrantedAuthoritiesConverters.isCustomConverterSelection(List.of("roles")))
                .isFalse();
        assertThat(OpaqueTokenCustomGrantedAuthoritiesConverters.isCustomConverterSelection(null)).isFalse();
    }

    @Test
    void resolvesKnownConverterAndRejectsUnknownOne() {
        assertThat(OpaqueTokenCustomGrantedAuthoritiesConverters.converterFor(
                OpaqueTokenCustomGrantedAuthoritiesConverters.GOOGLE_WORKSPACE_GROUPS)).isNotNull();
        assertThat(OpaqueTokenCustomGrantedAuthoritiesConverters.converterFor("fn:nope")).isNull();
    }

    @Test
    void directGroupMembershipsBecomeProviderRoleNames() {
        Collection<GrantedAuthority> authorities = googleGroups("""
                {"memberships":[
                  {"groupKey":{"id":"analytics-admins@example.com"}},
                  {"groupKey":{"id":"analytics-viewers@example.com"}}]}""");

        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactly("analytics-admins@example.com", "analytics-viewers@example.com");
    }

    @Test
    void responseWithoutMembershipsYieldsNoRoles() {
        assertThat(googleGroups("{}")).isEmpty();
    }

    /** A membership entry that does not carry a group id is skipped rather than failing the whole lookup. */
    @Test
    void skipsMalformedMembershipEntries() {
        Collection<GrantedAuthority> authorities = googleGroups("""
                {"memberships":[{"groupKey":{}},{"noGroupKey":true},{"groupKey":{"id":"good@example.com"}}]}""");

        assertThat(authorities).extracting(GrantedAuthority::getAuthority).containsExactly("good@example.com");
    }

    /** Only the first page is read; the caller still gets the roles it did resolve. */
    @Test
    void readsOnlyTheFirstPageOfPaginatedResponse() {
        Collection<GrantedAuthority> authorities = googleGroups("""
                {"nextPageToken":"more","memberships":[{"groupKey":{"id":"first@example.com"}}]}""");

        assertThat(authorities).extracting(GrantedAuthority::getAuthority).containsExactly("first@example.com");
    }

    /** Without an email there is nothing to query memberships by, so introspection fails rather than
     * silently granting no roles — which would read as a caller with no entitlements. */
    @Test
    void failsWhenNoEmailClaimResolves() {
        var converter = OpaqueTokenCustomGrantedAuthoritiesConverters.converterFor(
                OpaqueTokenCustomGrantedAuthoritiesConverters.GOOGLE_WORKSPACE_GROUPS);
        var context = new OpaqueAuthorityExtractionContext(
                RestClient.create(), "token", Map.of("sub", "user-1"), Set.of("email"));

        assertThatThrownBy(() -> converter.apply(context))
                .isInstanceOf(OAuth2IntrospectionException.class)
                .hasMessageContaining("Email claim is missing");
    }

    @Test
    void failsWhenTheMembershipLookupErrors() {
        var converter = OpaqueTokenCustomGrantedAuthoritiesConverters.converterFor(
                OpaqueTokenCustomGrantedAuthoritiesConverters.GOOGLE_WORKSPACE_GROUPS);
        RestClient failing = RestClient.builder()
                .requestFactory((uri, httpMethod) -> {
                    throw new java.io.IOException("connection refused");
                })
                .build();
        var context = new OpaqueAuthorityExtractionContext(
                failing, "token", Map.of("email", EMAIL), Set.of("email"));

        assertThatThrownBy(() -> converter.apply(context))
                .isInstanceOf(OAuth2IntrospectionException.class)
                .hasMessageContaining("Failed to retrieve Google Workspace groups");
    }

    private static Collection<GrantedAuthority> googleGroups(String cloudIdentityResponse) {
        var converter = OpaqueTokenCustomGrantedAuthoritiesConverters.converterFor(
                OpaqueTokenCustomGrantedAuthoritiesConverters.GOOGLE_WORKSPACE_GROUPS);
        RestClient stub = RestClient.builder()
                .requestFactory(new StubbedJsonRequestFactory(cloudIdentityResponse))
                .build();
        var context = new OpaqueAuthorityExtractionContext(
                stub, "token", Map.of("email", EMAIL), Set.of("email"));
        return converter.apply(context);
    }

    /**
     * Answers any request with the same JSON body. The converter targets Google's Cloud Identity host by a
     * hard-coded URL, so it cannot be pointed at a local WireMock the way a configurable endpoint can;
     * stubbing at the request-factory layer is what lets the response shapes be exercised at all.
     */
    private record StubbedJsonRequestFactory(String body) implements ClientHttpRequestFactory {

        @Override
        public @NonNull ClientHttpRequest createRequest(@NonNull URI uri, @NonNull HttpMethod httpMethod) {
            MockClientHttpRequest request = new MockClientHttpRequest(httpMethod, uri);
            MockClientHttpResponse response =
                    new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            request.setResponse(response);
            return request;
        }
    }
}
