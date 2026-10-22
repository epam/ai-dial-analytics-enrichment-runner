package com.epam.aidial.enrichment.web.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed binding for opaque-token introspection ({@code security.opaque-token.*}); property descriptions
 * live in {@code docs/configuration.md}. Bound in every mode and read only by the {@code oidc} wiring.
 *
 * <p>Has no ADAS counterpart. ADAS reintrospects on every request with an untimed client; the runner's
 * callers are operational tooling polling the same endpoints on a loop, and a converter-derived provider
 * costs two outbound calls per miss — so introspections are cached and the call is bounded.
 *
 * @param cacheTtlSeconds  how long a successful introspection stays valid; a revoked token stops working
 *                         within this window. {@code 0} disables caching, which is how an operator
 *                         needing immediate revocation configures it
 * @param cacheMaxSize     maximum number of cached introspections
 * @param requestTimeoutMs connect and read timeout for the userinfo call; must be positive
 */
@ConfigurationProperties(prefix = "security.opaque-token")
public record OpaqueTokenProperties(
        @DefaultValue("60") int cacheTtlSeconds,
        @DefaultValue("10000") int cacheMaxSize,
        @DefaultValue("3000") int requestTimeoutMs) {

    /**
     * Rejects values that would leave the introspection path worse off than the defaults, naming the
     * variable to set — as {@code SecurityProperties} does for {@code SECURITY_MODE}. Without this, a
     * negative value fails startup with an {@link IllegalArgumentException} out of {@code Duration} or
     * Caffeine that names neither the property nor the variable.
     *
     * <p>{@code 0} is legal for the TTL and illegal for the timeout, because the two read it differently:
     * a zero TTL expires an entry as it is written, which is the documented way to turn caching off, while
     * {@code SimpleClientHttpRequestFactory} reads a zero timeout as <em>no</em> timeout — reinstating the
     * held-serving-thread failure the timeout exists to prevent, behind a value that reads like the
     * opposite.
     */
    public OpaqueTokenProperties {
        if (cacheTtlSeconds < 0) {
            throw new IllegalStateException(
                    "OPAQUE_TOKEN_CACHE_TTL_SECONDS (security.opaque-token.cache-ttl-seconds) must not be "
                            + "negative; it is " + cacheTtlSeconds + ". Use 0 to disable introspection caching.");
        }
        if (cacheMaxSize < 0) {
            throw new IllegalStateException(
                    "OPAQUE_TOKEN_CACHE_MAX_SIZE (security.opaque-token.cache-max-size) must not be negative; "
                            + "it is " + cacheMaxSize + ".");
        }
        if (requestTimeoutMs <= 0) {
            throw new IllegalStateException(
                    "OPAQUE_TOKEN_REQUEST_TIMEOUT_MS (security.opaque-token.request-timeout-ms) must be "
                            + "positive; it is " + requestTimeoutMs + ". A value of 0 means no timeout at all, "
                            + "which lets an unresponsive identity provider hold a serving thread "
                            + "indefinitely.");
        }
    }
}
