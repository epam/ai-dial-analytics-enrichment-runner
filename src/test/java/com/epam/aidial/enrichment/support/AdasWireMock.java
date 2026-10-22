package com.epam.aidial.enrichment.support;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Builds the WireMock server that stands in for ADAS, with the contract echo its real counterpart sets.
 *
 * <p>Every test whose subject calls ADAS goes through this, so none of them has to remember the handshake
 * — and so a test that is <em>about</em> the handshake is the only one that mentions it.
 */
public final class AdasWireMock {

    private AdasWireMock() {
    }

    /** An ADAS matching this build's contract. */
    public static WireMockServer server() {
        return server(new AdasContractEcho());
    }

    /** The same, with the echo under the test's control so it can become a skewed ADAS mid-test. */
    public static WireMockServer server(AdasContractEcho echo) {
        return new WireMockServer(options().dynamicPort().extensions(echo));
    }
}
