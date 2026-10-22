package com.epam.aidial.enrichment.web.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trying each opaque provider in turn. An opaque token carries no issuer, so there is nothing to select a
 * provider by — the only way to find the one that owns a token is to offer it to each until one accepts.
 */
class MultiIssuerOpaqueTokenIntrospectorTest {

    private static final String TOKEN = "opaque-token";

    private static OpaqueTokenIntrospector accepting(String providerName) {
        return token -> new OAuth2IntrospectionAuthenticatedPrincipal(
                providerName, Map.of(DefaultOpaqueTokenIntrospector.IDP_CLAIM, providerName), List.of());
    }

    private static OpaqueTokenIntrospector rejecting() {
        return token -> {
            throw new OAuth2IntrospectionException("not mine");
        };
    }

    @Test
    void tokenTheFirstProviderRejectsIsOfferedToTheNext() {
        var introspector = new MultiIssuerOpaqueTokenIntrospector(List.of(rejecting(), accepting("second")));

        OAuth2AuthenticatedPrincipal principal = introspector.introspect(TOKEN);

        assertThat(principal.<String>getAttribute(DefaultOpaqueTokenIntrospector.IDP_CLAIM)).isEqualTo("second");
    }

    /** The first acceptance wins, so a later provider is never contacted for a token already claimed. */
    @Test
    void stopsAtTheFirstProviderThatAccepts() {
        var introspector = new MultiIssuerOpaqueTokenIntrospector(List.of(accepting("first"), token -> {
            throw new AssertionError("must not be reached once a provider has accepted the token");
        }));

        assertThat(introspector.introspect(TOKEN).getName()).isEqualTo("first");
    }

    /**
     * A bad-token exception specifically, not a generic introspection one: Spring's
     * {@code OpaqueTokenAuthenticationProvider} renders the former as 401 and the latter as 503, and the
     * runner's contract is that a token no provider accepts is a rejected credential.
     */
    @Test
    void tokenNoProviderAcceptsIsRejected() {
        var introspector = new MultiIssuerOpaqueTokenIntrospector(List.of(rejecting(), rejecting()));

        assertThatThrownBy(() -> introspector.introspect(TOKEN)).isInstanceOf(BadOpaqueTokenException.class);
    }

    /** The shape of a deployment configuring JWT providers only: every opaque credential is rejected. */
    @Test
    void rejectsEveryTokenWhenNoProviderIsConfigured() {
        var introspector = new MultiIssuerOpaqueTokenIntrospector(List.of());

        assertThatThrownBy(() -> introspector.introspect(TOKEN)).isInstanceOf(BadOpaqueTokenException.class);
    }
}
