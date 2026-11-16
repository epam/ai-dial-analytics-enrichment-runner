package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.adas.dto.OutputDto;
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
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code dial_app_route} preset: the rendered body plus {@code params} is POSTed to the application's route with
 * no {@code response_format}, and the output is assembled from the reply body by each output's {@code source}, or its
 * name when it declares none. There is no repair, because a route's body has no conversation to
 * append a correction to, and usage is counted as one call, because an application reports usage in a shape of its
 * own.
 *
 * <p>Every way the reply can fail to yield an output — a body that is not a JSON object, a {@code source} that fails —
 * is a validation failure carrying the <b>raw</b> body, which is what an operator needs to fix the mapping.
 */
@Component
@RequiredArgsConstructor
class AppRouteCall implements DialCall {

    private final DialClient dialClient;
    private final DialRequestBuilder requestBuilder;
    private final JsonataEvaluator jsonata;
    private final JsonMapper jsonMapper;

    @Override
    public TransformPreset preset() {
        return TransformPreset.DIAL_APP_ROUTE;
    }

    @Override
    public String usageUnit() {
        return SpendService.CALLS_UNIT;
    }

    /** The rendered body plus {@code params}; the schema is not sent, since a route takes no {@code response_format}. */
    @Override
    public String body(String renderedTemplate, TransformDto transform, Map<String, Object> responseSchema) {
        return requestBuilder.build(renderedTemplate, transform.params(), null);
    }

    @Override
    public String call(TransformDto transform, String body) {
        return dialClient.appRoute(transform.model(), transform.route(), body);
    }

    /**
     * Assembles the output from the route's body, one output at a time: each output's value is its {@code source}
     * evaluated over the body, or the body member of its own name when it declares none. A value that evaluates to
     * nothing is left out, so the schema decides whether that output may be absent. Only the assembled object is
     * validated, so members of the body no output reads never reach validation.
     */
    @Override
    public Reply read(PipelineDto pipeline, String raw) {
        Object parsed;
        try {
            parsed = jsonMapper.readValue(raw, Object.class);
        } catch (RuntimeException e) {
            throw new ResponseValidationException("route response is not valid JSON", raw);
        }
        if (!(parsed instanceof Map<?, ?> body)) {
            throw new ResponseValidationException("route response is not a JSON object", raw);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        for (OutputDto declared : pipeline.outputs()) {
            Object value = valueOf(declared, body, parsed, raw);
            if (value != null) {
                output.put(declared.name(), value);
            }
        }
        return new Reply(jsonMapper.writeValueAsString(output), raw, 0);
    }

    private Object valueOf(OutputDto declared, Map<?, ?> body, Object parsed, String raw) {
        if (StringUtils.isBlank(declared.source())) {
            return body.get(declared.name());
        }
        try {
            return jsonata.evaluate(declared.source(), parsed);
        } catch (RuntimeException e) {
            throw new ResponseValidationException("source of output '" + declared.name()
                    + "' failed on the route response: " + ErrorMessages.classify(e), raw);
        }
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
