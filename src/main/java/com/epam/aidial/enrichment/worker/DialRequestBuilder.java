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
 * corrective user message carrying the schema validation errors.
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
