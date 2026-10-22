package com.epam.aidial.enrichment.web.security;

import com.epam.aidial.enrichment.web.security.IdentityProvidersProperties.ProviderConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the issuer-to-decoder entries for a single provider: one {@link NimbusJwtDecoder} (with
 * issuer/audience and expiry validators attached) registered under each issuer the provider accepts.
 * The per-provider maps are merged into the {@link MultiIssuerJwtDecoder}'s lookup table.
 */
@RequiredArgsConstructor
public class IssuerToDecoderMapFactory {

    private static final String AUDIENCE_PREFIX = "api://";

    private final IdentityProviderUtils identityProviderUtils;
    private final NimbusJwtDecoderResolver nimbusJwtDecoderResolver;

    public Map<String, JwtDecoder> createIssuerToDecoderMap(ProviderConfig config) {
        NimbusJwtDecoder jwtDecoder = nimbusJwtDecoderResolver.resolve(config);
        Map<String, JwtDecoder> issuerToDecoderMap = new HashMap<>();
        Set<String> acceptedIssuers = identityProviderUtils.getAcceptedIssuers(config);
        for (String issuer : acceptedIssuers) {
            issuerToDecoderMap.put(issuer, jwtDecoder);
        }
        addTokenDecoderValidators(jwtDecoder, acceptedIssuers, getAcceptedAudiences(config.audiences()));

        return issuerToDecoderMap;
    }

    private void addTokenDecoderValidators(NimbusJwtDecoder jwtDecoder,
                                           Set<String> allAcceptedIssuers,
                                           Set<String> allAcceptedAudiences) {
        var claimsValidator = new TokenClaimsValidator(allAcceptedIssuers, allAcceptedAudiences);
        var expirationDateValidator = new JwtTimestampValidator();
        var tokenValidator = new DelegatingOAuth2TokenValidator<>(claimsValidator, expirationDateValidator);
        jwtDecoder.setJwtValidator(tokenValidator);
    }

    private Set<String> getAcceptedAudiences(List<String> acceptedAudiences) {
        Set<String> allAcceptedAudiences = new HashSet<>();
        if (acceptedAudiences != null) {
            for (String audience : acceptedAudiences) {
                allAcceptedAudiences.add(audience);
                allAcceptedAudiences.add(AUDIENCE_PREFIX + audience);
            }
        }
        return allAcceptedAudiences;
    }
}
