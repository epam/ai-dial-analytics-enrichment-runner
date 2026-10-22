package com.epam.aidial.enrichment.adas.dto;

/**
 * One resolved output on the pipeline's compiled projection: which model response property lands in which
 * physical enrichment column, and the transform applied on the way.
 *
 * <p>This is the fold of two lists into one. The feed used to carry {@code output_bindings}
 * ({@code column} → {@code var}) and the evaluator's {@code output_vars} ({@code name} → {@code jsonata}),
 * and a consumer had to join them by name — which the runner did, per row, into a throwaway map. The join
 * has one correct answer and one place that knows it, so ADAS now does it once at resolution.
 *
 * <p><strong>{@code name} and {@code column} are two facts and must not be collapsed.</strong> {@code name}
 * is the property the model's JSON response carries: the target column's <em>exposed</em> name, and what the
 * composed {@code response_schema} declares. {@code column} is the physical column written — ADAS rewrites a
 * binding to the physical spelling before storing it. They are often equal in practice and equal by
 * construction on none, so inferring either from the other writes to a column that does not exist, or reads
 * a property that was never declared.
 *
 * @param name    the model response property supplying the value
 * @param column  the physical enrichment column the value is written to
 * @param jsonata transform over the model response, absent when the value is read straight out of
 *                {@code response[name]} — the common case, and the only spelling of "no transform", since
 *                an identity expression is refused at registration
 */
public record OutputDto(
        String name,
        String column,
        String jsonata) {
}
