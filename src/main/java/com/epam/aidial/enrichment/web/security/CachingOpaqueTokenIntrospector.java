package com.epam.aidial.enrichment.web.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

import java.time.Duration;

/**
 * Caches successful opaque-token introspections so a valid token reaches the provider at most once per
 * TTL. Entries are keyed by a SHA-256 digest of the raw token (never the token itself), bounded by the
 * configured TTL and maximum size. Only successes are cached — the loader propagates any exception, so a
 * rejected token is re-introspected on every request and a revoked one stops working within one TTL
 * rather than at eviction.
 *
 * <p>Runner-only, with no ADAS counterpart: ADAS reintrospects on every request. The same contract the
 * API-key path already implements in {@code apikey/ApiKeyCache}, applied here because the cost is higher —
 * a miss is one userinfo round trip, and two for a provider deriving roles through a {@code fn:} converter.
 * Written as a decorator so the ported {@link MultiIssuerOpaqueTokenIntrospector} stays byte-comparable
 * with ADAS.
 *
 * <p>Misses run behind an {@link IntrospectionBulkhead}. A miss refused for want of a permit is rejected as
 * a bad token — the same {@code 401} an unreachable provider already produces, since the multi-provider
 * introspector cannot tell a rejection from an outage — and is not cached.
 */
@Slf4j
public class CachingOpaqueTokenIntrospector implements OpaqueTokenIntrospector {

    private final OpaqueTokenIntrospector delegate;
    private final Cache<String, OAuth2AuthenticatedPrincipal> cache;
    private final IntrospectionBulkhead bulkhead;

    public CachingOpaqueTokenIntrospector(OpaqueTokenIntrospector delegate, OpaqueTokenProperties properties) {
        this(delegate, properties, new IntrospectionBulkhead(IntrospectionBulkhead.DEFAULT_MAX_CONCURRENT));
    }

    CachingOpaqueTokenIntrospector(OpaqueTokenIntrospector delegate, OpaqueTokenProperties properties,
                                   IntrospectionBulkhead bulkhead) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(properties.cacheTtlSeconds()))
                .maximumSize(properties.cacheMaxSize())
                .build();
        this.bulkhead = bulkhead;
        log.debug("Initialized opaque-token introspection cache: ttl={}s, maxSize={}",
                properties.cacheTtlSeconds(), properties.cacheMaxSize());
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        return cache.get(TokenDigest.sha256Hex(token), k -> bulkhead.call(() -> delegate.introspect(token),
                () -> new BadOpaqueTokenException("Too many concurrent opaque-token introspections")));
    }
}
