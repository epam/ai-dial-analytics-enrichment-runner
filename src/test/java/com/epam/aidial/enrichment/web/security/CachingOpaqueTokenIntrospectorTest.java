package com.epam.aidial.enrichment.web.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The caching decorator: what it saves, what it deliberately does not cache, and how it keys entries. */
class CachingOpaqueTokenIntrospectorTest {

    private static final OpaqueTokenProperties PROPERTIES = new OpaqueTokenProperties(60, 100, 3000);

    private static OAuth2AuthenticatedPrincipal principal(String name) {
        return new OAuth2IntrospectionAuthenticatedPrincipal(name, Map.of("sub", name), List.of());
    }

    @Test
    void repeatedTokenIsServedFromTheCache() {
        AtomicInteger calls = new AtomicInteger();
        OpaqueTokenIntrospector cached = new CachingOpaqueTokenIntrospector(token -> {
            calls.incrementAndGet();
            return principal("user-1");
        }, PROPERTIES);

        assertThat(cached.introspect("token").getName()).isEqualTo("user-1");
        assertThat(cached.introspect("token").getName()).isEqualTo("user-1");

        assertThat(calls).hasValue(1);
    }

    @Test
    void distinctTokensAreIntrospectedSeparately() {
        AtomicInteger calls = new AtomicInteger();
        OpaqueTokenIntrospector cached = new CachingOpaqueTokenIntrospector(token -> {
            calls.incrementAndGet();
            return principal(token);
        }, PROPERTIES);

        assertThat(cached.introspect("first").getName()).isEqualTo("first");
        assertThat(cached.introspect("second").getName()).isEqualTo("second");

        assertThat(calls).hasValue(2);
    }

    /**
     * Only successes are cached, so a revoked token stops working within one TTL rather than at eviction,
     * and a provider that was briefly unreachable is retried instead of being remembered as a rejection.
     */
    @Test
    void rejectionIsNotCached() {
        AtomicInteger calls = new AtomicInteger();
        OpaqueTokenIntrospector cached = new CachingOpaqueTokenIntrospector(token -> {
            calls.incrementAndGet();
            throw new BadOpaqueTokenException("no");
        }, PROPERTIES);

        assertThatThrownBy(() -> cached.introspect("token")).isInstanceOf(BadOpaqueTokenException.class);
        assertThatThrownBy(() -> cached.introspect("token")).isInstanceOf(BadOpaqueTokenException.class);

        assertThat(calls).hasValue(2);
    }

    /** A zero TTL turns the cache off, which is how an operator needing immediate revocation configures it. */
    @Test
    void zeroTtlDisablesCaching() {
        AtomicInteger calls = new AtomicInteger();
        OpaqueTokenIntrospector cached = new CachingOpaqueTokenIntrospector(token -> {
            calls.incrementAndGet();
            return principal("user-1");
        }, new OpaqueTokenProperties(0, 100, 3000));

        cached.introspect("token");
        cached.introspect("token");

        assertThat(calls).hasValue(2);
    }

    /**
     * A miss beyond the concurrency bound is rejected without reaching the provider, and the refusal is not
     * cached: once the in-flight introspection finishes, the same token is introspected normally.
     */
    @Test
    void missBeyondTheConcurrencyBoundIsRejectedWithoutReachingTheProvider() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        OpaqueTokenIntrospector cached = new CachingOpaqueTokenIntrospector(token -> {
            calls.incrementAndGet();
            if (token.equals("slow")) {
                entered.countDown();
                await(release);
            }
            return principal(token);
        }, PROPERTIES, new IntrospectionBulkhead(1));

        CompletableFuture<OAuth2AuthenticatedPrincipal> slow = CompletableFuture.supplyAsync(() -> cached.introspect("slow"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> cached.introspect("other")).isInstanceOf(BadOpaqueTokenException.class);
        assertThat(calls).hasValue(1);

        release.countDown();
        assertThat(slow.get(5, TimeUnit.SECONDS).getName()).isEqualTo("slow");
        assertThat(cached.introspect("other").getName()).isEqualTo("other");
        assertThat(calls).hasValue(2);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
