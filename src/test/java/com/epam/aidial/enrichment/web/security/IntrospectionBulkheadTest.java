package com.epam.aidial.enrichment.web.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The bound on concurrent introspections: a refusal never reaches the provider, and every exit returns its permit. */
class IntrospectionBulkheadTest {

    private final IntrospectionBulkhead bulkhead = new IntrospectionBulkhead(1);

    @Test
    void permitIsReturnedAfterNormalReturn() {
        assertThat(bulkhead.call(() -> "first", Saturated::new)).isEqualTo("first");
        assertThat(bulkhead.call(() -> "second", Saturated::new)).isEqualTo("second");
    }

    @Test
    void permitIsReturnedWhenTheIntrospectionThrows() {
        assertThatThrownBy(() -> bulkhead.call(() -> {
            throw new IllegalStateException("provider unreachable");
        }, Saturated::new)).isInstanceOf(IllegalStateException.class).hasMessage("provider unreachable");

        assertThat(bulkhead.call(() -> "after failure", Saturated::new)).isEqualTo("after failure");
    }

    @Test
    void callFindingEveryPermitTakenIsRefusedWithoutRunningTheIntrospection() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger refusedCalls = new AtomicInteger();

        CompletableFuture<String> holder = CompletableFuture.supplyAsync(() -> bulkhead.call(() -> {
            entered.countDown();
            await(release);
            return "held";
        }, Saturated::new));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> bulkhead.call(() -> {
            refusedCalls.incrementAndGet();
            return "should not run";
        }, Saturated::new)).isInstanceOf(Saturated.class);
        assertThat(refusedCalls).hasValue(0);

        release.countDown();
        assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("held");
        assertThat(bulkhead.call(() -> "after release", Saturated::new)).isEqualTo("after release");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Saturated extends RuntimeException {
    }
}
