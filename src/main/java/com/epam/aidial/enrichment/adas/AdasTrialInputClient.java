package com.epam.aidial.enrichment.adas;

import com.epam.aidial.enrichment.adas.dto.TrialInputRequest;
import com.epam.aidial.enrichment.adas.dto.TrialInputResponse;
import com.epam.aidial.enrichment.configuration.AdasClientConfiguration;
import com.epam.aidial.enrichment.configuration.AdasProperties;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the input of a dry run through ADAS ({@code POST /v1/pipelines/{name}/trial-input}) as the person who asked for
 * it: the caller's own credential is sent instead of the runner's service {@code Api-Key}, so ADAS applies that
 * person's column and row restrictions, the arm gate and the {@code app-routes} allowlist. Only when there is no
 * caller credential — a runner admitting unauthenticated callers — is the service key sent, since there is then no
 * caller identity to preserve.
 */
@Component
@LogExecution
public class AdasTrialInputClient {

    private final RestClient restClient;
    private final AdasProperties properties;
    private final JsonMapper jsonMapper;

    public AdasTrialInputClient(@Qualifier(AdasClientConfiguration.ADAS_CALLER_REST_CLIENT) RestClient restClient,
                                AdasProperties properties, JsonMapper jsonMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    public TrialInputResponse fetch(String pipelineName, TrialInputRequest request, InboundCredential caller) {
        RestClient.RequestBodySpec call = restClient.post()
                .uri("/v1/pipelines/{name}/trial-input", pipelineName)
                .contentType(MediaType.APPLICATION_JSON);
        if (caller != null) {
            call = call.header(caller.header(), caller.value());
        } else if (StringUtils.isNotBlank(properties.apiKey())) {
            call = call.header(AdasClientConfiguration.API_KEY_HEADER, properties.apiKey());
        }
        String body = call.body(jsonMapper.writeValueAsString(request)).retrieve().body(String.class);
        if (StringUtils.isBlank(body)) {
            throw new IllegalStateException("Empty trial-input response for pipeline " + pipelineName);
        }
        return jsonMapper.readValue(body, TrialInputResponse.class);
    }
}
