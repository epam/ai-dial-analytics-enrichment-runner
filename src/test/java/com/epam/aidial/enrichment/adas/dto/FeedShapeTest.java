package com.epam.aidial.enrichment.adas.dto;

import com.epam.aidial.enrichment.configuration.JsonMapperConfiguration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deserializes a compiled-projection entry in the shape ADAS actually serves it, and asserts every member
 * the runner dereferences binds.
 *
 * <p>This is the repository's half of an agreement that spans two codebases. ADAS pins the serialized shape
 * of this projection in its own {@code RunnerContractShapeTest}, which fails its build if the shape moves
 * without {@code RunnerContract.VERSION} being raised; this test fails <em>this</em> build if the shape it
 * expects stops binding. Neither test can see the other repository, so the two agree through the artefact
 * rather than through prose — and the JSON below is written to match that golden list member for member,
 * including the ones the runner does not read.
 *
 * <p>The fixture is synthetic: a plausible declaration, not a copy of a real prompt or a real pipeline. What
 * is being pinned is the shape, and real content in a test resource is a liability with no compensating
 * benefit.
 *
 * <p><b>Members ADAS serves that this record deliberately does not model</b> appear in the JSON all the
 * same — {@code kind}, {@code target}, the top-level {@code inputs}, {@code filter}, {@code state},
 * {@code notes}, {@code transform.output_vars} and {@code transform.response_schema}. Including them is the
 * assertion: unknown properties are tolerated, so a feed carrying them must parse, and their presence here
 * is what proves the toleration rather than leaving it to be assumed.
 */
class FeedShapeTest {

    private final JsonMapper jsonMapper = new JsonMapperConfiguration().jsonMapper();

    /**
     * One {@code llm} entry of the compiled projection. Two details are load-bearing rather than incidental:
     *
     * <ul>
     *   <li>{@code transform.inputs} is an <b>object</b> keyed by variable name, while the top-level
     *       {@code inputs} is an <b>array</b> of table names. The rename created that collision, and the
     *       differing JSON type is what makes a member read at the wrong level fail to bind rather than bind
     *       to the wrong thing — so both are present, and the test asserts which one reached the variable
     *       map.</li>
     *   <li>{@code transform.response_schema} and the top-level {@code response_schema} hold
     *       <b>different</b> schemas. They mean different things — "the schema this declaration stores"
     *       against "the schema the model is held to" — and a fixture in which they were identical would
     *       pass whichever one the code happened to read.</li>
     * </ul>
     */
    private static final String LLM_ENTRY = """
            {"pipelines":[{
              "name":"sentiment-live",
              "kind":"enrich",
              "target":"enr_sentiment",
              "inputs":["dial_usage_log"],
              "filter":"status_code = 200",
              "transform":{
                "type":"llm",
                "preset":"chat_completion",
                "model":"gemini-2.5-flash-lite",
                "params":{"temperature":0},
                "request_template":"{\\"messages\\":[{\\"role\\":\\"user\\",\\"content\\":\\"{{prompt}}\\"}]}",
                "inputs":{"prompt":{"column":"request_body"},"deployment":{"jsonata":"$.deployment"}},
                "outputs":{"sentiment":{"prose":"the sentiment","values":["positive","negative"]}},
                "output_vars":[{"name":"sentiment","type":"string","jsonata":null,"sql":null}],
                "response_schema":{"type":"object","properties":{"stored_only":{"type":"string"}}}
              },
              "response_schema":{"type":"object","additionalProperties":false,
                "properties":{"sentiment":{"type":"string","enum":["positive","negative"]}},
                "required":["sentiment"]},
              "grain_key":"event_id",
              "version_column":"_ingested_at",
              "outputs":[{"name":"sentiment","column":"sentiment_label","jsonata":null}],
              "advanced":{"rows_per_scan":1000,"rows_per_call":25,"rate_rpm":60,"sample_fraction":0.5},
              "trigger":{"kind":"schedule","cron":"10 * * * * *"},
              "enabled":true,
              "generation":17,
              "created_at":"2026-09-01T00:00:00Z",
              "updated_at":"2026-09-18T00:00:00Z",
              "state":{"lag_seconds":42,"has_more":false},
              "notes":[]
            }]}""";

    /**
     * A {@code sql} entry. Every model member is absent, {@code transform.inputs} is absent (a derivation
     * renders no request), and there is <b>no</b> top-level {@code response_schema} — a derivation constrains
     * no model response. The resolved {@code outputs} is the only thing the runner reads, and only to admit
     * the pipeline.
     */
    private static final String SQL_ENTRY = """
            {"pipelines":[{
              "name":"model-name-live",
              "kind":"enrich",
              "target":"enr_model_name",
              "inputs":["dial_usage_log"],
              "transform":{"type":"sql","outputs":{"model_name":"JSONExtractString(request_body, 'model')"}},
              "grain_key":"event_id",
              "version_column":"_ingested_at",
              "outputs":[{"name":"model_name","column":"model_name","jsonata":null}],
              "trigger":{"kind":"schedule","cron":"23 * * * * *"},
              "enabled":true,
              "generation":4,
              "created_at":"2026-09-01T00:00:00Z",
              "updated_at":"2026-09-18T00:00:00Z"
            }]}""";

    @Test
    void bindsEveryMemberTheRunnerDereferencesOnAnLlmEntry() {
        PipelineDto pipeline = parseOne(LLM_ENTRY);

        assertThat(pipeline.name()).isEqualTo("sentiment-live");
        assertThat(pipeline.generation()).isEqualTo(17L);
        assertThat(pipeline.enabled()).isTrue();
        assertThat(pipeline.grainKey()).isEqualTo("event_id");
        assertThat(pipeline.versionColumn()).isEqualTo("_ingested_at");
        assertThat(pipeline.triggerKind()).isEqualTo(TriggerKind.SCHEDULE);
        assertThat(pipeline.triggerCron()).isEqualTo("10 * * * * *");

        assertThat(pipeline.transform().type()).isEqualTo(TransformType.LLM);
        assertThat(pipeline.transform().preset()).isEqualTo(TransformPreset.CHAT_COMPLETION);
        assertThat(pipeline.transform().model()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(pipeline.transform().params()).containsEntry("temperature", 0);
        assertThat(pipeline.transform().requestTemplate()).contains("{{prompt}}");

        assertThat(pipeline.outputs()).singleElement().satisfies(output -> {
            assertThat(output.name()).isEqualTo("sentiment");
            assertThat(output.column()).isEqualTo("sentiment_label");
            assertThat(output.jsonata()).isNull();
        });

        assertThat(pipeline.rowsPerScan()).isEqualTo(1000);
        assertThat(pipeline.rowsPerCall()).isEqualTo(25);
        assertThat(pipeline.rateRpm()).isEqualTo(60);
        assertThat(pipeline.sampleFraction()).isEqualTo(0.5);
    }

    /**
     * The variable map comes from {@code transform.inputs}, keyed by variable name, and each value carries
     * exactly one of a bound column or a JSONata expression. The top-level {@code inputs} array is the read
     * source and reaches nothing here.
     */
    @Test
    void theVariableMapComesFromTheTransformAndNotFromTheReadSource() {
        PipelineDto pipeline = parseOne(LLM_ENTRY);

        assertThat(pipeline.transformInputs()).containsOnlyKeys("prompt", "deployment");
        assertThat(pipeline.transformInputs())
                .containsEntry("prompt", new VarDto("request_body", null))
                .containsEntry("deployment", new VarDto(null, "$.deployment"));
        // Declaration order is the order the rendered prompt interpolates, so the map preserves it.
        assertThat(pipeline.transformInputs().keySet()).containsExactly("prompt", "deployment");
    }

    /**
     * The schema the model is held to is the <b>derived top-level</b> member, not the transform's authored
     * one. Reading the authored member would send an unconstrained call for every pipeline that stores no
     * schema of its own — which is most of them — so the two schemas in the fixture differ and this asserts
     * which one arrived.
     */
    @Test
    void theResolvedSchemaIsTheTopLevelMemberNotTheAuthoredOne() {
        PipelineDto pipeline = parseOne(LLM_ENTRY);

        assertThat(pipeline.responseSchema()).containsKey("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) pipeline.responseSchema().get("properties");
        assertThat(properties).containsOnlyKeys("sentiment");
    }

    @Test
    void bindsSqlEntryWithNoneOfTheModelMembers() {
        PipelineDto pipeline = parseOne(SQL_ENTRY);

        assertThat(pipeline.transform().type()).isEqualTo(TransformType.SQL);
        assertThat(pipeline.transform().model()).isNull();
        assertThat(pipeline.transform().preset()).isNull();
        assertThat(pipeline.transform().params()).isNull();
        assertThat(pipeline.transform().requestTemplate()).isNull();
        assertThat(pipeline.transformInputs()).isNull();
        assertThat(pipeline.responseSchema()).isNull();

        // The derivation's expressions cross the wire under transform.outputs but are never interpreted:
        // ADAS evaluates them inside ClickHouse. What the runner reads is the resolved list.
        assertThat(pipeline.transform().outputs()).containsOnlyKeys("model_name");
        assertThat(pipeline.outputs()).singleElement()
                .satisfies(output -> assertThat(output.column()).isEqualTo("model_name"));
    }

    /**
     * The enum wire values are lowercase on ADAS's side, and the application mapper's case-insensitive enum
     * reading is what binds them. Pinned here rather than only in the sync test, because the mapper
     * configuration and the enum spellings are two things that can drift apart.
     */
    @Test
    void lowercaseEnumWireValuesBind() {
        assertThat(parseOne(LLM_ENTRY).transform().type()).isEqualTo(TransformType.LLM);
        assertThat(parseOne(SQL_ENTRY).transform().type()).isEqualTo(TransformType.SQL);
    }

    /**
     * Contract 3 retired {@code advanced.scan_every}: the knobs are exactly four, and none of them is a clock. The
     * record's components are pinned so a fifth member cannot creep back without this test being edited.
     */
    @Test
    void advancedCarriesFourKnobsAndNoScanClock() {
        assertThat(AdvancedDto.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("rowsPerScan", "rowsPerCall", "rateRpm", "sampleFraction");
    }

    /**
     * ADAS never serves the retired kind under contract 3, but if one arrives it must not fail the whole feed
     * parse: {@link TriggerKind#ON_INGEST} stays modelled so the admission gate can refuse just that entry. A
     * stale {@code scan_every} beside it is an unknown member, tolerated and ignored.
     */
    @Test
    void retiredOnIngestEntryStillParses() {
        String entry = SQL_ENTRY.replace("\"trigger\":{\"kind\":\"schedule\",\"cron\":\"23 * * * * *\"}",
                "\"trigger\":{\"kind\":\"on_ingest\"},\"advanced\":{\"scan_every\":\"60s\"}");
        assertThat(entry).contains("on_ingest");

        PipelineDto pipeline = parseOne(entry);

        assertThat(pipeline.triggerKind()).isEqualTo(TriggerKind.ON_INGEST);
        assertThat(pipeline.triggerCron()).isNull();
        assertThat(pipeline.advanced()).isNotNull();
    }

    /**
     * ADAS may serve {@code created_at} at nanosecond precision, but the cursor row's {@code TIMESTAMPTZ} keeps only
     * microseconds; untruncated, the value read back would never equal the feed's, and every poll would reset the
     * pipeline.
     */
    @Test
    void createdAtIsTruncatedToMicroseconds() {
        String entry = SQL_ENTRY.replace("\"created_at\":\"2026-09-01T00:00:00Z\"",
                "\"created_at\":\"2026-10-06T09:30:00.123456789Z\"");
        assertThat(entry).contains("123456789");

        assertThat(parseOne(entry).createdAt()).isEqualTo(Instant.parse("2026-10-06T09:30:00.123456Z"));
    }

    @Test
    void createdAtIsTruncatedWhenConstructedDirectly() {
        PipelineDto pipeline = new PipelineDto("p", null, null, null, null, null, null, null, true, 1,
                Instant.parse("2026-10-06T09:30:00.123456789Z"));

        assertThat(pipeline.createdAt()).isEqualTo(Instant.parse("2026-10-06T09:30:00.123456Z"));
        assertThat(new PipelineDto("p", null, null, null, null, null, null, null, true, 1, null).createdAt()).isNull();
    }

    private PipelineDto parseOne(String envelope) {
        PipelineListDto listing = jsonMapper.readValue(envelope, PipelineListDto.class);
        assertThat(listing.pipelines()).hasSize(1);
        return listing.pipelines().getFirst();
    }
}
