package com.epam.aidial.enrichment.worker;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule four call sites depend on, and which until now was only ever tested incidentally through them.
 *
 * <p>It decides whether an outbound failure is retried or dead-lettered, so both directions cost something
 * real: a permanent failure retried is a storm against a server already rejecting us, and a transient one
 * dead-lettered is work that needs a human to requeue. ClickHouse memory refusals were the second kind, and
 * that was fixed in ADAS — by reporting its own resource exhaustion as a 5xx — rather than here, so what this
 * class pins is that the plain status rule is enough and no `error`-code branching crept back in.
 */
class RetryableStatusTest {

    @Test
    void serverErrorsAreRetried() {
        assertThat(RetryableStatus.isRetryable(server(HttpStatus.INTERNAL_SERVER_ERROR))).isTrue();
        assertThat(RetryableStatus.isRetryable(server(HttpStatus.BAD_GATEWAY))).isTrue();
        assertThat(RetryableStatus.isRetryable(server(HttpStatus.GATEWAY_TIMEOUT))).isTrue();
    }

    /**
     * The one that matters most across the ADAS boundary. ADAS answers `503 clickhouse_unavailable` when
     * ClickHouse itself is out of memory or unreachable — transient by construction, because the identical
     * request succeeds once the load clears. Classifying it as permanent is what turned ordinary node
     * contention into dead letters.
     */
    @Test
    void anUpstreamOutOfResourcesIsRetried() {
        assertThat(RetryableStatus.isRetryable(server(HttpStatus.SERVICE_UNAVAILABLE))).isTrue();
    }

    /** The three "later" statuses outside the 5xx range: each is an explicit invitation to try again. */
    @Test
    void theTimeoutAndRateLimitStatusesAreRetried() {
        assertThat(RetryableStatus.isRetryable(client(HttpStatus.TOO_MANY_REQUESTS))).isTrue();
        assertThat(RetryableStatus.isRetryable(client(HttpStatus.REQUEST_TIMEOUT))).isTrue();
        assertThat(RetryableStatus.isRetryable(client(HttpStatus.TOO_EARLY))).isTrue();
    }

    /**
     * Rejections of the request as posed, and — deliberately — a `422`. ADAS uses it for a refusal by its
     * own read budget, which is deterministic: the identical read is refused identically until the
     * declaration, the table layout or the budget changes, so a retry cannot succeed and the item belongs
     * in the DLQ with its payload for a human to act on.
     */
    @Test
    void rejectionsOfTheRequestAreNotRetried() {
        assertThat(RetryableStatus.isRetryable(client(HttpStatus.BAD_REQUEST))).isFalse();
        assertThat(RetryableStatus.isRetryable(client(HttpStatus.UNAUTHORIZED))).isFalse();
        assertThat(RetryableStatus.isRetryable(client(HttpStatus.FORBIDDEN))).isFalse();
        assertThat(RetryableStatus.isRetryable(client(HttpStatus.NOT_FOUND))).isFalse();
        assertThat(RetryableStatus.isRetryable(client(HttpStatus.CONFLICT))).isFalse();
        assertThat(RetryableStatus.isRetryable(client(HttpStatus.UNPROCESSABLE_CONTENT))).isFalse();
    }

    /**
     * The status alone decides it. Passing a body that names a capacity condition must change nothing —
     * branching on an upstream's machine codes is forbidden by this capability's spec, because it would
     * make that vocabulary a contract both services have to change together.
     *
     * <p><b>This cannot fail against today's implementation</b>, which reads only the status and so
     * physically cannot see the body. It is here as a guard on the <em>next</em> edit: the forbidden change
     * is small, locally reasonable-looking, and would make this the only failing test.
     */
    @Test
    void theResponseBodyDoesNotInfluenceTheDecision() {
        final RestClientResponseException withBody = new HttpClientErrorException(
                HttpStatus.UNPROCESSABLE_CONTENT, "Unprocessable Content",
                "{\"error\":\"pipeline_read_budget_exceeded\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertThat(RetryableStatus.isRetryable(withBody)).isFalse();
    }

    private static RestClientResponseException client(HttpStatusCode status) {
        return new HttpClientErrorException(status);
    }

    private static RestClientResponseException server(HttpStatusCode status) {
        return new HttpServerErrorException(status);
    }
}
