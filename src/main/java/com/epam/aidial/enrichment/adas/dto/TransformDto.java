package com.epam.aidial.enrichment.adas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * The transform an {@code enrich} pipeline declares — what it runs, and what that run reads. Served by both
 * of ADAS's projections exactly as the operator authored it, which is the difference that matters: this is
 * no longer a resolved registry object the feed inlined, it is the declaration itself. The evaluator fold
 * dissolved the registry into the pipeline, so there is nothing left to pin and nothing left to join.
 *
 * <p><strong>{@link #inputs} is the variable map, not the read source.</strong> The pipeline's top-level
 * {@code inputs} is the table the runner scans; this one is what the request template renders from, keyed by
 * variable name. The rename created the collision and the two are distinguishable by JSON type as well as
 * position — the read source is an array of table names, this is an object keyed by variable name — so a
 * member read at the wrong level fails to bind rather than binding to the wrong thing.
 *
 * <p><strong>The schema the model is held to is not here.</strong> A member of this name exists on ADAS's
 * wire and means only "the schema this declaration <em>stores</em>", absent whenever the declaration stores
 * none. The schema the runner puts on a DIAL request is the <em>derived</em> top-level
 * {@link PipelineDto#responseSchema()}, which ADAS serves resolved — the stored one where there is one, and
 * otherwise the schema composed from the target and the transform's outputs. Reading it from inside this
 * block would send an unconstrained call for every pipeline that stores no schema of its own, which is the
 * exact failure the admission gate's response-schema arm exists to prevent.
 *
 * @param type           the grammar selector: {@code llm} routes to the model-calling lanes, {@code sql} to
 *                       the Class-1 materialize tick
 * @param preset         the request-shaping preset ({@code llm} only)
 * @param model          the DIAL deployment called ({@code llm} only)
 * @param params         preset parameters overlaid onto the rendered request body ({@code llm} only)
 * @param requestTemplate the JSON request body rendered per row or per group ({@code llm} only)
 * @param inputs         what the transform receives, keyed by variable name — the key <em>is</em> the name.
 *                       Absent for a {@code sql} transform, which renders no request; the admission gate
 *                       refuses one that declares any
 * @param outputs        what the transform produces, keyed by the target column that receives it, exactly as
 *                       authored. Modelled as opaque values because the runner never interprets them: a
 *                       {@code sql} output is a bare expression string ADAS evaluates in ClickHouse, and an
 *                       {@code llm} output's prose and value list are ADAS's schema-composition inputs. What
 *                       the runner executes is the <em>resolved</em> {@link PipelineDto#outputs()} list
 */
public record TransformDto(
        TransformType type,
        TransformPreset preset,
        String model,
        Map<String, Object> params,
        @JsonProperty("request_template") String requestTemplate,
        Map<String, VarDto> inputs,
        Map<String, Object> outputs) {
}
