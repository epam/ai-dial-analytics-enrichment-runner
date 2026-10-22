package com.epam.aidial.enrichment.adas.dto;

/**
 * One value of a pipeline's {@code transform.inputs} map: what a single transform input variable is taken
 * from. Exactly one member is set — a directly bound source column, or a JSONata expression over the scanned
 * row.
 *
 * <p>The variable's <em>name</em> is the map key, not a member here. That is the whole of what this map
 * folded together: it replaced the former {@code input_bindings} list and the evaluator's
 * {@code input_vars} at once, because the map's own keys are what drive template substitution and nothing
 * else ever read the second declaration. The evaluator fold then moved it from the entry's top level
 * ({@code vars}) into the transform block beside the outputs it pairs with; the value shape did not change,
 * only the path to it.
 *
 * <p>ADAS always echoes the object form, even where the declaration used the bare-string shorthand its
 * request surface accepts, so the runner never has to handle two spellings of one value.
 *
 * @param column  source column bound directly (null when {@code jsonata} is used)
 * @param jsonata JSONata over the scanned row (null when {@code column} is used)
 */
public record VarDto(
        String column,
        String jsonata) {
}
