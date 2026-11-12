package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.TransformDto;
import com.epam.aidial.enrichment.adas.dto.TransformPreset;
import com.epam.aidial.enrichment.dial.DialClient;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.exception.ResponseValidationException;
import com.epam.aidial.enrichment.expression.JsonataEvaluator;
import com.epam.aidial.enrichment.spend.SpendService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code dial_app_route} preset: the rendered body plus {@code params} is POSTed to the application's route with
 * no {@code response_format}, and the whole reply body is the output, reshaped first by the transform's
 * {@code response_jsonata} when it declares one. There is no repair, because a route's body has no conversation to
 * append a correction to, and usage is counted as one call, because an application reports usage in a shape of its
 * own.
 *
 * <p>Every way the reply can fail to yield an output — a body that is not JSON, a mapping that fails or does not
 * produce an object — is a validation failure carrying the <b>raw</b> body, which is what an operator needs to fix
 * the mapping.
 */
@Component
@RequiredArgsConstructor
class AppRouteCall implements DialCall {

    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final DialClient dialClient;
    private final JsonataEvaluator jsonata;
    private final JsonMapper jsonMapper;

    @Override
    public TransformPreset preset() {
        return TransformPreset.DIAL_APP_ROUTE;
    }

    @Override
    public String body(String renderedTemplate, TransformDto transform, Map<String, Object> responseSchema) {
        Map<String, Object> body = jsonMapper.readValue(renderedTemplate, OBJECT);
        if (transform.params() != null) {
            body.putAll(transform.params());
        }
        return jsonMapper.writeValueAsString(body);
    }

    @Override
    public String call(TransformDto transform, String body) {
        return dialClient.appRoute(transform.model(), transform.route(), body);
    }

    @Override
    public Reply read(PipelineDto pipeline, TransformDto transform, String raw) {
        Object parsed;
        try {
            parsed = jsonMapper.readValue(raw, Object.class);
        } catch (RuntimeException e) {
            throw new ResponseValidationException("route response is not valid JSON", raw);
        }
        Object output = parsed;
        if (StringUtils.isNotBlank(transform.responseJsonata())) {
            try {
                output = jsonata.evaluate(transform.responseJsonata(), parsed);
            } catch (RuntimeException e) {
                throw new ResponseValidationException(
                        "response_jsonata failed on the route response: " + ErrorMessages.classify(e), raw);
            }
        }
        if (!(output instanceof Map<?, ?>)) {
            throw new ResponseValidationException(StringUtils.isNotBlank(transform.responseJsonata())
                    ? "response_jsonata did not produce a JSON object" : "route response is not a JSON object", raw);
        }
        return new Reply(jsonMapper.writeValueAsString(output), raw, 0, SpendService.CALLS_UNIT);
    }

    @Override
    public boolean repairs() {
        return false;
    }

    @Override
    public String withCorrection(String body, List<String> errors) {
        throw new UnsupportedOperationException("a route call is never repaired");
    }
}
