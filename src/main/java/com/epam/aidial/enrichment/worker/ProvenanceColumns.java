package com.epam.aidial.enrichment.worker;

/**
 * The provenance columns the runner attaches to an enrichment row — lineage about what produced the label,
 * when, and what input it saw. ADAS permits these alongside a pipeline's declared outputs and its grain key,
 * and <em>silently drops</em> any that the target enrichment does not physically carry, so a row may always
 * carry all of them.
 *
 * <p><b>This is the set the runner <em>writes</em>, not the set ADAS permits.</b> The two differ by one
 * column since the evaluator fold: ADAS still permits {@code evaluator_version} on a target's schema, so the
 * values already stamped there stay readable, but it <em>refuses a write naming it</em> with HTTP 422. That
 * refusal is unlike the toleration above — a column the target merely lacks is dropped, whereas this one
 * fails the whole batch — so the retired spelling is absent here rather than kept "just in case". See
 * {@link #PIPELINE_GENERATION}.
 *
 * <p>Every name is deliberately un-prefixed: the {@code _} prefix is reserved for ADAS's own system columns,
 * which are not caller-settable at all.
 */
public final class ProvenanceColumns {

    /**
     * Which pipeline {@code generation} produced the row — the declaration revision the label was derived
     * from, typed {@code long} because a generation is a 64-bit counter in ADAS.
     *
     * <p>It replaced {@code evaluator_version}, and the retired spelling is <strong>not</strong> sent
     * alongside it as a hedge. Unlike a provenance column the target merely lacks, which ADAS drops
     * silently, ADAS refuses a row naming {@code evaluator_version} with HTTP 422 — whether or not this
     * column is beside it — so sending both fails the whole batch rather than covering both cases. ADAS
     * keeps the old column readable for the rows written before the fold; nothing writes it again.
     */
    public static final String PIPELINE_GENERATION = "pipeline_generation";

    /**
     * The retired spelling {@link #PIPELINE_GENERATION} replaced, named here <strong>only so it can be
     * removed</strong> — it is the one string in this class the runner never writes.
     *
     * <p>It exists because a write item enqueued before ADAS's evaluator fold can still be in the queue
     * when this build starts, and its rows carry this key. ADAS refuses a write naming it with HTTP 422,
     * so {@code UpsertService} strips it before the write and puts nothing in its place: those rows were
     * computed under the pre-fold declaration, and stamping them with the generation this process is
     * serving now would assert a lineage that did not produce them. A provenance column is optional on
     * ADAS's side, so an absent stamp is accepted, and it is the honest record.
     */
    public static final String RETIRED_EVALUATOR_VERSION = "evaluator_version";

    /** Which model produced the row. */
    public static final String MODEL = "model";

    /** When the row was produced. */
    public static final String ENRICHED_AT = "enriched_at";

    /**
     * Group grain only: the version of the group input the label was derived from. Lets a downstream reader
     * tell a label computed from a stale group from one computed from the current group, which the label
     * alone cannot express.
     */
    public static final String GROUP_VERSION = "group_version";

    /**
     * Group grain only: whether the input the label was derived from had been trimmed — by ADAS's assembly
     * caps or by the runner's own token budget. Without it a partial judgement reads as a complete one.
     */
    public static final String TRUNCATED = "truncated";

    private ProvenanceColumns() {
    }
}
