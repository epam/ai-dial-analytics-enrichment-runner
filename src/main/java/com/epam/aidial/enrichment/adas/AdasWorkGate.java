package com.epam.aidial.enrichment.adas;

import com.epam.aidial.enrichment.exception.AdasIncompatibleHoldException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The cheap half of the paid-work gate: refuses to start a work item while ADAS is already recorded
 * incompatible, before any ADAS call, any DIAL call and any write.
 *
 * <p>This is what stops a chunk <em>enqueued before</em> the skew from paying for a model call it cannot
 * write. The lane's shape is derive-then-write, so a gate at the write alone would spend the call first and
 * then archive the result — the retries and the eventual dead letter all sitting downstream of money
 * already spent.
 *
 * <p>It is not the only arm, and cannot be: the <em>first</em> item to meet a fresh skew is the one that
 * sets the state, and when it starts the recorded verdict still reads compatible. That one is held by
 * {@link com.epam.aidial.enrichment.exception.AdasContractMismatchException} arriving from inside its own
 * call. Both arms end in the same place — a hold, in the lane's failure handler.
 *
 * <p>Deliberately <b>not</b> a reuse of the pause registry. {@code isPaused} gates <em>enqueueing</em>, in
 * the scan, backfill, sweep and materialize executors; none of the three work-item services consults
 * anything today, and what needs gating here is <em>executing</em>.
 */
@Component
@RequiredArgsConstructor
public class AdasWorkGate {

    private final AdasCompatibilityState compatibilityState;

    /**
     * Throws {@link AdasIncompatibleHoldException} when ADAS is recorded incompatible, naming the item so
     * the hold reads as being about this work rather than about the runner in general.
     */
    public void requireCompatible(String what) {
        if (compatibilityState.isCompatible()) {
            return;
        }
        String reason = compatibilityState.getReason();
        throw new AdasIncompatibleHoldException(what, reason == null ? "no matching contract echo" : reason);
    }
}
