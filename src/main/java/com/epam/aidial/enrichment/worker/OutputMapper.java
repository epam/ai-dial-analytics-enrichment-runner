package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.adas.dto.OutputDto;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.exception.OutputMappingException;
import com.epam.aidial.enrichment.expression.JsonataEvaluator;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the model's validated structured response to physical enrichment columns. The pipeline's
 * {@code outputs} is already resolved — one ordered entry per output carrying the response property, the
 * physical column and an optional transform — so this walks it once: evaluate {@code jsonata} over the
 * response when present, else read {@code response[name]}, and key the value to {@code column}. A JSONata
 * evaluation error is raised as an {@link OutputMappingException} for per-row dead-lettering.
 *
 * <p>There is no join here, and there must not be one. The feed used to carry the bindings and the
 * evaluator's output variables as two lists that had to be matched by name, per row, into a throwaway map;
 * ADAS now does that once at resolution.
 *
 * <p>{@code name} and {@code column} are <strong>not</strong> interchangeable, however equal they happen to
 * be on today's targets: {@code name} is the property the model's JSON carries — the column's exposed name,
 * and what the composed response schema declares — while {@code column} is the physical column written.
 * Reading one under the other's spelling writes nowhere, or reads a property that was never declared.
 */
@Component
@RequiredArgsConstructor
public class OutputMapper {

    private final JsonataEvaluator jsonataEvaluator;

    public Map<String, Object> map(PipelineDto pipeline, Object responseObject) {
        Map<String, Object> columns = new LinkedHashMap<>();
        if (pipeline.outputs() != null) {
            for (OutputDto output : pipeline.outputs()) {
                columns.put(output.column(), resolve(output, responseObject));
            }
        }
        return columns;
    }

    private Object resolve(OutputDto output, Object responseObject) {
        try {
            if (StringUtils.isNotBlank(output.jsonata())) {
                return jsonataEvaluator.evaluate(output.jsonata(), responseObject);
            }
            // An absent expression is the only spelling of "no transform" — an identity expression is
            // refused at registration — so the direct read below is the common path, not a fallback.
            if (responseObject instanceof Map<?, ?> map) {
                return map.get(output.name());
            }
            return null;
        } catch (RuntimeException e) {
            throw new OutputMappingException(output.name(), e);
        }
    }
}
