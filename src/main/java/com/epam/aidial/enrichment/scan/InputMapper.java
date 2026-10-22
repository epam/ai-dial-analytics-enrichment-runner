package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.VarDto;
import com.epam.aidial.enrichment.exception.InputMappingException;
import com.epam.aidial.enrichment.expression.JsonataEvaluator;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extracts a pipeline's transform input variables from a scanned source row. The variables are the
 * pipeline's {@code transform.inputs} map: each entry's <em>key</em> is the variable name, and its value is
 * either a directly bound column (value = {@code row[column]}) or a JSONata expression evaluated over the
 * row. A JSONata evaluation error is raised as an {@link InputMappingException} so the offending row can be
 * dead-lettered at stage {@code input_map} without blocking its page.
 *
 * <p>The map moved from the entry's top level ({@code vars}) into the transform block with the evaluator
 * fold, and is read through {@link PipelineDto#transformInputs()} so the read is spelled once. It is
 * <em>not</em> the pipeline's top-level {@code inputs}, which is the table ADAS scans.
 *
 * <p>The result preserves the map's order, which is the pipeline's declaration order and therefore the
 * order the rendered prompt interpolates.
 *
 * <p>JSONata <em>syntax</em> is validated by ADAS at registration, so only evaluation errors reach
 * here. A variable that resolves to nothing yields {@code null}, which is passed through to the
 * evaluator template (an absent optional value, not an error).
 */
@Component
@RequiredArgsConstructor
public class InputMapper {

    private final JsonataEvaluator jsonataEvaluator;

    public Map<String, Object> map(PipelineDto pipeline, Map<String, Object> row) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        Map<String, VarDto> declared = pipeline.transformInputs();
        if (declared != null) {
            for (Map.Entry<String, VarDto> entry : declared.entrySet()) {
                inputs.put(entry.getKey(), resolve(entry.getKey(), entry.getValue(), row));
            }
        }
        return inputs;
    }

    private Object resolve(String name, VarDto var, Map<String, Object> row) {
        if (var == null) {
            return null;
        }
        if (StringUtils.isNotBlank(var.column())) {
            return row.get(var.column());
        }
        if (StringUtils.isNotBlank(var.jsonata())) {
            try {
                return jsonataEvaluator.evaluate(var.jsonata(), row);
            } catch (RuntimeException e) {
                throw new InputMappingException(name, e);
            }
        }
        return null;
    }
}
