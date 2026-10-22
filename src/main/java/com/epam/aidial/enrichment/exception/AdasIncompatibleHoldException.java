package com.epam.aidial.enrichment.exception;

/**
 * A work item became due while ADAS was already recorded incompatible, so it is held before any ADAS call,
 * any DIAL call and any write.
 *
 * <p>The cheap half of the paid-work gate. The expensive half is
 * {@link AdasContractMismatchException}, which the <em>first</em> item to meet a fresh skew raises from
 * inside its own call — by then the recorded state still read compatible. Both arms lead to the same
 * outcome, and this one is what stops an item enqueued before the skew from paying for a model call it
 * could not write.
 */
public class AdasIncompatibleHoldException extends AdasContractException {

    public AdasIncompatibleHoldException(String what, String reason) {
        super("Holding " + what + ": ADAS is incompatible with this build's contract (" + reason + ")");
    }
}
