package com.epam.aidial.enrichment.dial;

import com.epam.aidial.enrichment.configuration.DialClientConfiguration;
import com.epam.aidial.enrichment.configuration.DialProperties;
import com.epam.aidial.enrichment.support.CapturingAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The DIAL client's call line, through the production client configuration and request factory. */
class DialCallTimingInterceptorTest {

    private static final int READ_TIMEOUT_MS = 300;
    private static final String DEPLOYMENT = "gpt-4o";
    private static final String PATH = "/openai/deployments/" + DEPLOYMENT + "/chat/completions";

    private static final WireMockServer DIAL = new WireMockServer(options().dynamicPort());

    private DialClient dialClient;
    private CapturingAppender appender;

    @BeforeAll
    static void startDial() {
        DIAL.start();
    }

    @AfterAll
    static void stopDial() {
        DIAL.stop();
    }

    @BeforeEach
    void setUp() {
        DIAL.resetAll();
        DialProperties properties = new DialProperties(DIAL.baseUrl(), "", "2025-01-01-preview", 1000, READ_TIMEOUT_MS,
                60, Duration.ZERO);
        dialClient = new DialClient(new DialClientConfiguration().dialRestClient(properties), properties);
        appender = CapturingAppender.attachAtTrace();
    }

    @AfterEach
    void tearDown() {
        appender.detach(Level.INFO);
    }

    @Test
    void timeoutNamesTheCallTheElapsedTimeAndItsSetting() {
        DIAL.stubFor(post(urlPathEqualTo(PATH)).willReturn(okJson("{}").withFixedDelay(READ_TIMEOUT_MS * 3)));

        // Unchanged for the lanes: RestClient's ResourceAccessException, which they classify transient.
        assertThatThrownBy(() -> dialClient.chatCompletion(DEPLOYMENT, "{\"messages\":[\"prompt-secret\"]}"))
                .isInstanceOf(ResourceAccessException.class);

        assertThat(appender.messages()).anySatisfy(m -> assertThat(m)
                .startsWith("DIAL POST " + PATH + " failed in transport after ")
                .contains("dial.read-timeout-ms=" + READ_TIMEOUT_MS));
        assertThat(callLines()).isNotEmpty().noneMatch(m -> m.contains("prompt-secret"));
    }

    @Test
    void slowCallWarnsAndTheLineCarriesNoQueryString() {
        DIAL.stubFor(post(urlPathEqualTo(PATH)).willReturn(okJson("{\"marker\":\"body-secret\"}").withFixedDelay(READ_TIMEOUT_MS * 2 / 3)));

        dialClient.chatCompletion(DEPLOYMENT, "{}");

        assertThat(appender.messages()).anySatisfy(m -> assertThat(m)
                .startsWith("DIAL POST " + PATH + " -> 200 in ")
                .contains("over half of dial.read-timeout-ms=" + READ_TIMEOUT_MS));
        assertThat(callLines()).isNotEmpty().noneMatch(m -> m.contains("api-version"));
        assertThat(callLines()).noneMatch(m -> m.contains("body-secret"));
    }

    @Test
    void serverErrorIsLogged() {
        DIAL.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> dialClient.chatCompletion(DEPLOYMENT, "{}")).isInstanceOf(HttpServerErrorException.class);

        assertThat(appender.messages()).anySatisfy(m -> assertThat(m).startsWith("DIAL POST " + PATH + " -> 503 in "));
    }

    /**
     * Only this client's call lines. The test runs without the application's logging config, so Spring's own
     * converter DEBUG lines (which do echo bodies, and which the XML pins at INFO in the application) are captured too.
     */
    private List<String> callLines() {
        return appender.messages().stream().filter(m -> m != null && m.startsWith("DIAL ")).toList();
    }
}
