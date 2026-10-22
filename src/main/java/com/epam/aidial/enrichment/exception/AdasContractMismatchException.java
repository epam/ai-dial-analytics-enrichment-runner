package com.epam.aidial.enrichment.exception;

import com.epam.aidial.enrichment.adas.AdasContract;

/**
 * An ADAS response did not carry this build's contract echo: the header was absent, or it held a different
 * value. Thrown from the client interceptor, so the call that met the skew fails before its body is
 * interpreted — whether that call was the sync poll or a data-plane one.
 *
 * <p>Both arms are the same verdict and are deliberately not ranked. An absent echo means ADAS predates the
 * contract; a different one means it is on the other side of a bump. The runner does not try to tell how far
 * apart the two are, because there is nothing it could safely do differently.
 */
public class AdasContractMismatchException extends AdasContractException {

    /**
     * The version ADAS echoed, or null when it carried no echo at all. The version this build declared is
     * not carried alongside it: it is always {@link AdasContract#VERSION}, so storing a copy would be a
     * second spelling of one constant.
     */
    private final String echoed;

    private AdasContractMismatchException(String message, String echoed) {
        super(message);
        this.echoed = echoed;
    }

    /** A response carrying no echo: an ADAS older than the contract, which cannot forge the header. */
    public static AdasContractMismatchException noEcho(String uri) {
        return new AdasContractMismatchException(
                "ADAS response for " + uri + " carried no " + AdasContract.HEADER + " header; this build "
                        + "declares contract version " + AdasContract.VERSION + ", so ADAS predates the contract",
                null);
    }

    /** A response echoing another version: an ADAS on the other side of a contract bump. */
    public static AdasContractMismatchException mismatch(String uri, String echoed) {
        return new AdasContractMismatchException(
                "ADAS response for " + uri + " echoed contract version " + echoed + ", but this build declares "
                        + AdasContract.VERSION,
                echoed);
    }

    /** The condition, without the URI, for the compatibility state's transition log. */
    public String reason() {
        return echoed == null
                ? "no " + AdasContract.HEADER + " echo (ADAS predates contract version " + AdasContract.VERSION + ")"
                : AdasContract.HEADER + " echo was " + echoed + ", not " + AdasContract.VERSION;
    }
}
