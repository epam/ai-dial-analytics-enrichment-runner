package com.epam.aidial.enrichment.adas;

import com.epam.aidial.enrichment.adas.dto.ScanRequest;
import com.epam.aidial.enrichment.adas.dto.ScanResponse;
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
 * Drives the ADAS pipeline-scoped incremental scan ({@code POST /v1/pipelines/{name}/scan}). The runner sends
 * only {@code {cursor, limit}}; ADAS resolves the pipeline's source, filter, projection, grain,
 * {@code final} and safety-lag server-side. The response body is parsed with the application
 * {@link JsonMapper}.
 */
@Component
@RequiredArgsConstructor
@LogExecution
public class AdasScanClient {

    @Qualifier(AdasClientConfiguration.ADAS_REST_CLIENT)
    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public ScanResponse scan(String pipelineName, String cursor, Integer limit) {
        return scan(pipelineName, new ScanRequest(cursor, limit));
    }

    /** The windowed form: a bounded backfill passes {@code from}/{@code to} through to ADAS on every page. */
    public ScanResponse scan(String pipelineName, ScanRequest request) {
        String requestJson = jsonMapper.writeValueAsString(request);
        String body = restClient.post()
                .uri("/v1/pipelines/{name}/scan", pipelineName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestJson)
                .retrieve()
                .body(String.class);
        if (StringUtils.isBlank(body)) {
            throw new IllegalStateException("Empty scan response for pipeline " + pipelineName);
        }
        return jsonMapper.readValue(body, ScanResponse.class);
    }
}
