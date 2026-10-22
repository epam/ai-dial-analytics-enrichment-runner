package com.epam.aidial.enrichment.dial;

import com.epam.aidial.enrichment.configuration.DialProperties;
import com.epam.aidial.enrichment.observability.CallTimingLog;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Times every DIAL call and logs it through {@link CallTimingLog}: DEBUG normally, WARN when it took more than
 * half of {@code dial.read-timeout-ms}, was answered {@code 429} or {@code 5xx}, or failed in transport. There
 * is no contract echo on this client.
 *
 * <p>The status is read here, as on the ADAS client, because for a request with a body the HTTP client does
 * not read the status line itself, so a read timeout would otherwise surface only later, in body extraction.
 * The {@code IOException} is rethrown unchanged: {@code RestClient} wraps it as a {@code ResourceAccessException},
 * which the work lanes already classify as a transient failure in their {@code DIAL_CALL} stage.
 */
@Slf4j
public class DialCallTimingInterceptor implements ClientHttpRequestInterceptor {

    public static final String READ_TIMEOUT_PROPERTY = "dial.read-timeout-ms";

    private final CallTimingLog timing;

    public DialCallTimingInterceptor(DialProperties properties) {
        this.timing = new CallTimingLog(log, "DIAL", READ_TIMEOUT_PROPERTY, properties.readTimeoutMs());
    }

    @Override
    public @NonNull ClientHttpResponse intercept(@NonNull HttpRequest request, byte @NonNull [] body,
                                                 @NonNull ClientHttpRequestExecution execution) throws IOException {
        return timing.execute(request, body, execution, (failure, elapsedMs) -> {
            throw failure;
        });
    }
}
