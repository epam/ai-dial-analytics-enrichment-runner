package com.epam.aidial.enrichment.exception;

import com.epam.aidial.enrichment.observability.CallTimingLog;
import lombok.Getter;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An ADAS call failed below HTTP: a read timeout, a refused or reset connection. Raised by
 * {@link com.epam.aidial.enrichment.adas.AdasContractInterceptor} so that the failure reads as what it is
 * instead of as Spring's "Error while extracting response" or, worse, as a missing contract echo.
 *
 * <p><b>A {@link ResourceAccessException} on purpose.</b> Transport failures are recognised by the work lanes'
 * {@code catch (RestClientException)} arms, which turn them into a {@link TransientChunkException} carrying
 * the lane's stage. Staying a {@code RestClientException} keeps those arms, and the stage, working unchanged.
 *
 * <p>The message names the call, the pipeline where the path carries one, the time waited and the
 * {@code adas.read-timeout-ms} it ran against, plus the remedy. It never carries a body: there was none, and
 * the path is taken without its query string. It is in {@link ErrorMessages#classify}'s curated list, so the
 * text survives classification into a failure line or a run's {@code last_error}.
 */
@Getter
public class AdasTransportException extends ResourceAccessException {

    /** The property the message names, so an operator can find the knob without reading code. */
    public static final String READ_TIMEOUT_PROPERTY = "adas.read-timeout-ms";

    private static final Pattern PIPELINE_PATH = Pattern.compile("^/v1/pipelines/([^/]+)(/.*)?$");

    /** How long the call waited before it failed. */
    private final long elapsedMs;

    private AdasTransportException(String message, long elapsedMs, IOException cause) {
        super(message, cause);
        this.elapsedMs = elapsedMs;
    }

    /**
     * Builds the failure for one call.
     *
     * @param method        the request method
     * @param path          the request path, without its query string
     * @param elapsedMs     how long the call waited before it failed
     * @param readTimeoutMs the configured {@code adas.read-timeout-ms}
     * @param cause         the transport failure
     */
    public static AdasTransportException of(HttpMethod method, String path, long elapsedMs, int readTimeoutMs,
                                            IOException cause) {
        String call = "ADAS " + method + " " + path + pipelineSuffix(path);
        String message;
        if (cause instanceof SocketTimeoutException && elapsedMs >= readTimeoutMs) {
            // Decided by the time waited, not the exception text: the JDK uses one type for connect and read timeouts.
            message = call + " sent no response within " + elapsedMs + "ms (" + READ_TIMEOUT_PROPERTY + "="
                    + readTimeoutMs + "). ADAS may still complete the request; the work retries and a replayed write "
                    + "collapses on the grain key. If this repeats, look for a slow call in ADAS, lower the pipeline's "
                    + "advanced.rows_per_scan, or raise " + READ_TIMEOUT_PROPERTY;
        } else {
            message = call + " failed without an HTTP response after " + elapsedMs + "ms (" + READ_TIMEOUT_PROPERTY
                    + "=" + readTimeoutMs + "): " + CallTimingLog.describe(cause) + ". The work retries; if this repeats, check that "
                    + "ADAS is reachable at adas.base-url";
        }
        return new AdasTransportException(message, elapsedMs, cause);
    }

    private static String pipelineSuffix(String path) {
        Matcher matcher = PIPELINE_PATH.matcher(path == null ? "" : path);
        return matcher.matches() ? " for pipeline " + matcher.group(1) : "";
    }
}
