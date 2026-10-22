package com.epam.aidial.enrichment.web.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenAuthenticationConverter;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the authentication token from an introspected opaque-token principal: resolves the caller's
 * email, maps the introspected provider roles onto application authorities, and wraps everything in a
 * {@link BearerTokenAuthentication}. A valid token is always authenticated; whether it may reach a given
 * endpoint is decided by the URL rules, which require {@link UserRole#FULL_ADMIN} on every functional
 * endpoint.
 *
 * <p>As on the JWT path, and unlike ADAS, the token carries its mapped application roles only. ADAS also
 * carries each raw provider role name as a prefixed authority so per-table access can match on it; the
 * runner has no per-target authorization, so those authorities would be unreachable state. See
 * {@link JwtAuthenticationConverter}, which made the same call.
 */
@Slf4j
@RequiredArgsConstructor
public class OpaqueAuthenticationConverter implements OpaqueTokenAuthenticationConverter {

    private final UserRolesResolver userRolesResolver;
    private final Set<String> emailClaims;
    private final boolean requireEmail;

    @Override
    public Authentication convert(String introspectedToken, OAuth2AuthenticatedPrincipal authenticatedPrincipal) {
        String providerName = authenticatedPrincipal.getAttribute(DefaultOpaqueTokenIntrospector.IDP_CLAIM);
        String email = extractEmail(authenticatedPrincipal.getAttributes());

        if (requireEmail && email == null) {
            // An invalid-token exception, not ADAS's AuthenticationServiceException, matching what the
            // runner's JWT path already does: the token is missing a required claim, which is the caller's
            // problem, not a failure of this service. Both render 401 through the entry point, but the
            // distinction matters where the two are told apart — the api-key filter maps a service
            // exception to 503.
            throw new InvalidBearerTokenException("Email claim is required");
        }

        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, introspectedToken, null, null);
        UserSecurityDetails details = new UserSecurityDetails(email);

        Collection<? extends GrantedAuthority> rawAuthorities = authenticatedPrincipal.getAuthorities();
        Collection<GrantedAuthority> appRoles = userRolesResolver.resolve(rawAuthorities);

        BearerTokenAuthentication authentication =
                new BearerTokenAuthentication(authenticatedPrincipal, accessToken, List.copyOf(appRoles));
        authentication.setDetails(details);

        if (appRoles.isEmpty()) {
            log.debug("Opaque token for idp {} maps to no application role; it will be denied by the URL rules",
                    providerName);
        }
        log.trace("Authorization state - idp: {}, authorities: {}", providerName, appRoles);

        return authentication;
    }

    private String extractEmail(Map<String, Object> attributes) {
        for (String claim : emailClaims) {
            Object value = attributes.get(claim);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }
}
