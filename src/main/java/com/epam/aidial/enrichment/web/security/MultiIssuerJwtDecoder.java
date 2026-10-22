package com.epam.aidial.enrichment.web.security;

import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.text.ParseException;
import java.util.Map;

/**
 * A {@link JwtDecoder} that supports several identity providers at once: it reads the {@code iss}
 * claim from the (still unverified) token and dispatches to the issuer-specific decoder, which then
 * verifies the signature and claims. Tokens whose issuer is not configured are rejected rather than
 * accepted unverified.
 */
@Slf4j
public class MultiIssuerJwtDecoder implements JwtDecoder {

    private final Map<String, JwtDecoder> issuerToDecoderMap;

    public MultiIssuerJwtDecoder(Map<String, JwtDecoder> issuerToDecoderMap) {
        this.issuerToDecoderMap = Map.copyOf(issuerToDecoderMap);
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        String issuer = getIssuer(token);
        JwtDecoder decoder = issuerToDecoderMap.get(issuer);
        if (decoder == null) {
            // Debug rather than warn, for the reason MultiPathGrantedAuthoritiesConverter and
            // UserRolesResolver already give: this fires once per request for a token the runner cannot
            // place, and every JWS-shaped credential now reaches here — an opaque-only deployment has an
            // empty map — so warning lets an unauthenticated caller set the log volume. Which providers
            // are configured is on the startup posture line; the per-token reason is one level away.
            log.debug("Rejecting token from unrecognized issuer: {}", issuer);
            throw new InvalidBearerTokenException("Unrecognized issuer: " + issuer);
        }
        return decoder.decode(token);
    }

    private static String getIssuer(String token) {
        try {
            return SignedJWT.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            throw new InvalidBearerTokenException("Malformed JWT token", e);
        }
    }
}
