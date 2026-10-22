package com.epam.aidial.enrichment.worker;

import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Summarises held work items instead of logging each one. A held condition — an incompatible ADAS, a cache that has
 * not synced yet — holds every due item of every lane, each re-held every {@link WorkHold#DELAY}, so a line per hold
 * is hundreds of identical lines a minute for the length of a deploy window.
 *
 * <p>The first hold of a condition is logged in full; after that, at most one count per {@link #REPORT_INTERVAL}
 * while holds keep arriving. A condition is over once no hold has arrived for a whole interval — longer than the
 * hold delay, so a condition that is still in force always re-holds inside it — and the next hold starts a new
 * one, logged in full again. Per-process, in-memory, one condition at a time: which condition holds is in the
 * first line, and the heartbeat reports the state.
 */
@Slf4j
final class HeldWorkLog {

    /** At most one count line per this interval while holds continue. */
    static final Duration REPORT_INTERVAL = Duration.ofMinutes(5);

    private final Clock clock;

    private Instant lastHoldAt;
    private Instant lastReportAt;
    private Instant conditionStartedAt;
    private long heldSinceReport;

    HeldWorkLog(Clock clock) {
        this.clock = clock;
    }

    /** Records one hold of {@code what}, logging it when it starts a condition or closes a reporting interval. */
    synchronized void held(String what, String cause) {
        Instant now = clock.instant();
        if (lastHoldAt == null || !now.isBefore(lastHoldAt.plus(REPORT_INTERVAL))) {
            conditionStartedAt = now;
            lastReportAt = now;
            heldSinceReport = 0;
            log.warn("{} held; retrying in {} without consuming an attempt; further holds are counted every {}: {}",
                    what, HumanDuration.format(WorkHold.DELAY), HumanDuration.format(REPORT_INTERVAL), cause);
        } else {
            heldSinceReport++;
            if (!now.isBefore(lastReportAt.plus(REPORT_INTERVAL))) {
                log.warn("{} work item hold(s) in the last {}, held for {} so far; latest {}: {}", heldSinceReport,
                        HumanDuration.format(Duration.between(lastReportAt, now)),
                        HumanDuration.format(Duration.between(conditionStartedAt, now)), what, cause);
                lastReportAt = now;
                heldSinceReport = 0;
            }
        }
        lastHoldAt = now;
    }
}
