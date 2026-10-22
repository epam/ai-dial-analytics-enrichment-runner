package com.epam.aidial.enrichment.support;

import com.epam.aidial.enrichment.adas.dto.AdvancedDto;
import com.epam.aidial.enrichment.adas.dto.OutputDto;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ReadyWhenDto;
import com.epam.aidial.enrichment.adas.dto.TransformDto;
import com.epam.aidial.enrichment.adas.dto.TransformPreset;
import com.epam.aidial.enrichment.adas.dto.TransformType;
import com.epam.aidial.enrichment.adas.dto.TriggerDto;
import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.adas.dto.VarDto;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds {@link PipelineDto} fixtures for tests. */
public final class TestPipelines {

    /** The registration instant every fixture carries unless a test recreates the pipeline under its name. */
    public static final Instant CREATED_AT = Instant.parse("2026-07-01T00:00:00Z");

    /**
     * The builder's default cron: every 30 seconds, the gap of the fixed delay the retired {@code on_ingest} default
     * gave, so a test measuring a stall in multiples of the cadence keeps the window it was written against.
     */
    public static final String EVERY_30_SECONDS = "*/30 * * * * *";

    /** Every minute, on second 0. */
    public static final String EVERY_MINUTE = "0 * * * * *";

    /** Every five minutes, on second 0 — the gap the former Class-1 lane default gave. */
    public static final String EVERY_5_MINUTES = "0 */5 * * * *";

    private TestPipelines() {
    }

    /** A row-grain pipeline on {@code cron}: the only clock a row-grain pipeline has since {@code on_ingest} retired. */
    public static PipelineDto schedule(String name, long generation, String cron) {
        return builder(name).generation(generation).triggerCron(cron).build();
    }

    public static PipelineDto withInputs(String name, Map<String, VarDto> inputs) {
        return builder(name).transformInputs(inputs).build();
    }

    public static PipelineDto scanPipeline(String name, int rowsPerCall, Map<String, VarDto> inputs) {
        return builder(name).rowsPerCall(rowsPerCall).transformInputs(inputs).build();
    }

    /** One variable bound straight to a source column — the shape almost every fixture wants. */
    public static Map<String, VarDto> columnVar(String name, String column) {
        return Map.of(name, new VarDto(column, null));
    }

    /**
     * A Class-1 pipeline: a {@code sql} transform with none of the model fields, which the builder drops for
     * this type. The derivation itself lives in ADAS and never crosses the wire; what the feed carries is the
     * resolved {@code outputs} naming the columns it writes.
     */
    public static PipelineDto class1(String name, long generation, String cron) {
        return builder(name)
                .generation(generation)
                .triggerCron(cron)
                .transformType(TransformType.SQL)
                .outputs(List.of(new OutputDto("model_name", "model_name", null)))
                .build();
    }

    /**
     * A group pipeline: fires per group key, with the readiness declaration the runner supports (an idle
     * window, no {@code signal}). ADAS validates {@code group_by} to equal the target's grain, so the
     * fixture keeps them the same.
     */
    public static PipelineDto group(String name, long generation, String groupBy, String idle) {
        return builder(name)
                .generation(generation)
                .triggerKind(TriggerKind.GROUP)
                .triggerCron(null)
                .groupBy(groupBy)
                .grainKey(groupBy)
                .readyWhen(new ReadyWhenDto(null, idle, null, null))
                .build();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** Minimal mutable builder producing an immutable {@link PipelineDto}. */
    public static final class Builder {
        private final String name;
        private long generation = 1;
        private TriggerKind triggerKind = TriggerKind.SCHEDULE;
        private String triggerCron = EVERY_30_SECONDS;
        private String groupBy;
        private ReadyWhenDto readyWhen;
        private String grainKey = "event_id";
        private String versionColumn = "_ingested_at";
        private Double sampleFraction;
        private TransformDto transformOverride;
        private boolean transformOverridden;
        private Integer rowsPerCall = 25;
        private TransformType transformType = TransformType.LLM;
        private String model = "gpt-4o";
        private String requestTemplate = "{\"messages\":[]}";
        private Map<String, Object> params;
        /**
         * Defaulted, because the compiled feed always carries one for a model-calling transform: ADAS serves
         * the declaration's stored schema where it has one and otherwise the schema it composed from the
         * target and {@code transform.outputs}. A fixture without it described a pipeline the registry
         * cannot produce, and the runner's admission gate refuses that shape — an unconstrained model call
         * is a paid-for round trip that dead-letters at `validate`. It sits at the entry's TOP LEVEL, not
         * inside the transform, exactly as on the wire. Override with {@link #responseSchema(Map)} to
         * exercise the gate.
         */
        private Map<String, Object> responseSchema = Map.of(
                "type", "object", "additionalProperties", false,
                "properties", Map.of("label", Map.of("type", "string")));
        private Map<String, VarDto> transformInputs = Map.of();
        // Non-empty by default: the admission gate refuses a pipeline of either lane that declares no
        // outputs, so an empty default would make every fixture that does not care about them unexecutable.
        private List<OutputDto> outputs = List.of(new OutputDto("label", "label", null));

        private Builder(String name) {
            this.name = name;
        }

        public Builder generation(long generation) {
            this.generation = generation;
            return this;
        }

        public Builder triggerKind(TriggerKind triggerKind) {
            this.triggerKind = triggerKind;
            return this;
        }

        public Builder triggerCron(String triggerCron) {
            this.triggerCron = triggerCron;
            return this;
        }

        public Builder rowsPerCall(Integer rowsPerCall) {
            this.rowsPerCall = rowsPerCall;
            return this;
        }

        public Builder transformType(TransformType transformType) {
            this.transformType = transformType;
            return this;
        }

        public Builder groupBy(String groupBy) {
            this.groupBy = groupBy;
            return this;
        }

        public Builder readyWhen(ReadyWhenDto readyWhen) {
            this.readyWhen = readyWhen;
            return this;
        }

        public Builder grainKey(String grainKey) {
            this.grainKey = grainKey;
            return this;
        }

        /** ADAS derives this from the input's catalog scan metadata; null models an input declaring none. */
        public Builder versionColumn(String versionColumn) {
            this.versionColumn = versionColumn;
            return this;
        }

        public Builder sampleFraction(Double sampleFraction) {
            this.sampleFraction = sampleFraction;
            return this;
        }

        /**
         * Replaces the transform block outright, including with {@code null}. Lets a test express a pipeline
         * the builder's field knobs cannot — an absent transform, or a {@code sql} transform that wrongly
         * declares a model or inputs — without hand-rolling the positional {@link PipelineDto} constructor,
         * which every new wire field breaks.
         */
        public Builder transform(TransformDto transform) {
            this.transformOverride = transform;
            this.transformOverridden = true;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder requestTemplate(String requestTemplate) {
            this.requestTemplate = requestTemplate;
            return this;
        }

        public Builder params(Map<String, Object> params) {
            this.params = params;
            return this;
        }

        public Builder responseSchema(Map<String, Object> responseSchema) {
            this.responseSchema = responseSchema;
            return this;
        }

        public Builder transformInputs(Map<String, VarDto> transformInputs) {
            this.transformInputs = transformInputs;
            return this;
        }

        public Builder outputs(List<OutputDto> outputs) {
            this.outputs = outputs;
            return this;
        }

        /** Only a {@code schedule} trigger carries a cron, as ADAS serves it; the default one is dropped for any other kind. */
        public PipelineDto build() {
            // A sql transform carries none of the model fields and no inputs — ADAS rejects them at
            // registration, and the runner's admission gate refuses a sql pipeline that declares either.
            boolean sql = transformType == TransformType.SQL;
            TransformDto transform = transformOverridden ? transformOverride : new TransformDto(
                    transformType,
                    sql ? null : TransformPreset.CHAT_COMPLETION,
                    sql ? null : model,
                    sql ? null : params,
                    sql ? null : requestTemplate,
                    sql || transformInputs == null ? null : new LinkedHashMap<>(transformInputs),
                    null);
            AdvancedDto advanced = new AdvancedDto(1000, rowsPerCall, 60, sampleFraction);
            return new PipelineDto(
                    name, transform, sql ? null : responseSchema, grainKey, versionColumn,
                    new TriggerDto(triggerKind, triggerKind == TriggerKind.SCHEDULE ? triggerCron : null, groupBy, readyWhen),
                    outputs, advanced, true, generation, CREATED_AT);
        }
    }
}
