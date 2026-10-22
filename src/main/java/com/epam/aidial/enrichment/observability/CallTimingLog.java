package com.epam.aidial.enrichment.observability;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * The one line an outbound ADAS or DIAL call leaves in the log: DEBUG for an ordinary call, WARN for a call
 * that took more than half of its client's read timeout, was answered {@code 429} or {@code 5xx}, or failed in
 * transport. A transport WARN names the read-timeout property the call ran against.
 *
 * <p>The path is logged without its query string, and nothing here takes a body, so a call line can carry
 * neither.
 */
@RequiredArgsConstructor
public final class CallTimingLog {

    private static final int MAX_CAUSE_DEPTH = 8;

    private final Logger log;

    /** {@code ADAS} or {@code DIAL}: the first word of every line. */
    private final String system;

    /** The read-timeout property of the client, e.g. {@code adas.read-timeout-ms}. */
    private final String readTimeoutProperty;

    private final int readTimeoutMs;

    /** What a client does with a transport failure once it is logged: rethrow it, or translate it. */
    @FunctionalInterface
    public interface TransportFailure {
        void raise(IOException failure, long elapsedMs) throws IOException;
    }

    /** A transport failure's root type and message: a socket message names a host or a condition, never a body. */
    public static String describe(IOException cause) {
        Throwable root = cause;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < MAX_CAUSE_DEPTH; depth++) {
            root = root.getCause();
        }
        String type = root.getClass().getSimpleName();
        return root.getMessage() == null ? type : type + ": " + root.getMessage();
    }

    /** Whether a call that took {@code elapsedMs} used more than half of {@code readTimeoutMs}. */
    public static boolean isOverHalf(long elapsedMs, int readTimeoutMs) {
        return elapsedMs * 2 > readTimeoutMs;
    }

    /**
     * Executes the call and reads its status, then logs it. For a request with a body the HTTP client does not
     * read the status line itself, so this is where a read timeout surfaces; such a failure is logged, the response
     * closed, and the failure handed to {@code onTransportFailure}, which rethrows it or a translation of it.
     */
    public ClientHttpResponse execute(HttpRequest request, byte[] body, ClientHttpRequestExecution execution,
                                      TransportFailure onTransportFailure) throws IOException {
        String path = request.getURI().getPath();
        long start = System.nanoTime();
        ClientHttpResponse response = null;
        try {
            response = execution.execute(request, body);
            completed(request.getMethod(), path, response.getStatusCode(), Elapsed.millisSince(start));
            return response;
        } catch (IOException e) {
            if (response != null) {
                response.close();
            }
            long elapsedMs = Elapsed.millisSince(start);
            log.warn("{} {} {} failed in transport after {}ms ({}={}): {}", system, request.getMethod(), path, elapsedMs,
                    readTimeoutProperty, readTimeoutMs, describe(e));
            onTransportFailure.raise(e, elapsedMs);
            throw e;
        }
    }

    private void completed(HttpMethod method, String path, HttpStatusCode status, long elapsedMs) {
        if (isOverHalf(elapsedMs, readTimeoutMs)) {
            log.warn("{} {} {} -> {} in {}ms, over half of {}={}", system, method, path, status.value(), elapsedMs,
                    readTimeoutProperty, readTimeoutMs);
        } else if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value() || status.is5xxServerError()) {
            log.warn("{} {} {} -> {} in {}ms", system, method, path, status.value(), elapsedMs);
        } else {
            log.debug("{} {} {} -> {} in {}ms", system, method, path, status.value(), elapsedMs);
        }
    }
}
