package com.epam.aidial.enrichment.sync;

import com.epam.aidial.enrichment.adas.dto.OutputDto;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ReadyWhenDto;
import com.epam.aidial.enrichment.adas.dto.TransformDto;
import com.epam.aidial.enrichment.adas.dto.TransformType;
import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.adas.dto.VarDto;
import com.epam.aidial.enrichment.class1.Class1MaterializeTask;
import com.epam.aidial.enrichment.scan.PipelineScanTask;
import com.epam.aidial.enrichment.support.TestPipelines;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineRoutingTest {

    @Test
    void routesAnLlmPipelineToTheScanTask() {
        PipelineDto pipeline = TestPipelines.schedule("r1", 1, TestPipelines.EVERY_30_SECONDS);
        assertThat(PipelineRouting.isClass1(pipeline)).isFalse();
        assertThat(PipelineRouting.taskName(pipeline)).isEqualTo(PipelineScanTask.TASK_NAME);
    }

    @Test
    void routesSqlPipelineToMaterializeTask() {
        PipelineDto pipeline = TestPipelines.class1("r2", 1, TestPipelines.EVERY_5_MINUTES);
        assertThat(PipelineRouting.isClass1(pipeline)).isTrue();
        assertThat(PipelineRouting.taskName(pipeline)).isEqualTo(Class1MaterializeTask.TASK_NAME);
    }

    @Test
    void pipelineWithoutTransformIsNotClass1() {
        // Defensive: the type discriminator lives on the transform, so an absent transform must fall back to
        // the row lane rather than silently routing to materialize. It is refused by the gate either way —
        // this pins that the routing predicate does not decide the lane from a null.
        PipelineDto pipeline = withoutTransform();
        assertThat(PipelineRouting.isClass1(pipeline)).isFalse();
        assertThat(PipelineRouting.taskName(pipeline)).isEqualTo(PipelineScanTask.TASK_NAME);
    }

    @Test
    void bothTaskNamesAreSwept() {
        assertThat(PipelineRouting.PIPELINE_TASK_NAMES)
                .containsExactlyInAnyOrder(PipelineScanTask.TASK_NAME, Class1MaterializeTask.TASK_NAME);
    }

    @Test
    void anExecutablePipelineHasNoUnsupportedReason() {
        assertThat(PipelineRouting.unsupportedReason(TestPipelines.schedule("r1", 1, TestPipelines.EVERY_30_SECONDS))).isNull();
        assertThat(PipelineRouting.unsupportedReason(TestPipelines.class1("r2", 1, TestPipelines.EVERY_5_MINUTES))).isNull();
    }

    /** Refused on either lane, by name, and never scheduled as a cron or a fixed delay. */
    @Test
    void retiredOnIngestTriggerIsUnsupported() {
        for (TransformType type : List.of(TransformType.LLM, TransformType.SQL)) {
            PipelineDto pipeline = TestPipelines.builder("r1")
                    .transformType(type)
                    .triggerKind(TriggerKind.ON_INGEST)
                    .triggerCron(null)
                    .build();
            assertThat(PipelineRouting.unsupportedReason(pipeline))
                    .isEqualTo("trigger kind 'on_ingest' is retired; ADAS serves it as 'schedule'");
        }
    }

    /** No default is supplied for a missing cron: that would be a second clock the declaration does not state. */
    @Test
    void schedulePipelineWithoutCronIsUnsupported() {
        for (String cron : Arrays.asList(null, "", "  ")) {
            assertThat(PipelineRouting.unsupportedReason(TestPipelines.schedule("r1", 1, cron)))
                    .isEqualTo("schedule pipeline declares no trigger.cron");
            assertThat(PipelineRouting.unsupportedReason(TestPipelines.class1("r2", 1, cron)))
                    .isEqualTo("schedule pipeline declares no trigger.cron");
        }
    }

    /** Refused rather than left to throw in the reconcile and the status heartbeat on every poll. */
    @Test
    void schedulePipelineWithInvalidCronIsUnsupported() {
        for (String cron : List.of("every minute", "* * * * *", "61 * * * * *")) {
            assertThat(PipelineRouting.unsupportedReason(TestPipelines.schedule("r1", 1, cron)))
                    .isEqualTo("schedule pipeline's trigger.cron is not a valid 6-field cron expression");
        }
    }

    /** No trigger is no clock: refused before any arm that presumes one. */
    @Test
    void pipelineWithoutTriggerIsUnsupported() {
        PipelineDto pipeline = TestPipelines.builder("r1").triggerKind(null).build();
        assertThat(PipelineRouting.unsupportedReason(pipeline)).isEqualTo("pipeline declares no trigger, so it has no schedule");
    }

    /** The cron arm is the schedule trigger's alone: a group trigger carries none and needs none. */
    @Test
    void groupPipelineWithoutCronIsNotRefusedForIt() {
        assertThat(PipelineRouting.unsupportedReason(TestPipelines.group("g1", 1, "chat_id", "10m"))).isNull();
    }

    @Test
    void sqlPipelineWithoutOutputsIsUnsupported() {
        PipelineDto pipeline = TestPipelines.builder("r4")
                .transformType(TransformType.SQL)
                .outputs(List.of())
                .build();
        // The same member as the model-calling arm below — the fold gave both lanes one — but its own
        // reason, because a sql pipeline with no outputs derives nothing rather than destroying a label.
        assertThat(PipelineRouting.unsupportedReason(pipeline)).isEqualTo("sql transform declares no outputs");
    }

    @Test
    void modelCallingPipelineWithoutResponseSchemaIsUnsupported() {
        // The failure this prevents actually happened, on the ADAS side: the enrich sync feed
        // (GET /v1/pipelines?kind=enrich&view=compiled) served a model-calling entry with no resolved
        // response_schema, while the single-pipeline read served it correctly. DialRequestBuilder sets
        // response_format only when a schema is present, so every row was sent to the model
        // unconstrained - after the call was made and charged. A non-JSON reply then dead-letters at
        // `validate`, but a reply that is well-formed JSON of the model's own shape passes (with no schema
        // ResponseValidator only parse-checks) and maps to nothing, writing an all-NULL row over a
        // paid-for label. Nothing in the feed looked wrong while it happened.
        PipelineDto pipeline = TestPipelines.builder("r-no-schema").responseSchema(null).build();
        assertThat(PipelineRouting.unsupportedReason(pipeline))
                .isEqualTo("model-calling pipeline has no response schema, so the model would be unconstrained");
    }

    /**
     * The retargeted arm, which no other arm covers. An absent transform makes both {@code isClass1} and the
     * model-calling test false, so every arm that depends on knowing the lane is skipped and
     * {@code taskName()} routes the entry to the scan lane as an ordinary row-grain pipeline — where the
     * worker then dereferences a transform that is not there.
     *
     * <p>{@code outputs} is deliberately <b>present</b> here, so this pins the transform arm rather than the
     * outputs one: the declaration is the transform now, and an entry that drops it while keeping
     * {@code outputs} passes every other arm.
     */
    @Test
    void pipelineWithNoTransformIsRefusedEvenWithOutputsPresent() {
        PipelineDto noTransform = withoutTransform();
        assertThat(noTransform.outputs()).isNotEmpty();
        assertThat(PipelineRouting.unsupportedReason(noTransform))
                .isEqualTo("pipeline carries no transform, so no lane can be chosen for it");
    }

    /**
     * The response-schema arm is scoped to a transform that is <em>explicitly</em> {@code llm}, not to
     * "anything that is not Class-1", so an entry with no transform at all keeps the verdict the arm above
     * gives it rather than being mis-described as an unconstrained model call.
     */
    @Test
    void theResponseSchemaArmDoesNotFireOnAnEntryWithNoTransform() {
        PipelineDto noTransform = TestPipelines.builder("r-no-transform")
                .transform(null)
                .responseSchema(null)
                .build();
        assertThat(PipelineRouting.unsupportedReason(noTransform))
                .isEqualTo("pipeline carries no transform, so no lane can be chosen for it");
    }

    /**
     * The member moved into the transform block; the rule did not move with it. A Class-1 derivation renders
     * no request, so a declared variable has nothing to be substituted into.
     */
    @Test
    void sqlTransformDeclaringInputsIsUnsupported() {
        PipelineDto pipeline = TestPipelines.builder("r-sql-inputs")
                .transform(new TransformDto(TransformType.SQL, null, null, null, null,
                        Map.of("text", new VarDto("request_body", null)), null))
                .build();
        assertThat(PipelineRouting.unsupportedReason(pipeline))
                .isEqualTo("sql transform declares transform.inputs, which a derivation renders no request for");
    }

    /** An empty map is not a declaration, so it must not be refused as one. */
    @Test
    void sqlTransformWithAnEmptyInputsMapIsAdmitted() {
        PipelineDto pipeline = TestPipelines.builder("r-sql-empty-inputs")
                .transform(new TransformDto(TransformType.SQL, null, null, null, null, Map.of(), null))
                .outputs(List.of(new OutputDto("model_name", "model_name", null)))
                .build();
        assertThat(PipelineRouting.unsupportedReason(pipeline)).isNull();
    }

    /**
     * The one field whose absence produced a silent cursor burn rather than a refusal: with a null grain
     * key {@code ChunkBuilder}'s {@code row.get(null)} answered null for every row, so the page yielded no
     * chunk and no dead letter and the cursor advanced over it anyway — with scan lag falling to zero as it
     * caught up, which is what made a lost backlog look like a finished one.
     */
    @Test
    void pipelineWithBlankGrainKeyIsRefused() {
        String reason = "pipeline declares no grain_key, so no scanned row could be keyed or written";
        assertThat(PipelineRouting.unsupportedReason(TestPipelines.builder("r-no-grain").grainKey(null).build()))
                .isEqualTo(reason);
        assertThat(PipelineRouting.unsupportedReason(TestPipelines.builder("r-blank-grain").grainKey("  ").build()))
                .isEqualTo(reason);
    }

    @Test
    void sqlPipelineDeclaringModelIsUnsupported() {
        // A sql transform must carry no model; one that does is not the contract the runner drives.
        PipelineDto pipeline = TestPipelines.builder("r5")
                .transform(new TransformDto(TransformType.SQL, null, "gpt-4o", null, null, null, null))
                .build();
        assertThat(PipelineRouting.unsupportedReason(pipeline)).contains("declares a model");
    }

    /**
     * The missing symmetry, and the most expensive shape of it. Nothing refused an {@code llm} transform
     * for <em>omitting</em> the two members it cannot run without, while the two arms above refuse a
     * {@code sql} one for declaring members it must not. Unrefused, a template-less pipeline reached
     * {@code TemplateRenderer} with a null template and every row died with an NPE dead-lettered at
     * {@code output_map} — a stack trace per row, naming nothing an operator can act on.
     */
    @Test
    void llmTransformWithoutRequestTemplateIsUnsupported() {
        PipelineDto pipeline = TestPipelines.builder("r-no-template").requestTemplate(null).build();
        assertThat(PipelineRouting.unsupportedReason(pipeline)).contains("no request_template");

        PipelineDto blank = TestPipelines.builder("r-blank-template").requestTemplate("  ").build();
        assertThat(PipelineRouting.unsupportedReason(blank)).contains("no request_template");
    }

    @Test
    void llmTransformWithoutModelIsUnsupported() {
        // The deployment segment of the DIAL URL: absent, the call is made against a path with a literal
        // "null" in it, after the template has rendered and the request has been built.
        PipelineDto pipeline = TestPipelines.builder("r-no-model").model(null).build();
        assertThat(PipelineRouting.unsupportedReason(pipeline)).contains("no model");
    }

    /**
     * Neither new arm may fire on the Class-1 lane, which legitimately carries no template and no model —
     * the mirror of the two {@code sql} arms, and the regression that would refuse every Class-1 pipeline.
     */
    @Test
    void sqlTransformIsAdmittedWithNoTemplateAndNoModel() {
        assertThat(PipelineRouting.unsupportedReason(TestPipelines.class1("c-ok", 1, TestPipelines.EVERY_30_SECONDS))).isNull();
    }

    @Test
    void sqlTransformOnTheGroupTriggerIsUnsupported() {
        // The two lanes exclude each other: a group's unit of work is one model call, and a sql transform makes
        // none. Admitted, it routes to class1-materialize, which ADAS answers with a 422 on every single fire —
        // while the pipeline looks healthy in the cache.
        PipelineDto pipeline = TestPipelines.builder("g5")
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .readyWhen(new ReadyWhenDto(null, "10m", null, null))
                .transformType(TransformType.SQL)
                .build();

        assertThat(PipelineRouting.unsupportedReason(pipeline)).contains("group trigger");
    }

    @Test
    void modelCallingPipelineWithoutOutputsIsUnsupported() {
        // The one refusal here that prevents data loss rather than wasted work: with no outputs the worker
        // writes the grain key plus provenance and nothing else, which on the target's ReplacingMergeTree
        // replaces an already-paid-for label with nulls — silently, and after the model call was made and
        // charged.
        String reason = "pipeline declares no outputs, so an evaluation could write no label";

        PipelineDto row = TestPipelines.builder("r6").outputs(List.of()).build();
        assertThat(PipelineRouting.unsupportedReason(row)).isEqualTo(reason);

        // The group arms run first, so this pins that a valid group declaration does not shadow the check.
        PipelineDto group = TestPipelines.builder("g6")
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .readyWhen(new ReadyWhenDto(null, "10m", null, null))
                .outputs(List.of())
                .build();
        assertThat(PipelineRouting.unsupportedReason(group)).isEqualTo(reason);

        // An absent transform does not reach this arm at all: it is refused earlier, by the arm that exists
        // because routing a lane-less entry is worse than refusing it for its empty outputs.
        PipelineDto noTransform = TestPipelines.builder("r7").transform(null).outputs(List.of()).build();
        assertThat(PipelineRouting.unsupportedReason(noTransform))
                .isEqualTo("pipeline carries no transform, so no lane can be chosen for it");
    }

    @Test
    void anOutputWithNoTransformIsStillMapped() {
        // An absent jsonata means "take response[name] verbatim" — the ordinary case, on 24 of 25 live
        // outputs. A gate that read the transform rather than the mapping would refuse almost everything.
        PipelineDto pipeline = TestPipelines.builder("r8")
                .outputs(List.of(new OutputDto("label", "sentiment", null)))
                .build();
        assertThat(PipelineRouting.unsupportedReason(pipeline)).isNull();
    }

    @Test
    void groupPipelineWithSupportedReadinessIsAdmitted() {
        PipelineDto pipeline = TestPipelines.group("g1", 1, "chat_id", "10m");
        assertThat(PipelineRouting.isGroup(pipeline)).isTrue();
        assertThat(PipelineRouting.unsupportedReason(pipeline)).isNull();
        assertThat(PipelineRouting.taskName(pipeline)).isEqualTo(PipelineScanTask.TASK_NAME);
    }

    @Test
    void groupPipelineDeclaringSignalIsSupported() {
        // ADAS evaluates the predicate inside the scan and projects the verdict, so the runner needs to know
        // only that the pipeline has one — not how to parse it.
        PipelineDto pipeline = TestPipelines.builder("g2")
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .readyWhen(new ReadyWhenDto("event_kind = 'conversation_end'", "10m", null, null))
                .build();
        assertThat(PipelineRouting.unsupportedReason(pipeline)).isNull();
        assertThat(PipelineRouting.hasSignal(pipeline)).isTrue();
    }

    @Test
    void signalAloneIsReadinessCondition() {
        PipelineDto signalOnly = TestPipelines.builder("g2b")
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .readyWhen(new ReadyWhenDto("event_kind = 'conversation_end'", null, null, null))
                .build();
        assertThat(PipelineRouting.unsupportedReason(signalOnly)).isNull();
        // The configured default idle still applies as a backstop: eviction never removes a dirty group, so a
        // group that is never signalled must still become ready some other way or it holds state forever.
        assertThat(PipelineRouting.idle(signalOnly, Duration.ofMinutes(10))).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void pipelineWithoutSignalDoesNotClaimOne() {
        assertThat(PipelineRouting.hasSignal(groupReadyWhen(new ReadyWhenDto(null, "10m", null, null)))).isFalse();
        assertThat(PipelineRouting.hasSignal(groupReadyWhen(new ReadyWhenDto("  ", "10m", null, null)))).isFalse();
    }

    @Test
    void groupPipelineWithoutGroupByOrReadyWhenIsUnsupported() {
        PipelineDto noGroupBy = TestPipelines.builder("g3")
                .triggerKind(TriggerKind.GROUP)
                .readyWhen(new ReadyWhenDto(null, "10m", null, null))
                .build();
        assertThat(PipelineRouting.unsupportedReason(noGroupBy)).contains("no group_by");

        PipelineDto noReadyWhen = TestPipelines.builder("g4")
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .build();
        assertThat(PipelineRouting.unsupportedReason(noReadyWhen)).contains("no ready_when");

        PipelineDto noVersionColumn = TestPipelines.builder("g3b")
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .versionColumn(null)
                .readyWhen(new ReadyWhenDto(null, "10m", null, null))
                .build();
        assertThat(PipelineRouting.unsupportedReason(noVersionColumn)).contains("no version_column");

        PipelineDto ceilingOnly = TestPipelines.builder("g5")
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .readyWhen(new ReadyWhenDto(null, null, null, 5))
                .build();
        assertThat(PipelineRouting.unsupportedReason(ceilingOnly))
                .contains("none of ready_when.signal, idle or max_staleness");
    }

    @Test
    void readinessDurationsParseBothSpringAndIsoForms() {
        Duration fallback = Duration.ofMinutes(10);
        PipelineDto shortForm = groupReadyWhen(new ReadyWhenDto(null, "45s", "12h", null));
        assertThat(PipelineRouting.idle(shortForm, fallback)).isEqualTo(Duration.ofSeconds(45));
        assertThat(PipelineRouting.maxStaleness(shortForm)).isEqualTo(Duration.ofHours(12));

        PipelineDto isoForm = groupReadyWhen(new ReadyWhenDto(null, "PT45S", "PT12H", null));
        assertThat(PipelineRouting.idle(isoForm, fallback)).isEqualTo(Duration.ofSeconds(45));
        assertThat(PipelineRouting.maxStaleness(isoForm)).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void undeclaredIdleFallsBackAndUndeclaredStalenessIsNull() {
        PipelineDto pipeline = groupReadyWhen(new ReadyWhenDto(null, null, "24h", null));
        assertThat(PipelineRouting.idle(pipeline, Duration.ofMinutes(10))).isEqualTo(Duration.ofMinutes(10));
        assertThat(PipelineRouting.maxStaleness(groupReadyWhen(new ReadyWhenDto(null, "5m", null, null)))).isNull();
    }

    @Test
    void retentionIsTheLongerReadinessWindow() {
        Duration fallback = Duration.ofMinutes(10);
        // Staleness dominates.
        assertThat(PipelineRouting.retention(groupReadyWhen(new ReadyWhenDto(null, "5m", "24h", null)), fallback))
                .isEqualTo(Duration.ofHours(24));
        // Idle dominates.
        assertThat(PipelineRouting.retention(groupReadyWhen(new ReadyWhenDto(null, "48h", "24h", null)), fallback))
                .isEqualTo(Duration.ofHours(48));
        // No staleness at all: retention is the idle window.
        assertThat(PipelineRouting.retention(groupReadyWhen(new ReadyWhenDto(null, "5m", null, null)), fallback))
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void costCeilingIsNullWhenUndeclared() {
        // Null means unlimited, which is every pipeline that has not opted in.
        assertThat(PipelineRouting.costCeiling(groupReadyWhen(new ReadyWhenDto(null, "10m", null, null)))).isNull();
        assertThat(PipelineRouting.costCeiling(TestPipelines.schedule("r1", 1, TestPipelines.EVERY_30_SECONDS))).isNull();
    }

    @Test
    void costCeilingIsTheDeclaredValue() {
        assertThat(PipelineRouting.costCeiling(groupReadyWhen(new ReadyWhenDto(null, "10m", null, 5))))
                .isEqualTo(5);
    }

    private static PipelineDto groupReadyWhen(ReadyWhenDto readyWhen) {
        return TestPipelines.builder("g")
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .readyWhen(readyWhen)
                .build();
    }

    private static PipelineDto withoutTransform() {
        return TestPipelines.builder("r-no-transform").transform(null).build();
    }
}
