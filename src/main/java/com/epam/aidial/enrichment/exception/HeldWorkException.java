package com.epam.aidial.enrichment.exception;

/**
 * A work item cannot run <em>yet</em>, for a reason that clears on its own. Every such cause shares one
 * treatment: the item is <strong>held</strong> — rescheduled after a short delay, its attempt budget
 * untouched, and never archived however long the condition lasts. See
 * {@link com.epam.aidial.enrichment.worker.WorkHold}, which is where that treatment is defined once so the
 * three work lanes cannot disagree about what "held" means.
 *
 * <p>Two cases today, and they are why this is a base type rather than a single exception — the
 * distinction it draws is the useful one: a condition the runner is waiting out, as opposed to a failure
 * it is retrying.
 *
 * <ul>
 *   <li>{@link PipelineCacheColdException} — this process has not yet synced the pipeline registry, which
 *       the first successful poll clears;</li>
 *   <li>{@link AdasContractException} — ADAS is on the other side of a contract version bump, which the
 *       matching deploy clears. An upstream the runner has decided it must not talk to.</li>
 * </ul>
 *
 * <p>Any later condition of that kind belongs on the same arm rather than on a second copy of it; a second
 * copy is exactly what the two changes that introduced these cases each grew independently, and what
 * merging them removed.
 *
 * <p>The dead-letter queue exists for work this runner cannot process; a process that has not finished
 * starting is not that.
 */
public abstract class HeldWorkException extends RuntimeException {

    /** Bounds the walk over a cause chain, which a wrapping layer could in principle make cyclic. */
    private static final int MAX_CAUSE_DEPTH = 16;

    protected HeldWorkException(String message) {
        super(message);
    }

    /**
     * Whether this failure is (or wraps) a condition that must be held rather than retried towards the DLQ.
     * Walks the cause chain because db-scheduler hands the handler whatever the task threw, and a lane may
     * wrap the original refusal on its way out.
     */
    public static boolean isHeld(Throwable cause) {
        Throwable current = cause;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof HeldWorkException) {
                return true;
            }
            if (current.getCause() == current) {
                return false;
            }
            current = current.getCause();
        }
        return false;
    }
}
