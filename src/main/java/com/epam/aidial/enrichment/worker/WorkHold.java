package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.exception.HeldWorkException;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Holds a work item that cannot run <em>yet</em>, instead of retrying it towards the DLQ.
 *
 * <p>Shared by all three work-item failure handlers ({@link ChunkFailureHandler},
 * {@code GroupFailureHandler}, {@code UpsertFailureHandler}) because "held" has to mean the same thing on
 * each: rescheduled after a short fixed delay, with the item's attempt budget untouched and no archive
 * however long the condition lasts. A held condition is temporary by construction — see
 * {@link HeldWorkException} — and the dead-letter queue exists for a row this runner cannot process, not
 * for a process that has not finished starting.
 *
 * <p><b>"Without consuming an attempt" has to be built, not inherited.</b> db-scheduler increments
 * {@code consecutive_failures} on every failure, and the handlers archive at {@code worker.max-attempts}. A
 * hold expressed on the ordinary failure arm would leave that counter elevated for the whole length of the
 * condition, so the <em>first real</em> failure after it cleared would archive on its first attempt —
 * turning a startup window into lost work at the moment it ended. {@link ExecutionOperations#reschedule}
 * writes {@code consecutive_failures = 0} when handed an {@link ExecutionComplete} whose result is OK
 * (verified against db-scheduler 15.6.0), so the hold reschedules with {@link ExecutionComplete#success}:
 * neither the backoff ladder nor the archive branch.
 *
 * <p>The OK result also stamps {@code last_success}, which is the one thing this overstates — the item did
 * not succeed, it did not run. That is the price of the counter reset, and it is the right trade: a held
 * lane is visible as due items not executing while the condition that names it is reported elsewhere.
 */
public final class WorkHold {

    /**
     * How long a held item waits before trying again. Short, because the window it is waiting out is a
     * registry poll: long enough not to spin, short enough that nothing queues up behind the recovery.
     */
    public static final Duration DELAY = Duration.ofSeconds(30);

    /** Every hold in the process reports through one summary: the first in full, then a count per interval. */
    private static final HeldWorkLog HELD_LOG = new HeldWorkLog(Clock.systemUTC());

    private WorkHold() {
    }

    /**
     * Reschedules the item and answers true when the failure was (or wrapped) a {@link HeldWorkException}.
     * Answers false for every other cause, including a permanent one such as {@code PoisonGroupException},
     * which must keep its own treatment.
     */
    public static <T> boolean holdIfHeld(String what, ExecutionComplete complete,
                                         ExecutionOperations<T> operations) {
        Throwable cause = complete.getCause().orElse(null);
        if (!HeldWorkException.isHeld(cause)) {
            return false;
        }
        Instant timeDone = complete.getTimeDone();
        ExecutionComplete held = ExecutionComplete.success(complete.getExecution(),
                timeDone.minus(complete.getDuration()), timeDone);
        operations.reschedule(held, Instant.now().plus(DELAY));
        HELD_LOG.held(what, cause == null ? "unknown cause" : cause.getMessage());
        return true;
    }
}
