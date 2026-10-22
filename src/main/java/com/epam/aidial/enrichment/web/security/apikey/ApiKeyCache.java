package com.epam.aidial.enrichment.web.security.apikey;

import com.epam.aidial.enrichment.web.security.IntrospectionBulkhead;
import com.epam.aidial.enrichment.web.security.TokenDigest;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Caches successful API-key introspections so a valid key hits DIAL Core at most once per TTL.
 * Entries are keyed by a SHA-256 digest of the raw key (never the key itself), bounded by the
 * configured TTL and maximum size. Only successful authentications are cached — the loader propagates
 * any exception, so a rejected key is re-checked against Core on every request and a revoked key stops
 * working within one TTL rather than at eviction.
 *
 * <p>Misses run behind an {@link IntrospectionBulkhead}; one refused for want of a permit surfaces as an
 * {@link AuthenticationServiceException}, which the filter answers with the same {@code 503} as an
 * unreachable Core, and is not cached.
 */
@Slf4j
@Component
@ConditionalOnExpression(ApiKeyProperties.ACTIVE_CONDITION)
public class ApiKeyCache {

    private final Cache<String, Authentication> cache;
    private final IntrospectionBulkhead bulkhead;

    @Autowired
    public ApiKeyCache(ApiKeyProperties properties) {
        this(properties, new IntrospectionBulkhead(IntrospectionBulkhead.DEFAULT_MAX_CONCURRENT));
    }

    ApiKeyCache(ApiKeyProperties properties, IntrospectionBulkhead bulkhead) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(properties.getCacheTtlSeconds()))
                .maximumSize(properties.getCacheMaxSize())
                .build();
        this.bulkhead = bulkhead;
        log.debug("Initialized api-key cache: ttl={}s, maxSize={}",
                properties.getCacheTtlSeconds(), properties.getCacheMaxSize());
    }

    public Authentication getOrAuthenticate(String apiKey, Supplier<Authentication> loader) {
        return cache.get(TokenDigest.sha256Hex(apiKey), k -> bulkhead.call(loader,
                () -> new AuthenticationServiceException("Too many concurrent API-key introspections")));
    }
}
