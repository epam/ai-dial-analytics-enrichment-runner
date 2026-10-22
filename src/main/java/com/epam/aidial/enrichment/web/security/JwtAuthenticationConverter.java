package com.epam.aidial.enrichment.web.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Converts a verified {@link Jwt} into an authentication token: it resolves the caller's email and
 * principal name from the configured claims, extracts provider roles, and maps them onto application
 * authorities. A valid token is always authenticated; whether it may reach a given endpoint is decided
 * by the URL rules, which require {@link UserRole#FULL_ADMIN} on every functional endpoint.
 *
 * <p>Unlike ADAS the token carries its mapped application roles only. ADAS also carries each raw
 * provider role name as a prefixed authority so per-table access can match on it; the runner has no
 * per-target authorization, so those authorities would be unreachable state.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final Converter<Jwt, Collection<GrantedAuthority>> jwtGrantedAuthoritiesConverter;
    private final UserRolesResolver userRolesResolver;
    private final String principalClaim;
    private final Set<String> emailClaims;
    private final boolean requireEmail;

    @Override
    public AbstractAuthenticationToken convert(@NonNull Jwt jwt) {
        String email = extractEmail(jwt);
        if (requireEmail && email == null) {
            // An invalid-token exception, not ADAS's AuthenticationServiceException: the token is missing a
            // required claim, which is the caller's problem, not a failure of this service. Both render 401
            // through the entry point, but the distinction matters where the two are told apart — the
            // api-key filter maps a service exception to 503.
            throw new InvalidBearerTokenException("Email claim is required");
        }

        UserSecurityDetails details = new UserSecurityDetails(email);
        String issuer = jwt.getIssuer().toString();
        Collection<GrantedAuthority> rawAuthorities = jwtGrantedAuthoritiesConverter.convert(jwt);
        Collection<GrantedAuthority> appRoles = userRolesResolver.resolve(rawAuthorities);
        String principalClaimValue = jwt.getClaimAsString(principalClaim);

        JwtAuthenticationToken authToken =
                new JwtAuthenticationToken(jwt, List.copyOf(appRoles), principalClaimValue);
        authToken.setDetails(details);

        if (appRoles.isEmpty()) {
            log.debug("JWT for issuer {} maps to no application role; it will be denied by the URL rules",
                    issuer);
        }
        log.trace("Authorization state - issuer: {}, authorities: {}", issuer, appRoles);

        return authToken;
    }

    private String extractEmail(Jwt jwt) {
        for (String claim : emailClaims) {
            Object value = jwt.getClaims().get(claim);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }
}
