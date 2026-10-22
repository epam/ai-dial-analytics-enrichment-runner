package com.epam.aidial.enrichment.web.security;

import com.epam.aidial.enrichment.web.security.IdentityProvidersProperties.ProviderConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds one {@link OpaqueAuthenticationConverter} per opaque provider, keyed by the local provider
 * name. After introspection, the converter for the provider that accepted the token (identified by the
 * {@link DefaultOpaqueTokenIntrospector#IDP_CLAIM} attribute) turns the principal into an authentication
 * token with that provider's roles mapping and email claims.
 */
public class OpaqueAuthenticationConverterFactory {

    private final IdentityProviderUtils identityProviderUtils;
    private final Map<String, OpaqueAuthenticationConverter> convertersByProviderName;

    public OpaqueAuthenticationConverterFactory(Map<String, ProviderConfig> opaqueProviders,
                                                IdentityProviderUtils identityProviderUtils) {
        this.identityProviderUtils = identityProviderUtils;
        Map<String, OpaqueAuthenticationConverter> tmpConvertersByProviderName = new HashMap<>();
        opaqueProviders.forEach((name, config) -> tmpConvertersByProviderName.put(name, create(config)));
        convertersByProviderName = Map.copyOf(tmpConvertersByProviderName);
    }

    private OpaqueAuthenticationConverter create(ProviderConfig config) {
        var grantedAuthoritiesTransformer = new UserRolesResolver(
                identityProviderUtils.getRolesMapping(config)
        );

        return new OpaqueAuthenticationConverter(
                grantedAuthoritiesTransformer,
                identityProviderUtils.getEmailClaims(config),
                identityProviderUtils.isEmailRequired()
        );
    }

    public OpaqueAuthenticationConverter getConverter(String providerName) {
        return convertersByProviderName.get(providerName);
    }
}
