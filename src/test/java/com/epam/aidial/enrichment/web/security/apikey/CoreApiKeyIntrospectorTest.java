package com.epam.aidial.enrichment.web.security.apikey;

import com.epam.aidial.enrichment.configuration.JsonMapperConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Core introspection: the two response shapes, and the deliberate split between a rejected credential
 * ({@link BadCredentialsException} → 401) and an unreachable validator
 * ({@link AuthenticationServiceException} → 503). Also covers where {@code SECURITY_REQUIRE_EMAIL} applies,
 * and the opt-in startup probe.
 */
class CoreApiKeyIntrospectorTest {

    private static final WireMockServer CORE = new WireMockServer(options().dynamicPort());
    private static final String USER_INFO = "/v1/user/info";
    private static final JsonMapper MAPPER = new JsonMapperConfiguration().jsonMapper();

    @BeforeAll
    static void start() {
        CORE.start();
    }

    @AfterAll
    static void stop() {
        CORE.stop();
    }

    @BeforeEach
    void reset() {
        CORE.resetAll();
    }

    private static CoreApiKeyIntrospector introspector(boolean startupProbe) {
        return introspector(startupProbe, false);
    }

    private static CoreApiKeyIntrospector introspector(boolean startupProbe, boolean requireEmail) {
        return new CoreApiKeyIntrospector(RestClient.create(), MAPPER, CORE.baseUrl(),
                "oid", "unique_name", startupProbe, requireEmail);
    }

    @Test
    void readsTheProjectKeyShape() {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO))
                .willReturn(okJson("{\"project\":\"analytics\",\"roles\":[\"project-admin\"]}")));

        IntrospectionResult result = introspector(false).introspect("k");

        assertThat(result.fromProjectKey()).isTrue();
        assertThat(result.principal()).isEqualTo("analytics");
        assertThat(result.email()).isNull();
        assertThat(result.rawRoles()).containsExactly("project-admin");
    }

    @Test
    void readsTheUserClaimsShape() {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO))
                .willReturn(okJson("{\"userClaims\":{\"oid\":[\"user-1\"],\"unique_name\":[\"u@example.com\"]},"
                        + "\"roles\":[\"admin\"]}")));

        IntrospectionResult result = introspector(false).introspect("k");

        assertThat(result.fromProjectKey()).isFalse();
        assertThat(result.principal()).isEqualTo("user-1");
        assertThat(result.email()).isEqualTo("u@example.com");
        assertThat(result.rawRoles()).containsExactly("admin");
    }

    /** Core labels its JSON {@code application/octet-stream}; the body must still parse. */
    @Test
    void parsesBodyLabelledAsOctetStream() {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/octet-stream")
                .withBody("{\"project\":\"analytics\",\"roles\":[\"project-admin\"]}")));

        assertThat(introspector(false).introspect("k").principal()).isEqualTo("analytics");
    }

    @Test
    void treatsAnyErrorStatusAsRejectedKey() {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> introspector(false).introspect("k"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void treatsUnparseableBodyAsRejectedKey() {
        //noinspection JsonStandardCompliance — deliberately not JSON
        CORE.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(okJson("not json at all")));

        assertThatThrownBy(() -> introspector(false).introspect("k"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void treatsResponseWithNeitherShapeAsRejectedKey() {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(okJson("{\"roles\":[\"admin\"]}")));

        assertThatThrownBy(() -> introspector(false).introspect("k"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void treatsConnectionFailureAsUnavailableRatherThanRejected() {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> introspector(false).introspect("k"))
                .isInstanceOf(AuthenticationServiceException.class);
    }

    /**
     * {@code SECURITY_REQUIRE_EMAIL} covers the JWT-rooted shape, which is a user identity carrying the same
     * claims that user's JWT would, and must therefore be held to the same rule as a bare JWT.
     */
    @Test
    void rejectsJwtRootedKeyWithNoEmailWhenEmailIsRequired() {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO))
                .willReturn(okJson("{\"userClaims\":{\"oid\":[\"user-1\"]},\"roles\":[\"admin\"]}")));

        assertThatThrownBy(() -> introspector(false, true).introspect("k"))
                .isInstanceOf(BadCredentialsException.class);
    }

    /**
     * A project key is a service identity with no user behind it and Core returns no email for one, so
     * gating it on an email would make the flag silently incompatible with project-key authentication.
     */
    @Test
    void admitsProjectKeyWithNoEmailEvenWhenEmailIsRequired() {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO))
                .willReturn(okJson("{\"project\":\"analytics\",\"roles\":[\"project-admin\"]}")));

        IntrospectionResult result = introspector(false, true).introspect("k");

        assertThat(result.fromProjectKey()).isTrue();
        assertThat(result.email()).isNull();
    }

    @Test
    void admitsJwtRootedKeyWithAnEmailWhenEmailIsRequired() {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO))
                .willReturn(okJson("{\"userClaims\":{\"oid\":[\"user-1\"],\"unique_name\":[\"u@example.com\"]},"
                        + "\"roles\":[\"admin\"]}")));

        assertThat(introspector(false, true).introspect("k").email()).isEqualTo("u@example.com");
    }

    @Test
    void startupProbeSucceedsWhenCoreRejectsTheProbeKey() {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO)).willReturn(aResponse().withStatus(401)));

        assertThatCode(() -> introspector(true).probeCore()).doesNotThrowAnyException();
    }

    @Test
    void startupProbeFailsFastWhenCoreIsUnreachable() {
        CORE.stubFor(get(urlPathEqualTo(USER_INFO))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> introspector(true).probeCore())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API_KEY_STARTUP_PROBE=false");
    }

    @Test
    void startupProbeIsSkippedWhenDisabled() {
        // No stub registered at all: a probe that ran would fail.
        assertThatCode(() -> introspector(false).probeCore()).doesNotThrowAnyException();
    }
}
