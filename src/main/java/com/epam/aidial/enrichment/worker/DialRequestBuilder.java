package com.epam.aidial.enrichment.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the DIAL chat/completions request body: the rendered template (messages, etc.) overlaid
 * with the transform's {@code params} and a {@code response_format = json_schema} derived from the
 * feed entry's resolved top-level {@code response_schema}. Also builds the repair request by appending a
 * corrective user message carrying the schema validation errors. A route body, which takes no
 * {@code response_format}, is built by {@link #buildRouteBody}: it may be any JSON value, not only an object.
 *
 * <p>The {@code json_schema} name it sends is a label on the DIAL request and nothing to do with the ADAS
 * contract, so the evaluator fold does not move it: changing it would change what this service sends to
 * DIAL Core for no reason on either side.
 */
@Component
@RequiredArgsConstructor
public class DialRequestBuilder {

    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;

    public String build(String renderedTemplate, Map<String, Object> params, Map<String, Object> responseSchema) {
        Map<String, Object> body = parse(renderedTemplate);
        if (params != null) {
            body.putAll(params);
        }
        if (responseSchema != null) {
            body.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of("name", "evaluator_output", "schema", responseSchema, "strict", true)));
        }
        return jsonMapper.writeValueAsString(body);
    }

    /**
     * The body of an application-route call: the rendered template as it stands, whatever JSON value it is, so an
     * application whose route takes a bare array or scalar can be called with exactly that. {@code params} are
     * overlaid only on an object, since there is nowhere to put a member on any other value; a non-empty
     * {@code params} beside a body that is not an object is refused rather than silently dropped.
     */
    public String buildRouteBody(String renderedTemplate, Map<String, Object> params) {
        Object body = jsonMapper.readValue(renderedTemplate, Object.class);
        if (params == null || params.isEmpty()) {
            return jsonMapper.writeValueAsString(body);
        }
        if (!(body instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("transform.params can only be overlaid on a JSON object, but the rendered"
                    + " request_template is a JSON " + kindOf(body) + "; drop params or render an object");
        }
        Map<String, Object> object = parse(renderedTemplate);
        object.putAll(params);
        return jsonMapper.writeValueAsString(object);
    }

    private static String kindOf(Object value) {
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        return "null";
    }

    public String withCorrectiveMessage(String priorBodyJson, List<String> errors) {
        Map<String, Object> body = parse(priorBodyJson);
        Object messagesValue = body.get("messages");
        List<Object> messages = messagesValue instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
        messages.add(Map.of("role", "user", "content",
                "Your previous response did not match the required JSON schema: " + String.join("; ", errors)
                        + ". Respond ONLY with JSON that strictly matches the schema."));
        body.put("messages", messages);
        return jsonMapper.writeValueAsString(body);
    }

    private Map<String, Object> parse(String json) {
        return jsonMapper.readValue(json, OBJECT);
    }
}
