package com.epam.aidial.enrichment.adas;

import com.epam.aidial.enrichment.configuration.AdasClientConfiguration;
import com.epam.aidial.enrichment.configuration.AdasProperties;
import com.epam.aidial.enrichment.exception.AdasContractMismatchException;
import com.epam.aidial.enrichment.exception.AdasTransportException;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.support.AdasContractEcho;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.CapturingAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interceptor against a real {@code SimpleClientHttpRequestFactory}, the production request factory. That
 * factory never reads the status line of a request with a body, so a POST timeout used to reach the echo check as
 * an empty header set and be recorded as contract skew (F1). Everything here is about telling a response ADAS
 * produced from one it did not.
 */
class AdasContractInterceptorTest {

    private static final int READ_TIMEOUT_MS = 300;
    private static final String PIPELINE = "p1";
    private static final String ROWS_PATH = "/v1/pipelines/" + PIPELINE + "/rows";

    private static final AdasContractEcho ECHO = new AdasContractEcho();
    private static final WireMockServer ADAS = AdasWireMock.server(ECHO);

    private final AdasCompatibilityState compatibilityState = new AdasCompatibilityState();
    private RestClient restClient;
    private CapturingAppender appender;

    @BeforeAll
    static void startAdas() {
        ADAS.start();
    }

    @AfterAll
    static void stopAdas() {
        ADAS.stop();
    }

    @BeforeEach
    void setUp() {
        ADAS.resetAll();
        ECHO.reset();
        AdasProperties properties = new AdasProperties(ADAS.baseUrl(), "", 1000, READ_TIMEOUT_MS);
        restClient = new AdasClientConfiguration().adasRestClient(properties,
                new AdasContractInterceptor(compatibilityState, properties));
        appender = CapturingAppender.attachAtTrace();
    }

    @AfterEach
    void tearDown() {
        appender.detach(Level.INFO);
    }

    @Test
    void writeThatTimesOutIsTransportFailureAndNotSkew() {
        ADAS.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(okJson("{\"inserted\":1}").withFixedDelay(READ_TIMEOUT_MS * 3)));

        assertThatThrownBy(this::postRows)
                .isInstanceOf(AdasTransportException.class)
                .isInstanceOf(ResourceAccessException.class)
                .isNotInstanceOf(AdasContractMismatchException.class)
                .hasMessageContaining("ADAS POST " + ROWS_PATH)
                .hasMessageContaining("for pipeline " + PIPELINE)
                .hasMessageContaining("sent no response within")
                .hasMessageContaining("adas.read-timeout-ms=" + READ_TIMEOUT_MS);

        assertThat(compatibilityState.isCompatible()).isTrue();
        assertThat(appender.messages()).anySatisfy(m -> assertThat(m)
                .contains("ADAS POST " + ROWS_PATH + " failed in transport after")
                .contains("adas.read-timeout-ms=" + READ_TIMEOUT_MS));
        assertThat(appender.containsText("ADAS is incompatible")).isFalse();
    }

    @Test
    void theTransportFailureSurvivesClassification() {
        ADAS.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(okJson("{}").withFixedDelay(READ_TIMEOUT_MS * 3)));

        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(this::postRows);

        assertThat(ErrorMessages.classify(failure)).contains("sent no response within").contains("adas.read-timeout-ms");
    }

    @Test
    void resetConnectionIsTransportFailureWithoutHttpResponse() {
        ADAS.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(this::postRows)
                .isInstanceOf(AdasTransportException.class)
                .hasMessageContaining("failed without an HTTP response")
                .hasMessageContaining("adas.read-timeout-ms=" + READ_TIMEOUT_MS);
        assertThat(compatibilityState.isCompatible()).isTrue();
    }

    @Test
    void gatewayErrorWithoutTheEchoLeavesCompatibleStateCompatible() {
        ECHO.serve(null);
        ADAS.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(aResponse().withStatus(502)));

        assertThatThrownBy(this::postRows)
                .isInstanceOf(HttpServerErrorException.class)
                .isNotInstanceOf(AdasContractMismatchException.class);

        assertThat(compatibilityState.isCompatible()).isTrue();
        assertThat(appender.containsText("ADAS POST " + ROWS_PATH + " -> 502 in")).isTrue();
    }

    /** "Unchanged" cuts both ways: a gateway error is not evidence of recovery either. */
    @Test
    void gatewayErrorLeavesIncompatibleStateIncompatible() {
        compatibilityState.markIncompatible("no Adas-Contract-Version echo");
        ADAS.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(this::postRows).isInstanceOf(HttpServerErrorException.class);

        assertThat(compatibilityState.isCompatible()).isFalse();
    }

    @Test
    void otherServerErrorWithoutTheEchoIsNoVerdict() {
        ECHO.serve(null);
        ADAS.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(this::postRows)
                .isInstanceOf(HttpServerErrorException.class)
                .isNotInstanceOf(AdasContractMismatchException.class);
        assertThat(compatibilityState.isCompatible()).isTrue();
    }

    /** A non-gateway response is still checked: a 4xx without the echo is an older ADAS. */
    @Test
    void clientErrorWithoutTheEchoIsStillSkew() {
        ECHO.serve(null);
        ADAS.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(this::postRows).isInstanceOf(AdasContractMismatchException.class);
        assertThat(compatibilityState.isCompatible()).isFalse();
    }

    @Test
    void callOverHalfTheReadTimeoutWarnsWithItsDuration() {
        ADAS.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(okJson("{\"inserted\":1}").withFixedDelay(READ_TIMEOUT_MS * 2 / 3)));

        postRows();

        assertThat(appender.messages()).anySatisfy(m -> assertThat(m)
                .startsWith("ADAS POST " + ROWS_PATH + " -> 200 in ")
                .contains("over half of adas.read-timeout-ms=" + READ_TIMEOUT_MS));
        assertThat(compatibilityState.isCompatible()).isTrue();
    }

    @Test
    void ordinaryCallIsLoggedWithoutItsQueryStringOrBody() {
        ADAS.stubFor(get(urlPathEqualTo("/v1/pipelines")).willReturn(okJson("{\"pipelines\":[],\"marker\":\"body-secret\"}")));

        // The ordinary line is DEBUG; an application context loaded earlier in the same JVM may have pinned this
        // package above that, so the level is set for this logger alone and put back afterwards.
        String loggerName = AdasContractInterceptor.class.getName();
        Level previous = LogManager.getLogger(loggerName).getLevel();
        Configurator.setLevel(loggerName, Level.DEBUG);
        try {
            restClient.get().uri("/v1/pipelines?kind=enrich&view=compiled").retrieve().body(String.class);
        } finally {
            Configurator.setLevel(loggerName, previous);
        }

        assertThat(appender.messages()).anySatisfy(m -> assertThat(m).startsWith("ADAS GET /v1/pipelines -> 200 in "));
        assertThat(callLines()).isNotEmpty().noneMatch(m -> m.contains("kind=enrich"));
        assertThat(callLines()).noneMatch(m -> m.contains("body-secret"));
    }

    /**
     * Pinned as a literal, not as {@link AdasContract#VERSION_VALUE}: the far side's constant is {@code 4} since
     * ADAS aligned row-grain enrichments, and a test reading the constant back would pass whatever it said.
     */
    @Test
    void everyCallDeclaresContractVersionFour() {
        ADAS.stubFor(post(urlPathEqualTo(ROWS_PATH)).willReturn(okJson("{\"inserted\":1}")));

        postRows();

        assertThat(AdasContract.VERSION).isEqualTo(4);
        ADAS.verify(postRequestedFor(urlPathEqualTo(ROWS_PATH)).withHeader(AdasContract.HEADER, equalTo("4")));
        assertThat(compatibilityState.isCompatible()).isTrue();
    }

    private void postRows() {
        restClient.post().uri(ROWS_PATH).contentType(MediaType.APPLICATION_JSON)
                .body("{\"rows\":[{\"event_id\":\"request-secret\"}]}")
                .retrieve().body(String.class);
    }

    /**
     * Only this client's call lines. The test runs without the application's logging config, so Spring's own
     * converter DEBUG lines (which do echo bodies, and which the XML pins at INFO in the application) are captured too.
     */
    private List<String> callLines() {
        return appender.messages().stream().filter(m -> m != null && m.startsWith("ADAS ")).toList();
    }
}
