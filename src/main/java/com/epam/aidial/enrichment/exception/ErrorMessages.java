package com.epam.aidial.enrichment.exception;

import com.epam.aidial.enrichment.adas.AdasContract;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place a failure is turned into text a caller may read: the sanitizer every {@code ErrorView} message
 * passes through, and the classifier behind a backfill run's {@code last_error}.
 *
 * <p>The service has never served a raw exception message — the exception handler deliberately refuses to echo
 * one — and {@code last_error} would be the first surface that did. A {@link RestClientResponseException}'s
 * message carries the upstream response body, and a data-access exception's carries SQL and bound values. So the
 * classifier stores a classification instead: the curated text of a failure the runner raises itself, the HTTP
 * status and ADAS error code of an upstream rejection, and only the failure's type otherwise.
 */
public final class ErrorMessages {

    /** Hard cap on a sanitized error-view message, bounding worst-case exposure in a response body. */
    public static final int MAX_MESSAGE_CHARS = 2000;

    /** Hard cap on a classified failure: it is stored on a row and read in a listing, not a diagnostic dump. */
    public static final int MAX_CLASSIFIED_CHARS = 500;

    /** Bounds the walk over a cause chain, which a wrapping layer could in principle make cyclic. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * Matches a stack-frame line ("... at com.foo.Bar(...)") after any line terminator, tolerating
     * CRLF endings and frames indented with spaces or no leading tab at all.
     */
    private static final Pattern STACK_FRAME = Pattern.compile("\\R\\s*at\\s");

    /**
     * ADAS's stable machine code in its {@code ErrorView} body. Only a code-shaped token is ever extracted, so no
     * other part of the body — which may quote the request — can reach the stored text.
     */
    private static final Pattern ADAS_ERROR_CODE = Pattern.compile("\"error\"\\s*:\\s*\"([A-Za-z0-9_.-]{1,64})\"");

    private ErrorMessages() {
    }

    /**
     * Best-effort sanitization to prevent accidental stacktrace leakage in API responses.
     * Error messages should already be sanitized at the exception-handling layer; this is a last
     * line of defense.
     */
    public static String sanitize(String message) {
        return sanitize(message, MAX_MESSAGE_CHARS);
    }

    private static String sanitize(String message, int maxChars) {
        String safe = Objects.requireNonNullElse(message, "");
        if (STACK_FRAME.matcher(safe).find()) {
            // A stack trace follows the first line break (any of \n, \r, \r\n); keep only the leading line.
            int firstBreak = indexOfLineBreak(safe);
            if (firstBreak >= 0) {
                safe = safe.substring(0, firstBreak);
            }
        }

        if (safe.length() > maxChars) {
            safe = safe.substring(0, maxChars);
        }
        return safe;
    }

    /**
     * A short, classified description of why {@code failure} happened, safe to store and serve.
     *
     * <ul>
     *   <li>the curated message of the runner's own refusals — a page missing a column, an unaccounted page, an
     *       incompatible ADAS — and of an ADAS call that failed in transport, which names the call and the timeout;</li>
     *   <li>{@code "ADAS <status> <error code>"} for a rejection ADAS produced, {@code "HTTP <status>"} for any other
     *       (DIAL, or an ingress), never its body;</li>
     *   <li>the failure's type otherwise — the wrapper's and its root cause's when they differ, since a wrapper
     *       alone ({@code RuntimeException}, {@code UndeclaredThrowableException}) tells an operator nothing.</li>
     * </ul>
     */
    public static String classify(Throwable failure) {
        Throwable current = failure;
        Throwable root = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            root = current;
            if (isCurated(current)) {
                return sanitize(current.getMessage(), MAX_CLASSIFIED_CHARS);
            }
            if (current instanceof RestClientResponseException rejection) {
                return sanitize(upstream(rejection), MAX_CLASSIFIED_CHARS);
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        if (failure == null) {
            return "unknown failure";
        }
        String type = typeName(failure);
        return root == failure ? type : type + ": " + typeName(root);
    }

    /**
     * Whether {@link #classify} reports {@code failure} by a curated message, which names what failed and why, so its
     * stack trace adds nothing a reader needs.
     */
    public static boolean isSelfDescribing(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (isCurated(current)) {
                return true;
            }
            if (current instanceof RestClientResponseException || current.getCause() == current) {
                return false;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isCurated(Throwable failure) {
        return failure instanceof ScanPageColumnException
                || failure instanceof UnaccountedScanPageException
                || failure instanceof AdasContractException
                || failure instanceof AdasTransportException;
    }

    /** The simple name, or the qualified one for an anonymous or local class, whose simple name is empty. */
    private static String typeName(Throwable failure) {
        String simple = failure.getClass().getSimpleName();
        return simple.isEmpty() ? failure.getClass().getName() : simple;
    }

    /**
     * {@code ADAS <status> [<code>]} for a response ADAS produced, which is one carrying the contract echo; otherwise
     * {@code HTTP <status>} — a DIAL answer, or an ingress error in front of either — with no body token read.
     */
    private static String upstream(RestClientResponseException rejection) {
        HttpHeaders headers = rejection.getResponseHeaders();
        if (headers == null || headers.getFirst(AdasContract.HEADER) == null) {
            return "HTTP " + rejection.getStatusCode().value();
        }
        String text = "ADAS " + rejection.getStatusCode().value();
        Matcher code = ADAS_ERROR_CODE.matcher(rejection.getResponseBodyAsString());
        return code.find() ? text + " " + code.group(1) : text;
    }

    private static int indexOfLineBreak(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return -1;
    }
}
