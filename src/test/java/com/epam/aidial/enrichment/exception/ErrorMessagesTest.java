package com.epam.aidial.enrichment.exception;

import com.epam.aidial.enrichment.adas.AdasContract;
import com.epam.aidial.enrichment.dlq.DlqStage;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code last_error} is the first surface that would serve an exception's text, so it stores a classification
 * rather than a copy: an upstream body or a statement's bound values must never reach it.
 */
class ErrorMessagesTest {

    @Test
    void theRunnersOwnRefusalsKeepTheirCuratedText() {
        assertThat(ErrorMessages.classify(new ScanPageColumnException("p1", "event_id", "grain_key")))
                .contains("event_id");
        assertThat(ErrorMessages.classify(new UnaccountedScanPageException("p1", 3, 4)))
                .contains("4 row(s)");
    }

    /**
     * An ADAS transport failure is curated too, and survives the lanes' wrapping: its text is the diagnosis (call,
     * pipeline, time waited, the timeout setting), and a plain {@code ResourceAccessException} would say none of it.
     */
    @Test
    void adasTransportFailureKeepsItsCuratedTextThroughWrapper() {
        AdasTransportException timeout = AdasTransportException.of(HttpMethod.POST, "/v1/pipelines/p1/rows", 180_004, 180_000,
                new SocketTimeoutException("Read timed out"));

        String classified = ErrorMessages.classify(new TransientChunkException(DlqStage.UPSERT, timeout));

        assertThat(classified)
                .startsWith("ADAS POST /v1/pipelines/p1/rows for pipeline p1 sent no response within 180004ms")
                .contains("adas.read-timeout-ms=180000")
                // Not truncated: the remedy at the end is part of the diagnosis.
                .isEqualTo(timeout.getMessage());
    }

    @Test
    void anAdasRejectionStoresItsStatusAndCodeButNotItsBody() {
        String body = "{\"status\":422,\"error\":\"pipeline_validation_failed\","
                + "\"message\":\"column secret_ssn=123-45-6789 is not permitted\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.set(AdasContract.HEADER, AdasContract.VERSION_VALUE);
        HttpClientErrorException rejection = HttpClientErrorException.create(HttpStatus.valueOf(422),
                "Unprocessable", headers, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        String classified = ErrorMessages.classify(new IllegalStateException("wrapped", rejection));

        assertThat(classified).isEqualTo("ADAS 422 pipeline_validation_failed");
        assertThat(classified).doesNotContain("secret_ssn");
    }

    /** Only a response carrying the contract echo came from ADAS; a DIAL or ingress answer is named by status alone. */
    @Test
    void rejectionWithoutTheContractEchoIsNotLabelledAdas() {
        String body = "{\"error\":{\"message\":\"model overloaded for secret_ssn\",\"code\":\"429\"}}";
        HttpClientErrorException dial = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        HttpServerErrorException gateway = HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", null,
                "{\"error\":\"upstream_down\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThat(ErrorMessages.classify(dial)).isEqualTo("HTTP 429");
        assertThat(ErrorMessages.classify(gateway)).isEqualTo("HTTP 502");
    }

    @Test
    void anythingElseIsOnlyItsType() {
        assertThat(ErrorMessages.classify(new DataIntegrityViolationException("insert ... values ('secret')")))
                .isEqualTo("DataIntegrityViolationException");
        assertThat(ErrorMessages.classify(new ResourceAccessException("I/O error on POST http://adas")))
                .isEqualTo("ResourceAccessException");
        assertThat(ErrorMessages.classify(null)).isEqualTo("unknown failure");
    }

    @Test
    void wrapperNamesItsRootCauseTypeButNeverItsMessage() {
        RuntimeException wrapped = new RuntimeException("secret", new SocketTimeoutException("secret"));

        assertThat(ErrorMessages.classify(wrapped)).isEqualTo("RuntimeException: SocketTimeoutException");
    }

    @Test
    void anonymousFailureStillHasName() {
        RuntimeException anonymous = new RuntimeException("secret") {
        };

        assertThat(ErrorMessages.classify(anonymous)).isNotBlank().doesNotContain("secret");
    }

    @Test
    void classificationIsCapped() {
        String longColumn = "c".repeat(2000);
        assertThat(ErrorMessages.classify(new ScanPageColumnException("p1", longColumn, "grain_key")))
                .hasSize(ErrorMessages.MAX_CLASSIFIED_CHARS);
    }

    @Test
    void theSanitizerStillDropsStackFrames() {
        assertThat(ErrorMessages.sanitize("boom\n\tat com.foo.Bar(Bar.java:1)")).isEqualTo("boom");
    }
}
