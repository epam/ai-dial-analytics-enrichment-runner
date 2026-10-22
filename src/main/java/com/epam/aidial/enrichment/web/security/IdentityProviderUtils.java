package com.epam.aidial.enrichment.web.security;

import com.epam.aidial.enrichment.web.security.IdentityProvidersProperties.ProviderConfig;
import com.epam.aidial.enrichment.web.security.SecurityProperties.Defaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Resolves per-provider authentication settings from {@link IdentityProvidersProperties}, applying the
 * service-wide {@link SecurityProperties} defaults where a provider omits its own values. Created only
 * in {@code oidc} mode by {@link OidcSecurityConfiguration}; the constructor fails fast when no usable
 * provider is configured, so an {@code oidc} deployment cannot start silently accepting no credential.
 */
@Slf4j
public class IdentityProviderUtils {

    private static final TypeReference<Map<String, Set<UserRole>>> ROLES_MAPPING_TYPE = new TypeReference<>() {
    };
    private static final String V1_ISSUER_FORMAT = "https://%s/%s/";
    private static final String V2_ISSUER_FORMAT = "https://%s/%s/v2.0";

    private final Map<String, Set<UserRole>> defaultRolesMapping;
    private final String defaultEmailClaim;
    private final String defaultPrincipalClaim;
    private final boolean requireEmail;
    private final JsonMapper jsonMapper;

    public IdentityProviderUtils(JsonMapper jsonMapper,
                                 SecurityProperties securityProperties,
                                 IdentityProvidersProperties providersProperties) {
        this.jsonMapper = jsonMapper;
        this.requireEmail = securityProperties.requireEmail();

        Defaults defaults = securityProperties.defaults();
        this.defaultEmailClaim = defaults != null ? defaults.emailClaim() : null;
        this.defaultPrincipalClaim = defaults != null ? defaults.principalClaim() : null;
        this.defaultRolesMapping = parseRolesMapping(defaults != null ? defaults.rolesMapping() : null);

        Map<String, ProviderConfig> usable = usableProviders(providersProperties);
        if (usable.isEmpty()) {
            throw new IllegalStateException(
                    "Security mode is '" + SecurityProperties.MODE_OIDC + "' but no usable identity providers are "
                            + "configured (each provider needs either an issuer and a jwk-set-uri to verify JWTs "
                            + "locally, or a user-info-endpoint to introspect opaque tokens)");
        }
        usable.forEach(this::validateProvider);
        validateIssuersAreDistinct(jwtProviders(providersProperties));
    }

    /**
     * Rejects two JWT providers accepting the same issuer. Both the decoder lookup table and the converter
     * lookup table are keyed by issuer, so one provider would silently overwrite the other — and which one
     * survives depends on map iteration order. The caller would then be verified against one provider's JWKS
     * and have its roles mapped by that same provider's mapping, neither of which is necessarily the one the
     * operator meant.
     */
    private void validateIssuersAreDistinct(Map<String, ProviderConfig> jwtProviders) {
        Map<String, String> providerByIssuer = new HashMap<>();
        jwtProviders.forEach((name, config) -> getAcceptedIssuers(config).forEach(issuer -> {
            String previous = providerByIssuer.put(issuer, name);
            if (previous != null) {
                throw new IllegalStateException(
                        "Identity providers '" + previous + "' and '" + name + "' both accept the issuer '" + issuer
                                + "'. Issuer values must be unique across providers, because the issuer is what "
                                + "selects the JWKS and roles mapping a token is verified and mapped with.");
            }
        }));
    }

    /**
     * Rejects a provider that satisfies {@link ProviderConfig#isUsable()} — it can verify a token by one
     * route or the other — but is nonetheless incapable of admitting anyone. Each case below starts
     * cleanly and then refuses every caller uniformly, with nothing but a per-request log line to say why,
     * which is the hardest kind of misconfiguration to find. Failing startup here matches what
     * {@code ApiKeyProperties.validate()} already does for the equivalent API-key mistake.
     *
     * <p>Checks branch on the provider's kind, because the two kinds validate a token differently. Issuer
     * and audience are meaningful only where a token is verified locally: an opaque token carries no issuer
     * to match on, and there is no local {@code aud} check to feed. Requiring them of an opaque provider
     * would reject the very ADAS-shaped configuration this service exists to accept unchanged.
     *
     * <p>Deliberately not folded into {@code isUsable()}: that predicate mirrors ADAS's own notion of a
     * provider that can verify a token, and widening it would shift the meaning of the shared name.
     */
    private void validateProvider(String name, ProviderConfig config) {
        if (config.isJwtProvider()) {
            validateJwtProvider(name, config);
        }
        if (isAllBlank(config.roleClaims())) {
            throw new IllegalStateException(
                    "Identity provider '" + name + "' declares no role claims (providers." + name + ".role-claims). "
                            + "No provider role could be read from a token, so every caller would authenticate and "
                            + "then be refused with 403.");
        }
        validateCustomAuthoritiesConverter(name, config);
    }

    private void validateJwtProvider(String name, ProviderConfig config) {
        if (getAcceptedIssuers(config).isEmpty()) {
            throw new IllegalStateException(
                    "Identity provider '" + name + "' accepts no issuer: providers." + name + ".issuer is '"
                            + config.issuer() + "', which is not a URL, and providers." + name + ".aliases is empty. "
                            + "A tenant id needs at least one alias to expand into issuer URLs; otherwise the "
                            + "provider matches no token and every request is rejected with 401.");
        }
        if (isAllBlank(config.audiences())) {
            throw new IllegalStateException(
                    "Identity provider '" + name + "' declares no audiences (providers." + name + ".audiences). "
                            + "A token's 'aud' claim is matched against that list, so an empty one rejects every "
                            + "token with 401.");
        }
    }

    /**
     * A provider whose {@code role-claims} select a custom converter ({@code fn:...}) must name one that
     * exists, and must be a provider that introspects opaque tokens. Either mistake refuses every caller of
     * that provider at request time, so fail at startup instead, where the misconfiguration is visible.
     * ADAS makes the unknown-name check in its {@code oidc} wiring; here it sits beside the other
     * provider-completeness checks, so there is one place to look for why a provider was rejected.
     *
     * <p>The kind check has no ADAS counterpart because ADAS only ever reaches this code for an opaque
     * provider. A converter derives roles by calling an API with the caller's <em>token</em> during
     * introspection, which the JWT path has no step for: there,
     * {@link MultiPathGrantedAuthoritiesConverter} would read {@code fn:...} as a dotted claim path,
     * match nothing, and hand back no role — a provider that starts cleanly and then refuses every caller
     * with 403.
     */
    private void validateCustomAuthoritiesConverter(String name, ProviderConfig config) {
        List<String> trimmed = getRoleClaims(config).stream().map(String::trim).toList();
        if (!OpaqueTokenCustomGrantedAuthoritiesConverters.isCustomConverterSelection(trimmed)) {
            return;
        }
        if (!config.isOpaqueProvider()) {
            throw new IllegalStateException(
                    "Identity provider '" + name + "' selects custom role converter '" + trimmed.getFirst()
                            + "' (providers." + name + ".role-claims) but declares no providers." + name
                            + ".user-info-endpoint. Custom role converters derive roles during opaque-token "
                            + "introspection, so a provider verifying JWTs locally never invokes one and would "
                            + "read the converter name as a claim path that matches nothing, refusing every "
                            + "caller with 403. Give the provider a user-info-endpoint, or configure role "
                            + "claim paths instead.");
        }
        if (OpaqueTokenCustomGrantedAuthoritiesConverters.converterFor(trimmed.getFirst()) == null) {
            throw new IllegalStateException(
                    "Identity provider '" + name + "' selects unknown custom role converter '" + trimmed.getFirst()
                            + "' (providers." + name + ".role-claims); known converters: "
                            + OpaqueTokenCustomGrantedAuthoritiesConverters.CONVERTERS.keySet());
        }
    }

    private static boolean isAllBlank(List<String> values) {
        return values == null || values.stream().noneMatch(StringUtils::isNotBlank);
    }

    /** The providers that can verify a token by either route, keyed by their local configuration name. */
    public static Map<String, ProviderConfig> usableProviders(IdentityProvidersProperties properties) {
        return select(properties, ProviderConfig::isUsable);
    }

    /** The providers that verify a JWT locally against their JWKS, keyed by their local configuration name. */
    public static Map<String, ProviderConfig> jwtProviders(IdentityProvidersProperties properties) {
        return select(properties, ProviderConfig::isJwtProvider);
    }

    /** The providers that introspect an opaque token against their userinfo endpoint, keyed by local name. */
    public static Map<String, ProviderConfig> opaqueProviders(IdentityProvidersProperties properties) {
        return select(properties, ProviderConfig::isOpaqueProvider);
    }

    private static Map<String, ProviderConfig> select(IdentityProvidersProperties properties,
                                                      Predicate<ProviderConfig> predicate) {
        Map<String, ProviderConfig> result = new HashMap<>();
        if (properties.providers() != null) {
            properties.providers().forEach((name, config) -> {
                if (predicate.test(config)) {
                    result.put(name, config);
                }
            });
        }
        return result;
    }

    /**
     * Expands the accepted issuer values for a provider. A provider with a fully-qualified issuer URL
     * accepts exactly that issuer; a provider configured with a tenant id plus {@code aliases} accepts
     * the Azure v1 and v2 issuer URL forms for each alias.
     */
    public Set<String> getAcceptedIssuers(ProviderConfig config) {
        Set<String> acceptedIssuers = new HashSet<>();
        String issuer = config.issuer();
        if (isValidUrlWithProtocol(issuer)) {
            acceptedIssuers.add(issuer);
        } else if (config.aliases() != null && !config.aliases().isEmpty()) {
            for (String alias : config.aliases()) {
                acceptedIssuers.add(String.format(V1_ISSUER_FORMAT, alias, issuer));
                acceptedIssuers.add(String.format(V2_ISSUER_FORMAT, alias, issuer));
            }
        }
        return acceptedIssuers;
    }

    /** Merges the service-wide default roles mapping with the provider's own (provider entries win). */
    public Map<String, Set<UserRole>> getRolesMapping(ProviderConfig config) {
        Map<String, Set<UserRole>> providerMapping = parseRolesMapping(config.rolesMapping());
        if (providerMapping.isEmpty()) {
            return defaultRolesMapping;
        }
        Map<String, Set<UserRole>> merged = new HashMap<>(defaultRolesMapping);
        merged.putAll(providerMapping);
        return merged;
    }

    /** The claim names to read the caller's email from: the provider's, else the default if any. */
    public Set<String> getEmailClaims(ProviderConfig config) {
        Set<String> result = new LinkedHashSet<>();
        if (config.emailClaims() != null && !config.emailClaims().isEmpty()) {
            result.addAll(config.emailClaims());
        } else if (StringUtils.isNotBlank(defaultEmailClaim)) {
            result.add(defaultEmailClaim);
        }
        return result;
    }

    /** The dotted claim paths to read provider role names from. */
    public List<String> getRoleClaims(ProviderConfig config) {
        return config.roleClaims() != null ? config.roleClaims() : List.of();
    }

    /** The claim used as the authenticated principal name: the provider's, else the default. */
    public String getPrincipalClaim(ProviderConfig config) {
        String principalClaim = config.principalClaim();
        return StringUtils.isNotBlank(principalClaim) ? principalClaim : defaultPrincipalClaim;
    }

    public boolean isEmailRequired() {
        return requireEmail;
    }

    private Map<String, Set<UserRole>> parseRolesMapping(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        return jsonMapper.readValue(json, ROLES_MAPPING_TYPE);
    }

    private static boolean isValidUrlWithProtocol(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        try {
            return new URI(value).getScheme() != null;
        } catch (URISyntaxException e) {
            log.debug("Invalid issuer url format: {}", value, e);
            return false;
        }
    }
}
