package com.epam.aidial.enrichment.web.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Introspects an opaque (non-JWT) bearer token by calling a single provider's OIDC userinfo endpoint
 * with the token, then turning the returned claims into an authenticated principal. Provider role
 * names are extracted from the userinfo claims using the configured dotted paths — or, when the
 * provider's {@code role-claims} name a custom converter ({@code fn:...}), by that converter instead
 * (see {@link OpaqueTokenCustomGrantedAuthoritiesConverters}); mapping onto application roles happens
 * later in {@link OpaqueAuthenticationConverter}. The originating provider's name is recorded under the
 * {@link #IDP_CLAIM} attribute so that converter can be selected per provider.
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultOpaqueTokenIntrospector implements OpaqueTokenIntrospector {

    /** Synthetic attribute holding the local provider name, used to pick the per-provider converter. */
    public static final String IDP_CLAIM = "idp";

    private static final ParameterizedTypeReference<Map<String, Object>> STRING_OBJECT_MAP =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final String providerName;
    private final String userInfoEndpoint;
    private final MultiPathGrantedAuthoritiesConverter<ClaimAccessor> authoritiesConverter;
    private final String principalClaim;
    private final List<String> roleClaims;
    private final Set<String> emailClaims;

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        Map<String, Object> attributes = fetchUserInfo(token);

        Collection<GrantedAuthority> authorities = extractAuthorities(token, attributes);

        Map<String, Object> enriched = new HashMap<>(attributes);
        enriched.put(IDP_CLAIM, providerName);

        // The null guard is this class's only divergence from the otherwise verbatim ADAS port, and it
        // changes no behaviour: getPrincipalClaim() returns null when neither the provider nor
        // SECURITY_USER_CLAIM names one, and ADAS's bare get(null) is safe on the LinkedHashMap Jackson
        // returns. It states the case rather than leaving it to the map implementation.
        Object principal = principalClaim != null ? attributes.get(principalClaim) : null;
        String name = principal != null ? principal.toString() : providerName;
        return new OAuth2IntrospectionAuthenticatedPrincipal(name, enriched, authorities);
    }

    /**
     * Roles either come from the userinfo claims via the configured dotted paths, or — when the
     * provider selected a custom {@code fn:} converter — from that converter, which may call a further
     * API on the caller's behalf. An unknown converter name is a configuration error; it fails the
     * introspection rather than silently granting no roles. Startup already rejects such a provider, so
     * reaching this branch means the registry and the validation disagree.
     */
    private Collection<GrantedAuthority> extractAuthorities(String token, Map<String, Object> attributes) {
        if (!OpaqueTokenCustomGrantedAuthoritiesConverters.isCustomConverterSelection(roleClaims)) {
            ClaimAccessor claims = () -> attributes;
            return authoritiesConverter.convert(claims);
        }

        String converterName = roleClaims.getFirst();
        var converter = OpaqueTokenCustomGrantedAuthoritiesConverters.converterFor(converterName);
        if (converter == null) {
            log.debug("No custom granted-authorities converter '{}' for provider {}", converterName, providerName);
            throw new OAuth2IntrospectionException("Unknown custom granted-authorities converter: " + converterName);
        }
        return converter.apply(new OpaqueAuthorityExtractionContext(restClient, token, attributes, emailClaims));
    }

    private Map<String, Object> fetchUserInfo(String token) {
        try {
            Map<String, Object> body = restClient.get()
                    .uri(userInfoEndpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(STRING_OBJECT_MAP);
            if (body == null) {
                throw new OAuth2IntrospectionException("Empty userinfo response from provider " + providerName);
            }
            return body;
        } catch (OAuth2IntrospectionException ex) {
            throw ex;
        } catch (Exception ex) {
            log.debug("Userinfo introspection failed for provider {}", providerName, ex);
            throw new OAuth2IntrospectionException("Userinfo introspection failed for provider " + providerName, ex);
        }
    }
}
