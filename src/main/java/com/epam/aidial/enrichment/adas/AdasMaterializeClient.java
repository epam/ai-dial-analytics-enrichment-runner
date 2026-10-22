package com.epam.aidial.enrichment.adas;

import com.epam.aidial.enrichment.adas.dto.MaterializeRequest;
import com.epam.aidial.enrichment.adas.dto.MaterializeResponse;
import com.epam.aidial.enrichment.configuration.AdasClientConfiguration;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the Class-1 materialize call ({@code POST /v1/pipelines/{name}/materialize}). The runner sends only
 * cursor, limit and an optional window; ADAS resolves the pipeline's source, filter, output expressions,
 * grain and safety-lag server-side, probes the window, then writes it with one {@code INSERT … SELECT}.
 * No row payload crosses the wire in either direction.
 *
 * <p>A blank body is treated as a failure rather than "zero rows written": silently accepting it would
 * advance the cursor past a window the runner cannot confirm was written.
 */
@Component
@RequiredArgsConstructor
@LogExecution
public class AdasMaterializeClient {

    @Qualifier(AdasClientConfiguration.ADAS_REST_CLIENT)
    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public MaterializeResponse materialize(String pipelineName, String cursor, Integer limit) {
        return materialize(pipelineName, new MaterializeRequest(cursor, limit));
    }

    public MaterializeResponse materialize(String pipelineName, MaterializeRequest request) {
        String requestJson = jsonMapper.writeValueAsString(request);
        String body = restClient.post()
                .uri("/v1/pipelines/{name}/materialize", pipelineName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestJson)
                .retrieve()
                .body(String.class);
        if (StringUtils.isBlank(body)) {
            throw new IllegalStateException("Empty materialize response for pipeline " + pipelineName);
        }
        return jsonMapper.readValue(body, MaterializeResponse.class);
    }
}
