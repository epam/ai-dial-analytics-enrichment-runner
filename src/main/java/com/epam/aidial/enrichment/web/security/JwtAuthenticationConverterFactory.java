package com.epam.aidial.enrichment.web.security;

import com.epam.aidial.enrichment.web.security.IdentityProvidersProperties.ProviderConfig;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds one {@link JwtAuthenticationConverter} per accepted issuer, each configured with that
 * provider's role-claim paths, roles mapping, principal claim and email claims. After a token is
 * decoded, the converter for its issuer turns it into an authentication token.
 */
public class JwtAuthenticationConverterFactory {

    private final IdentityProviderUtils identityProviderUtils;
    private final Map<String, JwtAuthenticationConverter> convertersByIssuer;

    public JwtAuthenticationConverterFactory(Map<String, ProviderConfig> providers,
                                             IdentityProviderUtils identityProviderUtils) {
        this.identityProviderUtils = identityProviderUtils;
        Map<String, JwtAuthenticationConverter> tmpConvertersByIssuer = new HashMap<>();
        providers.forEach((name, config) -> {
            var converter = create(config);
            var acceptedIssuers = this.identityProviderUtils.getAcceptedIssuers(config);
            for (var issuer : acceptedIssuers) {
                tmpConvertersByIssuer.put(issuer, converter);
            }
        });
        convertersByIssuer = Map.copyOf(tmpConvertersByIssuer);
    }

    private JwtAuthenticationConverter create(ProviderConfig config) {
        var grantedAuthoritiesConverter = new MultiPathGrantedAuthoritiesConverter<Jwt>();
        var authoritiesPaths = identityProviderUtils.getRoleClaims(config).stream()
                .map(String::trim)
                .toList();
        grantedAuthoritiesConverter.setAuthoritiesPaths(authoritiesPaths);
        grantedAuthoritiesConverter.setAuthorityPrefix("");

        var grantedAuthoritiesTransformer = new UserRolesResolver(
                identityProviderUtils.getRolesMapping(config)
        );

        return new JwtAuthenticationConverter(
                grantedAuthoritiesConverter,
                grantedAuthoritiesTransformer,
                identityProviderUtils.getPrincipalClaim(config),
                identityProviderUtils.getEmailClaims(config),
                identityProviderUtils.isEmailRequired()
        );
    }

    public JwtAuthenticationConverter getConverter(String issuer) {
        return convertersByIssuer.get(issuer);
    }
}
