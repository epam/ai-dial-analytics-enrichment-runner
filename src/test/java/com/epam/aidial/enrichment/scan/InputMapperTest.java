package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.VarDto;
import com.epam.aidial.enrichment.exception.InputMappingException;
import com.epam.aidial.enrichment.expression.JsonataEvaluator;
import com.epam.aidial.enrichment.support.TestPipelines;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputMapperTest {

    private final InputMapper mapper = new InputMapper(new JsonataEvaluator());

    @Test
    void resolvesBoundColumn() {
        PipelineDto pipeline = TestPipelines.withInputs("r1", TestPipelines.columnVar("prompt", "request_body"));

        Map<String, Object> inputs = mapper.map(pipeline, Map.of("request_body", "hello"));

        assertThat(inputs).containsEntry("prompt", "hello");
    }

    @Test
    void resolvesJsonataExpression() {
        PipelineDto pipeline = TestPipelines.withInputs("r1", Map.of("model", new VarDto(null, "deployment")));

        Map<String, Object> inputs = mapper.map(pipeline, Map.of("deployment", "gpt-4o"));

        assertThat(inputs).containsEntry("model", "gpt-4o");
    }

    @Test
    void keysTheInputsByTheTransformInputsMapKey() {
        // The variable name is the map key, not a member of the value — that is the whole of what this map
        // folded together, so a mapper reading a name from anywhere else would be reading the wrong thing.
        Map<String, VarDto> declared = new LinkedHashMap<>();
        declared.put("prompt", new VarDto("request_body", null));
        declared.put("model", new VarDto(null, "deployment"));
        PipelineDto pipeline = TestPipelines.withInputs("r1", declared);

        Map<String, Object> inputs = mapper.map(pipeline, Map.of("request_body", "hello", "deployment", "gpt-4o"));

        // Declaration order is the order the rendered prompt interpolates, so it is preserved.
        assertThat(inputs).containsExactly(Map.entry("prompt", "hello"), Map.entry("model", "gpt-4o"));
    }

    @Test
    void missingBoundColumnResolvesToNull() {
        PipelineDto pipeline = TestPipelines.withInputs("r1", TestPipelines.columnVar("prompt", "absent_column"));

        Map<String, Object> row = new HashMap<>();
        row.put("other", "x");
        Map<String, Object> inputs = mapper.map(pipeline, row);

        assertThat(inputs).containsKey("prompt");
        assertThat(inputs.get("prompt")).isNull();
    }

    @Test
    void jsonataEvaluationErrorBecomesInputMappingException() {
        PipelineDto pipeline = TestPipelines.withInputs("r1", Map.of("x", new VarDto(null, "$notAFunction()")));

        assertThatThrownBy(() -> mapper.map(pipeline, Map.of("a", 1)))
                .isInstanceOf(InputMappingException.class)
                .hasMessageContaining("x");
    }
}
