package com.epam.aidial.enrichment.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock a test moves explicitly. Group readiness ({@code ready_when.idle}, {@code max_staleness}) and
 * state eviction are decisions about elapsed time; the only alternative way to exercise them is to sleep,
 * which is slow and flaky.
 *
 * <p>Register it in place of the {@code Clock} bean and call {@link #advance(Duration)} to cross a
 * threshold deterministically.
 */
public final class SettableClock extends Clock {

    private final ZoneId zone;
    private final AtomicReference<Instant> instant;

    public SettableClock(Instant instant) {
        this(instant, ZoneOffset.UTC);
    }

    private SettableClock(Instant instant, ZoneId zone) {
        this.instant = new AtomicReference<>(instant);
        this.zone = zone;
    }

    @Override
    public Instant instant() {
        return instant.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new SettableClock(instant.get(), newZone);
    }

    /** Moves the clock forward. */
    public void advance(Duration amount) {
        instant.updateAndGet(current -> current.plus(amount));
    }

    /** Moves the clock to an absolute instant. */
    public void setTo(Instant newInstant) {
        instant.set(newInstant);
    }
}
