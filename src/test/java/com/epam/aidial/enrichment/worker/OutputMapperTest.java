package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.adas.dto.OutputDto;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.exception.OutputMappingException;
import com.epam.aidial.enrichment.expression.JsonataEvaluator;
import com.epam.aidial.enrichment.support.TestPipelines;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputMapperTest {

    private final OutputMapper mapper = new OutputMapper(new JsonataEvaluator());

    @Test
    void mapsViaTheOutputTransform() {
        PipelineDto pipeline = TestPipelines.builder("r1")
                .outputs(List.of(new OutputDto("label", "sentiment", "verdict")))
                .build();

        Map<String, Object> columns = mapper.map(pipeline, Map.of("verdict", "positive"));

        assertThat(columns).containsEntry("sentiment", "positive");
    }

    @Test
    void readsTheResponsePropertyWhenNoTransform() {
        // An absent expression is the only spelling of "no transform" (an identity one is refused at
        // registration), so this is the common path rather than a fallback.
        PipelineDto pipeline = TestPipelines.builder("r1")
                .outputs(List.of(new OutputDto("label", "sentiment", null)))
                .build();

        Map<String, Object> columns = mapper.map(pipeline, Map.of("label", "neutral"));

        assertThat(columns).containsEntry("sentiment", "neutral");
    }

    @Test
    void doesNotCollapseTheResponsePropertyAndThePhysicalColumn() {
        // The two are often equal in practice and equal by construction on none. A mapper that inferred
        // either from the other would write to a column that does not exist, or read a property that was
        // never declared — and would pass every other test here, since they all use equal pairs.
        PipelineDto pipeline = TestPipelines.builder("r1")
                .outputs(List.of(new OutputDto("model_logical", "model_physical", null)))
                .build();

        Map<String, Object> columns = mapper.map(pipeline, Map.of("model_logical", "gpt-4o"));

        assertThat(columns).containsExactly(Map.entry("model_physical", "gpt-4o"));
    }

    @Test
    void mapsEachOutputInBindingOrder() {
        PipelineDto pipeline = TestPipelines.builder("r1")
                .outputs(List.of(new OutputDto("title", "title", null),
                        new OutputDto("mood", "mood", "(mood = \"none\") ? null : mood")))
                .build();

        Map<String, Object> columns = mapper.map(pipeline, Map.of("title", "a chat", "mood", "none"));

        assertThat(columns).containsExactlyInAnyOrderEntriesOf(titledWithNullMood());
        assertThat(columns.keySet()).containsExactly("title", "mood");
    }

    /** {@code Map.of} and {@code Map.entry} both refuse a null value, and a nulled-out column is the point. */
    private static Map<String, Object> titledWithNullMood() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("title", "a chat");
        expected.put("mood", null);
        return expected;
    }

    @Test
    void jsonataEvaluationErrorBecomesOutputMappingException() {
        PipelineDto pipeline = TestPipelines.builder("r1")
                .outputs(List.of(new OutputDto("label", "sentiment", "$notAFunction()")))
                .build();

        assertThatThrownBy(() -> mapper.map(pipeline, Map.of("verdict", "x")))
                .isInstanceOf(OutputMappingException.class)
                .hasMessageContaining("label");
    }
}
