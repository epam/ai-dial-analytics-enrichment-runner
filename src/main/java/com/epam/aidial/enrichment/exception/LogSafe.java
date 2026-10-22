package com.epam.aidial.enrichment.exception;

import org.springframework.web.client.RestClientResponseException;

/**
 * Guards what a failure path hands to a logger. A {@link RestClientResponseException} puts the upstream response
 * body into its message, so its {@code toString()} — and the first line of every stack trace that contains it —
 * carries a DIAL or ADAS body into the log aggregator. Such a failure is logged by its
 * {@linkplain ErrorMessages#classify classified} form only.
 */
public final class LogSafe {

    /** Bounds the walk over a cause chain, which a wrapping layer could in principle make cyclic. */
    private static final int MAX_CAUSE_DEPTH = 16;

    private LogSafe() {
    }

    /**
     * The throwable to pass as a log call's throwable argument: {@code failure} itself, or {@code null} when it or
     * any of its causes is a {@link RestClientResponseException}, whose stack trace would print the body.
     */
    public static Throwable loggable(Throwable failure) {
        return responseIn(failure) == null ? failure : null;
    }

    private static RestClientResponseException responseIn(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof RestClientResponseException rejection) {
                return rejection;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }
}
