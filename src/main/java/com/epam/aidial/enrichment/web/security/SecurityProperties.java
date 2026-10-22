package com.epam.aidial.enrichment.web.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the scalar authentication configuration ({@code security.*}). Values are
 * env-driven via {@code application.yml}; property descriptions live in {@code docs/configuration.md}.
 * Identity providers live in a separate top-level map — see {@link IdentityProvidersProperties}.
 *
 * <p>This holder is bound in every mode (it is harmless when {@code mode} is {@code none}).
 *
 * @param mode         security mode: {@code oidc} (enforce Bearer-JWT auth) or {@code none} (open)
 * @param requireEmail when {@code true}, a token without a resolvable email claim is rejected
 * @param disableSwaggerAuthorization when {@code true}, the OpenAPI description and the Swagger UI are served
 *                     without a credential in {@code oidc} mode; otherwise they require {@code FULL_ADMIN}.
 *                     Ignored in {@code none} mode, where every path is open
 * @param defaults     fallback claim names and roles mapping applied to providers that omit their own
 */
@ConfigurationProperties(prefix = "security")
public record SecurityProperties(
        String mode,
        boolean requireEmail,
        boolean disableSwaggerAuthorization,
        Defaults defaults) {

    /** The security mode that permits every request without authenticating it — the default. */
    public static final String MODE_NONE = "none";

    /** The security mode that enforces authentication and role-based authorization. */
    public static final String MODE_OIDC = "oidc";

    /**
     * Rejects any mode value other than the two canonical lowercase ones, so a mistyped or
     * differently-cased {@code SECURITY_MODE} fails startup instead of producing a service that looks
     * configured and is not. Two failure modes make this worth failing on rather than tolerating:
     *
     * <ul>
     *   <li>A value neither condition matches (a typo, or an <em>empty</em> value — {@code matchIfMissing}
     *       covers an absent property, not a blank one) activates neither {@link NoneSecurityConfiguration}
     *       nor {@link OidcSecurityConfiguration}. Spring Boot's default chain then takes over and demands
     *       HTTP Basic on every path, health probes included, which an orchestrator resolves by restarting
     *       the container forever.</li>
     *   <li>A differently-cased value such as {@code OIDC} matches the {@code @ConditionalOnProperty}
     *       guards, which compare case-insensitively, but not
     *       {@link com.epam.aidial.enrichment.web.security.apikey.ApiKeyProperties#ACTIVE_CONDITION},
     *       whose SpEL comparison is case-sensitive. Authentication would be enforced while every
     *       API-key bean silently vanished, refusing each {@code Api-Key} caller with a 401.</li>
     * </ul>
     *
     * <p>Normalizing the case here would not fix the second case: the SpEL condition reads the raw
     * property, not this record.
     *
     * <p>{@code null} is accepted because that is the property-absent case, the one where
     * {@code matchIfMissing = true} legitimately selects {@code none}.
     */
    public SecurityProperties {
        if (mode != null && !MODE_NONE.equals(mode) && !MODE_OIDC.equals(mode)) {
            throw new IllegalStateException(
                    "Invalid security mode '" + mode + "'. SECURITY_MODE must be exactly '" + MODE_NONE
                            + "' or '" + MODE_OIDC + "', lowercase; any other value leaves the service with "
                            + "neither security configuration active.");
        }
    }

    /**
     * Service-wide fallbacks used when a provider does not specify its own values.
     *
     * @param rolesMapping  JSON object mapping provider role names to lists of {@link UserRole}, e.g.
     *                      {@code {"admin":["FULL_ADMIN"]}}; merged under each provider's own mapping
     * @param emailClaim    default claim name to read the caller's email from
     * @param principalClaim default claim name to use as the authenticated principal name
     */
    public record Defaults(
            String rolesMapping,
            String emailClaim,
            String principalClaim) {
    }
}
