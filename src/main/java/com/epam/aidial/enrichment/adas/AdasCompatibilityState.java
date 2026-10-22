package com.epam.aidial.enrichment.adas;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Whether the last ADAS response carried this build's contract echo — the one fact the paid-work gate and
 * the compatibility gauge both read.
 *
 * <p>Written by {@link AdasContractInterceptor}, which is the only component that observes every response.
 * Deliberately not written by the sync poll: a skew beginning between polls is met first by whichever call
 * comes first, and that is usually a scan tick or a write-back. A verdict recorded only by the poll would
 * leave the gate reading a stale {@code compatible} for up to a poll interval, and paid work would go
 * through it.
 *
 * <p><b>The ERROR is logged on the transition, not on the condition.</b> Incompatibility is met by every
 * call while it lasts — a scan tick, a group fetch, a write-back, each one repeating — so logging per
 * occurrence would bury the one line that says what happened under thousands that say the same thing. Same
 * pattern, same reason, as {@code PipelineSyncService}'s {@code refusedGenerations}.
 *
 * <p>In-memory and single-replica, like the pause registry and the rate limiter: a restart re-derives it
 * from the first response, and until then the runner is optimistic — which is correct, because a fresh
 * process has made no call yet and its first one will settle the question before any work is dispatched.
 */
@Slf4j
@Getter
@Component
public class AdasCompatibilityState {

    /**
     * Starts {@code true} so a process that has not yet called ADAS does not report a skew it has no
     * evidence for. Nothing is lost by the optimism: the gauge is published from this value and the first
     * response corrects it, while the sync's own readiness latch is what keeps a fresh pod out of service
     * until a poll has actually succeeded.
     */
    private volatile boolean compatible = true;

    /** Why the runner last classified ADAS incompatible, or null while it is compatible. */
    private volatile String reason;

    /** Records a response that carried the matching echo, logging the recovery only if there was one. */
    public void markCompatible() {
        if (!compatible) {
            log.error("ADAS is compatible again (contract version {}); held work resumes on its next due time",
                    AdasContract.VERSION);
        }
        compatible = true;
        reason = null;
    }

    /**
     * Records a response that did not, logging the ERROR only on the transition into the condition. A
     * changed reason counts as a transition: "no echo at all" and "echoed 2" are different deployments on
     * the far side, and an operator reading one line needs the one that is currently true.
     */
    public void markIncompatible(String why) {
        if (compatible || !why.equals(reason)) {
            log.error("ADAS is incompatible with this build's contract version {}: {}. Keeping the pipeline cache "
                    + "and tasks, dispatching no work, and advancing no cursor until a matching ADAS answers",
                    AdasContract.VERSION, why);
        }
        compatible = false;
        reason = why;
    }
}
