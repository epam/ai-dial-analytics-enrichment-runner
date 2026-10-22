package com.epam.aidial.enrichment.sync;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ReadyWhenDto;
import com.epam.aidial.enrichment.adas.dto.TransformType;
import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.class1.Class1MaterializeTask;
import com.epam.aidial.enrichment.scan.PipelineScanTask;
import com.epam.aidial.enrichment.scan.ScheduleFactory;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.util.Set;

/**
 * The single source of the pipeline-routing predicates: which lane a pipeline belongs to, which recurring task
 * drives it, and whether the runner can execute it at all.
 *
 * <p>Routing lives here rather than in each caller because the same question is asked from pipeline sync,
 * both executors and the ops mapper.
 *
 * <p>There is no version-resolution helper any more, and its absence is the point. It existed to reconcile
 * an inlined evaluator's {@code version} with a flat {@code evaluator_version} field — two spellings of one
 * fact, with a 0 sentinel for "unknown". Since the evaluator fold the declaration revision is the pipeline's
 * own {@code generation}, always present and always a {@code long}, so every caller reads
 * {@link PipelineDto#generation()} directly rather than through a resolver that could answer 0.
 */
public final class PipelineRouting {

    /** Every recurring task name a pipeline can be scheduled onto; reconciliation must sweep all of them. */
    public static final Set<String> PIPELINE_TASK_NAMES =
            Set.of(PipelineScanTask.TASK_NAME, Class1MaterializeTask.TASK_NAME);

    private PipelineRouting() {
    }

    /** Whether the pipeline is Class-1 ({@code transform.type=sql}): computed in the database, no model call. */
    public static boolean isClass1(PipelineDto pipeline) {
        return pipeline.transformType() == TransformType.SQL;
    }

    /** Whether the pipeline fires per group key rather than per row. */
    public static boolean isGroup(PipelineDto pipeline) {
        return pipeline.triggerKind() == TriggerKind.GROUP;
    }

    /** The lane a pipeline runs in, as log lines name it: {@code class1}, {@code group} or {@code row}. */
    public static String lane(PipelineDto pipeline) {
        if (isClass1(pipeline)) {
            return "class1";
        }
        return isGroup(pipeline) ? "group" : "row";
    }

    /** The recurring task name that drives this pipeline. */
    public static String taskName(PipelineDto pipeline) {
        return isClass1(pipeline) ? Class1MaterializeTask.TASK_NAME : PipelineScanTask.TASK_NAME;
    }

    /**
     * Why the runner cannot execute this pipeline, or null when it can. Deliberately conservative: it names
     * only combinations that provably cannot run, so an unrecognised-but-harmless future field never
     * disables a pipeline. A pipeline with a reason is skipped at sync — neither cached nor scheduled — because
     * caching it would make it look healthy while failing on every fire.
     *
     * <p>Most reasons here prevent wasted work. Two prevent data loss, and both are on the model-calling
     * lane: a pipeline that declares no {@code outputs}, and one that carries no top-level
     * {@code response_schema}. Neither fails — each can write an all-NULL row over an existing label, after
     * the model call has been made and charged. Those two arms are why this method is a gate rather than a
     * warning.
     *
     * <p>The first arms below cover the facts every lane <em>dereferences</em> — a transform, a trigger the
     * runner has a clock for, a grain key — and they are ordered first because every arm after them presumes
     * them.
     */
    public static String unsupportedReason(PipelineDto pipeline) {
        if (pipeline.transform() == null) {
            // The same arm the pre-fold gate pointed at the inlined `evaluator`, retargeted rather than
            // removed. The declaration IS the transform now, so an entry without one describes nothing the
            // runner can run — and no other arm catches it: an absent transform makes isClass1() and
            // isModelCalling() both false, so the response-schema and sql-declares-a-model arms are skipped,
            // taskName() routes the entry to the scan lane as an ordinary row-grain pipeline, and the worker
            // then dereferences a transform that is not there. Left testing `evaluator` this arm would be
            // permanently true after the fold and would refuse EVERY pipeline; deleted, the gap reopens
            // under the new member name.
            return "pipeline carries no transform, so no lane can be chosen for it";
        }
        if (pipeline.triggerKind() == null) {
            // Every lane is fired by a clock its trigger selects, so an entry without one has nothing to run on.
            // Unrefused it would be cached as healthy and then fail in ScheduleFactory on every poll.
            return "pipeline declares no trigger, so it has no schedule";
        }
        if (pipeline.triggerKind() == TriggerKind.ON_INGEST) {
            // A property of the entry, not of its lane, so it sits beside the no-transform arm. ADAS rewrote
            // every on_ingest pipeline to schedule and refuses the kind on write; one arriving means a producer
            // the contract check should already have refused. Unrefused it would be cached as healthy and then
            // fail in ScheduleFactory, which has no clock for it.
            return "trigger kind 'on_ingest' is retired; ADAS serves it as 'schedule'";
        }
        if (pipeline.triggerKind() == TriggerKind.SCHEDULE) {
            if (StringUtils.isBlank(pipeline.triggerCron())) {
                // ADAS stores a cron on every row-grain pipeline, so its absence is a producer defect. Refused here
                // rather than defaulted, because a runner default is a second clock the declaration does not state,
                // and rather than left to ScheduleFactory's throw, which happens after the entry was cached.
                return "schedule pipeline declares no trigger.cron";
            }
            if (!isRunnableCron(pipeline.triggerCron())) {
                // ADAS validates the cron on write, so this too is a producer defect, refused for the same reason.
                // Both of the runner's parsers must accept it: db-scheduler's schedules the instance and Spring's
                // measures the status heartbeat's cadence, and ADAS checks with only one of them.
                return "schedule pipeline's trigger.cron is not a valid 6-field cron expression";
            }
        }
        if (StringUtils.isBlank(pipeline.grainKey())) {
            // The single field whose absence produced a silent cursor burn rather than a refusal: with a null
            // grain key ChunkBuilder's row.get(null) answered null for every row, so a page yielded no chunk
            // and no dead letter, and the cursor advanced over it anyway — up to SCAN_MAX_PAGES_PER_FIRE pages
            // a fire, with scan lag falling to zero as it caught up. Both lanes read a scanned row by this
            // key, and every written row is keyed by it.
            return "pipeline declares no grain_key, so no scanned row could be keyed or written";
        }
        if (isGroup(pipeline) && isClass1(pipeline)) {
            // The two lanes are mutually exclusive: a group pipeline's work is one model call per group, and a sql
            // transform makes none. Without this the pipeline routes to class1-materialize, which ADAS answers with
            // a 422 on every fire — and the runner would have cached it as healthy.
            return "sql transform on the group trigger is not a supported combination";
        }
        if (isGroup(pipeline)) {
            if (StringUtils.isBlank(pipeline.groupBy())) {
                return "group pipeline declares no group_by";
            }
            if (StringUtils.isBlank(pipeline.versionColumn())) {
                // ADAS omits version_column when the source declares no scan metadata. Without it there
                // is no version to compare and no activity timestamp, so the group could never be
                // dirty-marked — refusing once beats dead-lettering every scanned row forever.
                return "group pipeline's source declares no version_column";
            }
            ReadyWhenDto readyWhen = pipeline.readyWhen();
            if (readyWhen == null) {
                return "group pipeline declares no ready_when";
            }
            if (!hasSignal(pipeline) && StringUtils.isBlank(readyWhen.idle())
                    && StringUtils.isBlank(readyWhen.maxStaleness())) {
                // ADAS rejects this at registration, so it can only arrive from an older definition; a group
                // with no readiness condition is dirty forever and never ready, so the pipeline would do nothing.
                return "group pipeline declares none of ready_when.signal, idle or max_staleness";
            }
        }
        if (pipeline.outputs() == null || pipeline.outputs().isEmpty()) {
            // One member, both lanes — that is what the fold bought: `outputs` is the resolved mapping for a
            // model-calling pipeline and the derivation set for a Class-1 one. The reasons stay distinct
            // because the consequences do. A sql pipeline with no outputs derives nothing. A model-calling
            // one is the most expensive refusal here, and the only failure it prevents that is silent: with
            // no outputs OutputMapper returns no columns, the worker still builds a row from the grain key
            // plus the provenance columns, and that row is written — against the target's ReplacingMergeTree
            // an all-NULL row at an existing grain key REPLACES a label that has already been paid for.
            // Nothing throws, nothing is dead-lettered, nothing is retried, and the model call was made and
            // charged first. ADAS refuses to serve such a pipeline too, but a remote invariant is not a safe
            // guard for a local, irreversible loss — nor for an older runner image scaled back up against a
            // newer ADAS.
            //
            // Only emptiness is checked. Whether an output's column exists on the target, or its name is
            // declared by the response schema, is ADAS's registration check; either failure is loud at write
            // time, and a second validator here would drift from the one that gates the registry.
            return isClass1(pipeline)
                    ? "sql transform declares no outputs"
                    : "pipeline declares no outputs, so an evaluation could write no label";
        }
        if (isClass1(pipeline) && StringUtils.isNotBlank(pipeline.transform().model())) {
            return "sql transform declares a model, which it must not";
        }
        if (isClass1(pipeline) && pipeline.transformInputs() != null && !pipeline.transformInputs().isEmpty()) {
            // The member moved into the transform block; the rule did not move with it. A Class-1 derivation
            // renders no request, so there is nothing for a bound variable to be substituted into — and a
            // declaration that names some describes two different transforms at once.
            return "sql transform declares transform.inputs, which a derivation renders no request for";
        }
        if (isModelCalling(pipeline) && StringUtils.isBlank(pipeline.transform().requestTemplate())) {
            // The symmetric partner of the two sql arms above, and it was missing: the sql side is refused
            // for declaring members it must not, and nothing refused the llm side for omitting the two it
            // cannot run without. Unrefused, a template-less llm transform reaches TemplateRenderer with a
            // null template — whose placeholder assertion returns early on null and whose substitution then
            // works on a null string — so every row dies with an NPE dead-lettered at `output_map`, naming
            // no cause an operator can act on. A named refusal at sync says which member is missing, once,
            // instead of once per row. ADAS refuses this shape at write time too; a remote invariant is not
            // a safe guard for a local failure, exactly as the outputs arm above argues.
            return "llm transform declares no request_template, so no request could be rendered";
        }
        if (isModelCalling(pipeline) && StringUtils.isBlank(pipeline.transform().model())) {
            // The other half of the same omission. A null model is the deployment segment of the DIAL
            // chat-completions URL, so the call is made against a path with a literal "null" in it and
            // fails per row — after the template has rendered and the request has been built.
            return "llm transform declares no model, so no deployment could be called";
        }
        if (isModelCalling(pipeline)
                && (pipeline.responseSchema() == null || pipeline.responseSchema().isEmpty())) {
            // The second paid-for failure on this lane, and the one that has actually happened.
            // DialRequestBuilder sets `response_format = json_schema` only when a schema is present, so a
            // model-calling pipeline arriving without one is asked an unconstrained question — and what
            // happens next splits, badly, on what the model happens to reply:
            //   * a reply that is not JSON (prose, a fenced block) is rejected by ResponseValidator, so
            //     every row dead-letters at `validate` after the call was made and charged;
            //   * a reply that IS well-formed JSON of the model's own shape passes, because with no schema
            //     ResponseValidator only parse-checks. OutputMapper then reads response[name] for each
            //     output, finds none of them, and the worker writes the grain key plus provenance and
            //     NULLs — the same silent replace-a-paid-for-label loss the outputs arm above prevents.
            // The second outcome is why this is a gate and not a warning: nothing about the feed looks
            // wrong while it happens, and no dead-letter item records that it did.
            //
            // Read from the entry's TOP LEVEL, never from inside the transform block. ADAS serves the
            // resolved schema there — the declaration's stored one where it has one, otherwise the one it
            // composed from the target and the transform's outputs — while `transform.response_schema`
            // means only "the schema this declaration stores" and is absent for every pipeline that stores
            // none. Reading the authored member would refuse exactly the pipelines ADAS composes a schema
            // for, which is most of them.
            return "model-calling pipeline has no response schema, so the model would be unconstrained";
        }
        return null;
    }

    /**
     * Whether this pipeline calls a model — a transform that is explicitly {@code LLM}.
     *
     * <p>Deliberately <b>not</b> "anything that is not Class-1": a pipeline the feed carried no transform
     * for is a different question, answered by the first arm above and pinned by its own test. This
     * predicate only ever gates a refusal, and the gate's posture is that only a combination known to be
     * broken may disable a pipeline — which is also why it stays private rather than joining the routing
     * predicates above, none of whose callers should be choosing a lane by it.
     */
    private static boolean isModelCalling(PipelineDto pipeline) {
        return pipeline.transformType() == TransformType.LLM;
    }

    /**
     * Whether the group pipeline declares a {@code ready_when.signal} — a predicate ADAS evaluates inside the
     * scan and projects per row, which the runner records on the group and reads as a readiness branch. The
     * runner never parses the predicate itself; it only needs to know whether to consult the recorded
     * verdict.
     */
    public static boolean hasSignal(PipelineDto pipeline) {
        ReadyWhenDto readyWhen = pipeline.readyWhen();
        return readyWhen != null && StringUtils.isNotBlank(readyWhen.signal());
    }

    /**
     * The group pipeline's idle window — how long a group must be quiet to count as ready — falling back to
     * the configured default when the pipeline declares none. ADAS accepts both the Spring short form
     * ({@code 10m}) and ISO-8601 ({@code PT10M}), so both are parsed.
     *
     * <p>The fallback applies even to a pipeline whose only declared condition is a {@code signal}, and
     * deliberately: eviction never removes a dirty group, so a group that is never signalled would hold its
     * state row forever. The default idle window is the backstop that gets it evaluated, clean, and
     * eventually evicted — a signal then only makes a group fire <em>sooner</em> than that window.
     */
    public static Duration idle(PipelineDto pipeline, Duration defaultIdle) {
        ReadyWhenDto readyWhen = pipeline.readyWhen();
        if (readyWhen == null || StringUtils.isBlank(readyWhen.idle())) {
            return defaultIdle;
        }
        return DurationStyle.detectAndParse(readyWhen.idle());
    }

    /**
     * The group pipeline's max-staleness window, or null when it declares none (in which case a computed
     * group is re-evaluated only when it changes, never merely because it aged).
     */
    public static Duration maxStaleness(PipelineDto pipeline) {
        ReadyWhenDto readyWhen = pipeline.readyWhen();
        if (readyWhen == null || StringUtils.isBlank(readyWhen.maxStaleness())) {
            return null;
        }
        return DurationStyle.detectAndParse(readyWhen.maxStaleness());
    }

    /**
     * The group pipeline's per-group daily evaluation ceiling, or null when it declares none — which every pipeline
     * that has not opted in does, so null is the common path and is kept as an explicit "unlimited" rather
     * than a sentinel the query would have to compare against.
     */
    public static Integer costCeiling(PipelineDto pipeline) {
        ReadyWhenDto readyWhen = pipeline.readyWhen();
        return readyWhen == null ? null : readyWhen.costCeiling();
    }

    /**
     * How long a group's state is retained after its last activity before eviction may discard it: the
     * longer of the two readiness windows, so eviction can never race a readiness pipeline that would still
     * have fired.
     */
    public static Duration retention(PipelineDto pipeline, Duration defaultIdle) {
        Duration idle = idle(pipeline, defaultIdle);
        Duration staleness = maxStaleness(pipeline);
        return staleness == null || staleness.compareTo(idle) < 0 ? idle : staleness;
    }

    private static boolean isRunnableCron(String cron) {
        if (!CronExpression.isValidExpression(cron)) {
            return false;
        }
        try {
            ScheduleFactory.cronSchedule(cron);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
