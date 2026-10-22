package com.epam.aidial.enrichment.observability;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Tracks whether the runner has completed at least one successful ADAS pipeline sync. Readiness gates on
 * this (plus PostgreSQL reachability) so traffic/scheduling is only considered ready once the pipeline
 * cache has been populated at least once.
 *
 * <p>It also keeps the instant of the most recent success, which the status heartbeat reports as {@code sync_age}:
 * the latch says the runner has synced once, and only the instant says whether it still is.
 */
@Component
@RequiredArgsConstructor
public class ReadinessState {

    private final Clock clock;

    @Getter
    private volatile boolean pipelineSyncSucceeded;

    /** The instant of the last successful sync, or {@code null} before the first one. */
    @Getter
    private volatile Instant lastPipelineSyncAt;

    public void markPipelineSyncSucceeded() {
        this.lastPipelineSyncAt = clock.instant();
        this.pipelineSyncSucceeded = true;
    }

}
