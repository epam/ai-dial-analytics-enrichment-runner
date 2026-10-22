package com.epam.aidial.enrichment.adas;

import com.epam.aidial.enrichment.adas.dto.GroupFetchRequest;
import com.epam.aidial.enrichment.adas.dto.GroupFetchResponse;
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
 * Assembles a group's member rows through ADAS ({@code POST /v1/pipelines/{name}/group-rows}). The runner sends
 * only the group key and a row limit — it never names a table, a column set or an ordering — and ADAS
 * re-checks the sensitive-column entitlement against the calling identity on the way out.
 */
@Component
@RequiredArgsConstructor
@LogExecution
public class AdasGroupFetchClient {

    @Qualifier(AdasClientConfiguration.ADAS_REST_CLIENT)
    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public GroupFetchResponse fetch(String pipelineName, String groupKey, Integer limit) {
        String requestJson = jsonMapper.writeValueAsString(new GroupFetchRequest(groupKey, limit));
        String body = restClient.post()
                .uri("/v1/pipelines/{name}/group-rows", pipelineName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestJson)
                .retrieve()
                .body(String.class);
        if (StringUtils.isBlank(body)) {
            // Not "an empty group": ADAS answers that with a 200 and an empty rows array. A blank body is a
            // broken response, and treating it as an empty group would stamp the group clean and label
            // nothing.
            throw new IllegalStateException("Empty group-rows response for pipeline " + pipelineName);
        }
        return jsonMapper.readValue(body, GroupFetchResponse.class);
    }
}
