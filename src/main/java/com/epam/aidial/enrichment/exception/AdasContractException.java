package com.epam.aidial.enrichment.exception;

/**
 * The runner and ADAS disagree about the contract version, so nothing ADAS says may be interpreted and
 * nothing the runner computes may be written. Raised in two places, which is why it is one type:
 *
 * <ul>
 *   <li>{@link AdasContractMismatchException} — a response whose echo was absent or different, thrown from
 *       the client interceptor by the call that discovered it;</li>
 *   <li>{@link AdasIncompatibleHoldException} — the recorded verdict, thrown by a work lane's gate before
 *       it makes any call at all.</li>
 * </ul>
 *
 * <p>A {@link HeldWorkException}, and that is the whole of its failure treatment: the three work-item
 * failure handlers reschedule on this cause without consuming an attempt and without ever archiving,
 * because the condition is temporary by construction — the matching deploy is what clears it — and the
 * dead-letter queue exists for a row this runner cannot process, not for a deploy in progress. It is a
 * {@code HeldWorkException} rather than a second copy of that treatment precisely because the distinction
 * the base type draws — a condition the runner is waiting out, as opposed to a failure it is retrying — is
 * the one a contract skew falls on; see {@link com.epam.aidial.enrichment.worker.WorkHold}.
 */
public abstract class AdasContractException extends HeldWorkException {

    protected AdasContractException(String message) {
        super(message);
    }
}
