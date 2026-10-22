package com.epam.aidial.enrichment.adas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * An {@code enrich}-kind pipeline as returned by the ADAS registry sync feed
 * ({@code GET /v1/pipelines?kind=enrich&enabled=true&view=compiled}), consumed read-only by the runner.
 * {@code grainKey}, {@code versionColumn}, {@code responseSchema} and {@code outputs} are derived by ADAS
 * from the target enrichment and the resolved input; {@code transform} is the declaration as authored.
 * {@code generation} is the change-detection token the runner diffs on (there is no ETag/304 on the feed).
 * Field names mirror the ADAS wire contract.
 *
 * <p><strong>The compiled projection, and only it.</strong> ADAS serves two projections of one stored
 * pipeline, differing in resolution and never in naming. {@code view=source} — the default — is the authored
 * declaration and carries no derived response schema, no grain key, no version column and no resolved
 * outputs; {@code view=compiled} is the same vocabulary with those filled in. The runner asks for
 * {@code compiled} explicitly, and everything below assumes it.
 *
 * <p><strong>The transform is the declaration, not a pinned object.</strong> The feed once inlined a
 * resolved {@code evaluator} — {@code type}, {@code model}, {@code preset}, {@code params},
 * {@code request_template} and a {@code response_schema} — alongside a top-level {@code vars} map and a
 * top-level {@code evaluator_version}. ADAS's evaluator fold dissolved the registry into the pipeline, so
 * every one of those facts now has exactly one spelling: the transform's own members on {@link #transform},
 * the variable map at {@code transform.inputs}, the resolved schema at {@link #responseSchema}, and the
 * declaration revision at {@link #generation}. None of the retired spellings is modelled here, and there is
 * deliberately no compatibility branch for them — a build able to read the old shape could not tell a
 * correct feed from a stale producer, which is the whole value of the {@code Adas-Contract-Version} check.
 *
 * <p><strong>{@code transform.inputs} and the top-level {@code inputs} are different facts.</strong> This
 * record models only the former, because the read source is ADAS's to resolve and the runner never names a
 * table: {@code inputs} here would be the table scanned, {@code transform.inputs} is the variable map the
 * request template renders from. They differ by JSON type as well as position — an array of table names
 * against an object keyed by variable name — so a member read at the wrong level fails to bind rather than
 * binding to the wrong thing. The collision is named because the rename created it.
 *
 * <p>The {@code name} is the identity: it keys the runner's five state tables and is the prefix of every
 * deterministic db-scheduler instance id. ADAS validates it at registration ({@code ^[a-z][a-z0-9_-]{0,63}$});
 * the runner deliberately does not re-validate it, because a second validator would be free to drift from the
 * one that actually gates the registry.
 *
 * <p>A name can outlive the pipeline it names, though: a delete followed by a create under the same name (a
 * catalog import, or a plain {@code DELETE} + {@code POST}) is a different pipeline, and its {@code generation}
 * restarts at 1 and often climbs back to the old value. {@link #createdAt} is what tells the two apart. ADAS sets
 * it once, at registration, and never changes it, so the sync stores it beside the scan cursor and resets the
 * name's state when the feed reports a different one.
 *
 * <p>This record models only what the runner reads. ADAS's merged pipeline DTO is one flat shape covering
 * both kinds and carries more — {@code kind}, {@code target}, {@code inputs}, {@code filter},
 * {@code updated_at}, {@code notes}, {@code state} and the aggregate-only members among
 * them. Unknown properties are tolerated, so omitting them here is the way to say the runner does not depend
 * on them: {@code kind} is already pinned by the feed's own filter, ADAS applies the filter server-side, and
 * {@code state} is ADAS's own execution position, which for an {@code enrich} pipeline is not even the one
 * the runner owns.
 *
 * @param transform the transform this pipeline runs, as authored. Its absence is not a degraded entry but an
 *                  unroutable one — the lane is chosen from {@code transform.type} — and the admission gate
 *                  refuses it by name
 * @param responseSchema the schema the model is actually held to, <em>derived</em>: the declaration's stored
 *                       schema where it has one, otherwise the one ADAS composed from the target's columns
 *                       and the transform's outputs. Absent for a {@code sql} transform, which constrains no
 *                       model response. Deliberately top-level rather than inside {@code transform}, where a
 *                       member of the same name means only "as authored"
 * @param trigger how and at which grain this pipeline fires; its {@code groupBy} and {@code readyWhen} are
 *                present only for a {@code group} trigger
 * @param grainKey the column the target enrichment is physically keyed on — what a written row is keyed by
 * @param versionColumn the physical name under which every scanned row carries the input's version value,
 *                      which for a group pipeline doubles as the group's activity timestamp. ADAS derives it
 *                      from the input's catalog scan metadata and omits it when the input declares none — an
 *                      input the runner therefore cannot dirty-mark
 * @param outputs where each model response property lands, in binding order, already resolved: the runner
 *                never joins two lists to obtain it
 * @param advanced the execution knobs as declared, absent when the declaration set none. Read them through
 *                 the accessors below rather than directly, so an absent object is not a null check at every
 *                 call site
 * @param generation the declaration revision, bumped by ADAS on <em>every</em> accepted mutation. It is both
 *                   the change-detection token and — since the fold — the version component of the runner's
 *                   own deterministic work-item ids, dead-letter row identity, spend counter key and
 *                   {@code pipeline_generation} provenance stamp
 * @param createdAt when ADAS registered this pipeline, truncated to the microseconds {@code TIMESTAMPTZ} keeps so a
 *                  value read back from {@code pipeline_cursor} compares equal. Null when the feed omits it, which
 *                  the recreation check reads as "unknown": it neither adopts nor resets
 */
public record PipelineDto(
        String name,
        TransformDto transform,
        @JsonProperty("response_schema") Map<String, Object> responseSchema,
        @JsonProperty("grain_key") String grainKey,
        @JsonProperty("version_column") String versionColumn,
        TriggerDto trigger,
        List<OutputDto> outputs,
        AdvancedDto advanced,
        boolean enabled,
        long generation,
        @JsonProperty("created_at") Instant createdAt) {

    public PipelineDto {
        createdAt = createdAt == null ? null : createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** The transform's type, or null when the feed carried no transform at all. */
    public TransformType transformType() {
        return transform == null ? null : transform.type();
    }

    /**
     * What the transform receives, keyed by variable name, or null when the feed carried no transform. This
     * is {@code transform.inputs} — the former top-level {@code vars} — and never the read source.
     */
    public Map<String, VarDto> transformInputs() {
        return transform == null ? null : transform.inputs();
    }

    /** The trigger kind, or null when the feed carried no trigger at all. */
    public TriggerKind triggerKind() {
        return trigger == null ? null : trigger.kind();
    }

    /** The cron expression of a {@code schedule} trigger, else null. */
    public String triggerCron() {
        return trigger == null ? null : trigger.cron();
    }

    /**
     * The group key of a {@code group} trigger, else null.
     *
     * <p>ADAS validates it to <em>resolve</em> to the target enrichment's grain, which is not the same as
     * spelling it: it equals {@code grainKey} when it names a bare input column, but when the key is
     * reachable only through an enrichment of the input it carries the qualified flat name
     * {@code <enrichment>.<column>}. Read a scanned row's key under this name — that is the name ADAS
     * projects it under — and write it back under {@code grainKey}.
     */
    public String groupBy() {
        return trigger == null ? null : trigger.groupBy();
    }

    /** The readiness declaration of a {@code group} trigger, else null. */
    public ReadyWhenDto readyWhen() {
        return trigger == null ? null : trigger.readyWhen();
    }

    /** Upper bound on rows one scan tick may claim, or null to use the configured default. */
    public Integer rowsPerScan() {
        return advanced == null ? null : advanced.rowsPerScan();
    }

    /** How many rows one work item evaluates, or null to use the configured default. */
    public Integer rowsPerCall() {
        return advanced == null ? null : advanced.rowsPerCall();
    }

    /** Per-pipeline model-call ceiling per minute, or null to use the configured default. */
    public Integer rateRpm() {
        return advanced == null ? null : advanced.rateRpm();
    }

    /** Fraction of eligible keys to evaluate, or null to keep everything. */
    public Double sampleFraction() {
        return advanced == null ? null : advanced.sampleFraction();
    }
}
