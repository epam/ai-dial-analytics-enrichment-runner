package com.epam.aidial.enrichment.web.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;

/**
 * Validates the {@code iss} and {@code aud} claims of a decoded JWT against the set accepted for a
 * provider. Signature and expiry are validated separately (by the Nimbus decoder and a
 * {@link org.springframework.security.oauth2.jwt.JwtTimestampValidator}); this validator covers the
 * issuer and audience binding.
 */
@Slf4j
public class TokenClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private static final String ERROR_CODE = "invalid_token";

    private final Set<String> acceptedIssuers;
    private final Set<String> acceptedAudiences;

    public TokenClaimsValidator(Set<String> acceptedIssuers, Set<String> acceptedAudiences) {
        this.acceptedIssuers = Set.copyOf(acceptedIssuers);
        this.acceptedAudiences = Set.copyOf(acceptedAudiences);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getClaims() == null || jwt.getClaims().isEmpty()) {
            return failure("token must contain claims");
        }
        if (!jwt.hasClaim("iss")) {
            return failure("token must contain an issuer (iss) claim");
        }
        if (!jwt.hasClaim("aud")) {
            return failure("token must contain an audience (aud) claim");
        }
        if (acceptedAudiences.stream().noneMatch(jwt.getAudience()::contains)) {
            return failure("invalid audience (aud) claim: " + jwt.getAudience());
        }
        if (acceptedIssuers.stream().noneMatch(issuer -> issuer.equals(jwt.getIssuer().toString()))) {
            return failure("invalid issuer (iss) claim: " + jwt.getIssuer());
        }
        return OAuth2TokenValidatorResult.success();
    }

    /**
     * Debug, not warn: any caller holding a token signed by a configured issuer chooses whether this fires,
     * so a higher level would let that caller set the runner's log volume.
     */
    private OAuth2TokenValidatorResult failure(String description) {
        log.debug("JWT token validation failed: {}", description);
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(ERROR_CODE, description, null));
    }
}
