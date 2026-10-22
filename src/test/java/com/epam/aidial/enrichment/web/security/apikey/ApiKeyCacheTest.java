package com.epam.aidial.enrichment.web.security.apikey;

import com.epam.aidial.enrichment.configuration.JsonMapperConfiguration;
import com.epam.aidial.enrichment.web.security.IntrospectionBulkhead;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The concurrency bound on cache misses: a miss beyond it never reaches Core, and the refusal is not cached. */
class ApiKeyCacheTest {

    @Test
    void missBeyondTheConcurrencyBoundIsRefusedWithoutReachingCore() throws Exception {
        ApiKeyCache cache = cacheWith(new IntrospectionBulkhead(1));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        CompletableFuture<Authentication> slow = CompletableFuture.supplyAsync(() -> cache.getOrAuthenticate("slow", () -> {
            calls.incrementAndGet();
            entered.countDown();
            await(release);
            return new TestingAuthenticationToken("slow", "n/a");
        }));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> cache.getOrAuthenticate("other", () -> {
            calls.incrementAndGet();
            return new TestingAuthenticationToken("other", "n/a");
        })).isInstanceOf(AuthenticationServiceException.class);
        assertThat(calls).hasValue(1);

        release.countDown();
        assertThat(slow.get(5, TimeUnit.SECONDS).getName()).isEqualTo("slow");
        assertThat(cache.getOrAuthenticate("other", () -> {
            calls.incrementAndGet();
            return new TestingAuthenticationToken("other", "n/a");
        }).getName()).isEqualTo("other");
        assertThat(calls).hasValue(2);
    }

    @Test
    void missWithEveryPermitTakenIsAnAuthenticationServiceException() {
        ApiKeyCache cache = cacheWith(new IntrospectionBulkhead(0));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> cache.getOrAuthenticate("any", () -> {
            calls.incrementAndGet();
            return new TestingAuthenticationToken("any", "n/a");
        })).isInstanceOf(AuthenticationServiceException.class);
        assertThat(calls).hasValue(0);
    }

    private static ApiKeyCache cacheWith(IntrospectionBulkhead bulkhead) {
        ApiKeyProperties properties = new ApiKeyProperties(new JsonMapperConfiguration().jsonMapper(), "");
        properties.setCacheTtlSeconds(60);
        properties.setCacheMaxSize(100);
        return new ApiKeyCache(properties, bulkhead);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
