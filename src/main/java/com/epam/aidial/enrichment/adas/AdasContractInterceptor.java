package com.epam.aidial.enrichment.adas;

import com.epam.aidial.enrichment.configuration.AdasProperties;
import com.epam.aidial.enrichment.exception.AdasContractMismatchException;
import com.epam.aidial.enrichment.exception.AdasTransportException;
import com.epam.aidial.enrichment.observability.CallTimingLog;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Declares this build's contract version on every ADAS request and verifies the echo on every ADAS
 * response, recording the verdict in {@link AdasCompatibilityState} as it goes.
 *
 * <p><b>One interceptor, not five clients.</b> Every {@code adas/*Client} shares the single
 * {@code RestClient} bean, so setting the header here covers a sixth client added later without its author
 * being told — and five places that must agree are five places that can drift, which the feed vocabulary
 * fold has already demonstrated once.
 *
 * <p><b>The check is on the response, not the request.</b> An unknown request header is silently ignored by
 * any Spring service, so nothing the runner sends can establish that the far side understood it; that is
 * precisely how a fold-era ADAS ignored {@code view=compiled}. An unknown response header is the one signal
 * an older ADAS cannot forge, so its absence is a fact rather than an inference.
 *
 * <p>ADAS sets the echo on every response under {@code /v1/pipelines}, <em>ahead of its security chain</em>,
 * which is where all five of this runner's call sites lie. Two consequences worth stating: a 401 from a
 * wrong API key still carries the echo and therefore stays an ordinary auth failure on the existing arm,
 * and a call site added under a different ADAS prefix would receive no echo and read as a skew — so adding
 * one is a change to both halves of the contract (recorded in {@code openspec/config.yaml}).
 *
 * <p><b>The status is read before any header, and only a response ADAS produced gets a verdict.</b> For a
 * request with a body, {@code SimpleClientHttpRequest} never reads the status line itself; after a read
 * timeout, header access then swallows the {@code SocketTimeoutException} and returns an empty set, which
 * looks exactly like an absent echo. Reading the status first surfaces the failure as an
 * {@link AdasTransportException} instead. A {@code 502}, {@code 503} or {@code 504}, or any other {@code 5xx}
 * without the echo, came from the ingress in front of ADAS and leaves the compatibility state as it was; it
 * reaches the caller as the server error it is.
 *
 * <p>It also times every call and logs it through {@link CallTimingLog}: DEBUG normally, WARN when slow,
 * throttled, failing or cut off in transport.
 */
@Slf4j
@Component
public class AdasContractInterceptor implements ClientHttpRequestInterceptor {

    private final AdasCompatibilityState compatibilityState;
    private final int readTimeoutMs;
    private final CallTimingLog timing;

    public AdasContractInterceptor(AdasCompatibilityState compatibilityState, AdasProperties properties) {
        this.compatibilityState = compatibilityState;
        this.readTimeoutMs = properties.readTimeoutMs();
        this.timing = new CallTimingLog(log, "ADAS", AdasTransportException.READ_TIMEOUT_PROPERTY, readTimeoutMs);
    }

    @Override
    public @NonNull ClientHttpResponse intercept(@NonNull HttpRequest request, byte @NonNull [] body,
                                                 @NonNull ClientHttpRequestExecution execution) throws IOException {
        request.getHeaders().set(AdasContract.HEADER, AdasContract.VERSION_VALUE);
        String uri = request.getURI().getPath();
        ClientHttpResponse response = timing.execute(request, body, execution, (failure, elapsedMs) -> {
            throw AdasTransportException.of(request.getMethod(), uri, elapsedMs, readTimeoutMs, failure);
        });
        // Already read by execute(), before any header, so a timed-out POST never reaches the echo check.
        HttpStatusCode status = response.getStatusCode();

        String echoed = response.getHeaders().getFirst(AdasContract.HEADER);
        if (isGatewayError(status, echoed)) {
            // Not ADAS's answer, so not a verdict: the state stays as it was and RestClient raises the 5xx.
            return response;
        }
        if (StringUtils.isBlank(echoed)) {
            throw refuse(response, AdasContractMismatchException.noEcho(uri));
        }
        if (!AdasContract.VERSION_VALUE.equals(echoed.trim())) {
            throw refuse(response, AdasContractMismatchException.mismatch(uri, echoed.trim()));
        }
        compatibilityState.markCompatible();
        return response;
    }

    /**
     * A gateway status, or any other 5xx that carries no echo. ADAS sets the echo on every response it produces
     * under the prefix, error dispatch included, so a 5xx without it came from in front of ADAS.
     */
    private static boolean isGatewayError(HttpStatusCode status, String echoed) {
        int code = status.value();
        if (code == HttpStatus.BAD_GATEWAY.value() || code == HttpStatus.SERVICE_UNAVAILABLE.value()
                || code == HttpStatus.GATEWAY_TIMEOUT.value()) {
            return true;
        }
        return status.is5xxServerError() && StringUtils.isBlank(echoed);
    }

    /**
     * Records the verdict and closes the response the caller will never read. Closing it matters: the
     * refusal is thrown before {@code RestClient} takes ownership of the body, so nothing else would
     * release the connection, and a skew produces one refusal per call for as long as it lasts.
     */
    private AdasContractMismatchException refuse(ClientHttpResponse response, AdasContractMismatchException refusal) {
        compatibilityState.markIncompatible(refusal.reason());
        response.close();
        return refusal;
    }
}
