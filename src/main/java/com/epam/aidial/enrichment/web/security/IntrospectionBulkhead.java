package com.epam.aidial.enrichment.web.security;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Bounds how many credential introspections — outbound calls to an identity provider or DIAL Core made on a
 * cache miss — run at once. Only successes are cached, and a caller choosing a fresh random credential per
 * request misses every time, so without a bound an unauthenticated caller decides how many outbound calls
 * the runner makes and how many serving threads wait on them. A rejection cache would not help: every random
 * credential is a new key.
 *
 * <p>A request that finds every permit taken is refused at once rather than queued, since waiting would hold
 * the serving thread the bound exists to protect. Cached credentials never reach this class, so a flood of
 * unknown ones cannot lock out a caller that has already authenticated within the cache TTL.
 *
 * <p>{@link #DEFAULT_MAX_CONCURRENT} is fixed rather than configurable: the runner's callers are operational
 * tooling, which re-presents a handful of credentials it has already authenticated, so even a burst of new
 * ones stays far below it.
 */
@Slf4j
public final class IntrospectionBulkhead {

    /** Introspections allowed to run at once, per credential kind. */
    public static final int DEFAULT_MAX_CONCURRENT = 16;

    private final Semaphore permits;

    public IntrospectionBulkhead(int maxConcurrent) {
        this.permits = new Semaphore(maxConcurrent);
    }

    /**
     * Runs {@code introspection} if a permit is free; otherwise throws the exception {@code onSaturated}
     * supplies, without contacting the provider.
     */
    public <T> T call(Supplier<T> introspection, Supplier<? extends RuntimeException> onSaturated) {
        if (!permits.tryAcquire()) {
            // Debug, not warn: the caller filling the permits is the caller that would otherwise set the log volume.
            log.debug("Credential introspection refused: every concurrent-introspection permit is taken");
            throw onSaturated.get();
        }
        try {
            return introspection.get();
        } finally {
            permits.release();
        }
    }
}
