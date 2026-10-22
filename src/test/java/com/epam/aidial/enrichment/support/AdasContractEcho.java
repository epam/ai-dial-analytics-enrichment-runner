package com.epam.aidial.enrichment.support;

import com.epam.aidial.enrichment.adas.AdasContract;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Models the half of the contract that lives in ADAS: a filter setting the {@link AdasContract#HEADER}
 * response header on <em>every</em> response under {@code /v1/pipelines}, whatever its status.
 *
 * <p>A response transformer rather than a per-stub {@code withHeader}, because the real thing is a filter
 * and behaves like one. Two consequences the per-stub form would get wrong, and both are asserted
 * elsewhere in the suite: an ADAS {@code 503} or {@code 422} still carries the echo, so it stays an
 * ordinary transient or permanent failure rather than being reclassified as a version skew; and a stub
 * added later is covered without its author being told.
 *
 * <p>{@link #serve} is how a test becomes the <em>other</em> side of a skew: {@code serve(null)} is an ADAS
 * that predates the contract and cannot forge the header, and any other value is one on the far side of a
 * bump.
 */
public final class AdasContractEcho implements ResponseTransformerV2 {

    /** What ADAS serves under {@code /v1/pipelines}; ADAS sets nothing elsewhere, and neither does this. */
    private static final String PIPELINES_PREFIX = "/v1/pipelines";

    private volatile String echo = AdasContract.VERSION_VALUE;

    /** Sets the version this stub ADAS echoes, or {@code null} for one that echoes nothing at all. */
    public void serve(String version) {
        this.echo = version;
    }

    /** Back to an ADAS matching this build — the state every test should start in. */
    public void reset() {
        this.echo = AdasContract.VERSION_VALUE;
    }

    @Override
    public String getName() {
        return "adas-contract-echo";
    }

    @Override
    public Response transform(Response response, ServeEvent serveEvent) {
        if (echo == null || !serveEvent.getRequest().getUrl().startsWith(PIPELINES_PREFIX)) {
            return response;
        }
        List<HttpHeader> headers = new ArrayList<>(response.getHeaders().all());
        headers.add(new HttpHeader(AdasContract.HEADER, echo));
        return Response.Builder.like(response).headers(new HttpHeaders(headers)).build();
    }
}
