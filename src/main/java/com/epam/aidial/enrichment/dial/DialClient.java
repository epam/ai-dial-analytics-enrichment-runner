package com.epam.aidial.enrichment.dial;

import com.epam.aidial.enrichment.configuration.DialClientConfiguration;
import com.epam.aidial.enrichment.configuration.DialProperties;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls DIAL Core chat/completions for a deployment. The runner sends a fully-rendered request body
 * (messages + {@code response_format = json_schema} + the transform's params) and returns the raw response
 * body for the worker to parse. A non-2xx response surfaces as a
 * {@code RestClientResponseException} the worker routes per the failure taxonomy.
 *
 * <p>The call carries an {@code api-version} query parameter. DIAL Core does not consume it — it
 * forwards the caller's query string verbatim to the upstream adapter — and the
 * Azure-OpenAI-compatible adapters reject a request without it (HTTP 400
 * {@code api-version is a required query parameter}). Since that is a permanent failure, omitting it
 * dead-letters every row of a pipeline bound to such a deployment rather than degrading it, so the
 * parameter is sent by default and suppressed only when configured blank.
 */
@Component
@RequiredArgsConstructor
@LogExecution
public class DialClient {

    private static final String API_VERSION_PARAM = "api-version";

    @Qualifier(DialClientConfiguration.DIAL_REST_CLIENT)
    private final RestClient restClient;
    private final DialProperties properties;

    public String chatCompletion(String deployment, String requestBodyJson) {
        return restClient.post()
                .uri(uriBuilder -> {
                    uriBuilder.path("/openai/deployments/{deployment}/chat/completions");
                    if (StringUtils.isNotBlank(properties.apiVersion())) {
                        uriBuilder.queryParam(API_VERSION_PARAM, properties.apiVersion());
                    }
                    return uriBuilder.build(deployment);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBodyJson)
                .retrieve()
                .body(String.class);
    }
}
