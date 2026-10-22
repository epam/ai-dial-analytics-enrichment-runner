package com.epam.aidial.enrichment.worker;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates the model's structured output against the feed entry's resolved response JSON Schema. Returns the
 * list of validation error messages (empty when valid); a non-empty result after the repair retry
 * dead-letters the row at stage {@code validate}.
 *
 * <p><strong>Every rejection is a returned message, never a thrown exception.</strong> That is the whole
 * contract this class owes its caller: {@code TransformInvoker.callWithRepair} drives the repair loop off
 * the returned list, so anything thrown from here skips the repair entirely and escapes into the worker's
 * last-resort {@code RuntimeException} catch — which dead-letters at a stage that has nothing to do with
 * validation and attaches no raw response. The one reply whose evidence matters most then arrives with
 * none of it.
 *
 * <p>Which is also why an entry with <em>no</em> response schema still has its reply parse-checked here
 * rather than only where it is parsed for output mapping: the mapper needs JSON either way, so a reply that
 * is not JSON is a rejection this gate owes the caller a message for whether a schema exists or not.
 */
@Component
@RequiredArgsConstructor
public class ResponseValidator {

    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private static final List<String> NOT_JSON = List.of("response is not valid JSON");

    /**
     * Compiled schemas reused across rows — compilation, not validation, is the expensive step, and this
     * ran once per row before. Keyed on the schema itself, so a re-declared transform gets its own entry and
     * two pipelines sharing a schema share one compilation; bounded by the distinct schemas the runner has
     * invoked, as {@link OutputMapper} is by its distinct JSONata expressions.
     */
    private final Map<Map<String, Object>, JsonSchema> compiled = new ConcurrentHashMap<>();

    private final JsonMapper jsonMapper;

    public List<String> validate(Map<String, Object> responseSchema, String content) {
        if (content == null) {
            return List.of("model returned no content");
        }
        if (responseSchema == null) {
            return isJson(content) ? List.of() : NOT_JSON;
        }
        JsonSchema schema = compiled.computeIfAbsent(responseSchema, this::compile);
        Set<ValidationMessage> messages;
        try {
            messages = schema.validate(content, InputFormat.JSON);
        } catch (IllegalArgumentException e) {
            // The library parses the string inside validate() and reports a parse failure by throwing
            // IllegalArgumentException("Invalid input") rather than by returning a message — the one input
            // it answers with an exception instead of a verdict. A reply that is not JSON at all is an
            // ordinary schema failure from the caller's point of view (the model was asked for JSON and did
            // not produce it), so it is reported as one: the repair prompt gets its attempt, and a failure
            // that survives the repair dead-letters at `validate` carrying the reply that could not be read.
            return NOT_JSON;
        }
        return messages.stream().map(ValidationMessage::getMessage).toList();
    }

    /** Compiled outside the validate try-block, so a broken schema is not misreported as a broken reply. */
    private JsonSchema compile(Map<String, Object> responseSchema) {
        return FACTORY.getSchema(jsonMapper.writeValueAsString(responseSchema));
    }

    private boolean isJson(String content) {
        try {
            jsonMapper.readTree(content);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
