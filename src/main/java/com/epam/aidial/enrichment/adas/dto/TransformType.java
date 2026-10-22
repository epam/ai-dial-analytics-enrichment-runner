package com.epam.aidial.enrichment.adas.dto;

/**
 * The kind of transform a pipeline declares, mirroring ADAS's {@code transform.type}. This is the runner's
 * tick router: an {@link #LLM} (Class-2) pipeline runs the scan → chunk → DIAL → upsert path, a {@link #SQL}
 * (Class-1) pipeline runs the materialize tick. ADAS enforces the split — it rejects a {@code sql} pipeline
 * on the row scan and an {@code llm} pipeline on materialize.
 *
 * <p>The enum is <em>renamed</em> rather than replaced by the evaluator fold: the values still exist on the
 * wire, under the same lowercase spellings, and only the object that carried them is gone. What used to be
 * {@code evaluator.type} on an object the feed inlined is now {@code transform.type} on the declaration the
 * operator authored.
 */
public enum TransformType {

    /** Model-computed. Carries a model, preset, request template and (at the entry's top level) a schema. */
    LLM,

    /**
     * Database-computed (Class-1). Its expressions live in ADAS's stored declaration and are evaluated
     * server-side inside ClickHouse; the runner never interprets them and never sees a row payload. The
     * block carries no model, no preset, no params, no request template and no {@code inputs}.
     */
    SQL
}
