package com.epam.aidial.enrichment.adas;

import com.epam.aidial.enrichment.adas.dto.WriteRowsResponse;
import com.epam.aidial.enrichment.configuration.AdasClientConfiguration;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Writes enrichment results back through ADAS's pipeline-scoped endpoint
 * ({@code POST /v1/pipelines/{name}/rows}). The runner sends grain key + declared output columns +
 * provenance and never names a table; ADAS resolves the target, validates the columns, and stamps
 * {@code _updated_at}. A single insert is atomic in ClickHouse, so a chunk upsert is all-or-nothing
 * and safe to retry (the grain-key RMT collapses duplicates).
 */
@Component
@RequiredArgsConstructor
@LogExecution
public class AdasUpsertClient {

    @Qualifier(AdasClientConfiguration.ADAS_REST_CLIENT)
    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public int upsert(String pipelineName, List<Map<String, Object>> rows) {
        String requestJson = jsonMapper.writeValueAsString(Map.of("rows", rows));
        String body = restClient.post()
                .uri("/v1/pipelines/{name}/rows", pipelineName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestJson)
                .retrieve()
                .body(String.class);
        if (StringUtils.isBlank(body)) {
            // A successful write must return a body; a blank one is an anomaly, not "0 inserted"
            // (mirrors AdasScanClient rather than silently under-reporting).
            throw new IllegalStateException("ADAS returned a blank body for pipeline " + pipelineName + " write-back");
        }
        return jsonMapper.readValue(body, WriteRowsResponse.class).inserted();
    }
}
