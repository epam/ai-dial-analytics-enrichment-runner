package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.TransformDto;
import com.epam.aidial.enrichment.adas.dto.TransformPreset;
import com.epam.aidial.enrichment.configuration.WorkerProperties;
import com.epam.aidial.enrichment.dial.DialRateLimiter;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.ResponseValidationException;
import com.epam.aidial.enrichment.exception.TransientChunkException;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.RunnerTracer;
import com.epam.aidial.enrichment.spend.SpendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Derives a transform's output columns from a set of input variables: render the request template, build
 * the DIAL request for the transform's preset (see {@link DialCall}), call the deployment, validate the response against the entry's resolved schema with a
 * bounded repair retry, and map the result to physical enrichment columns.
 *
 * <p><strong>The schema comes from the entry, the request from the transform.</strong> The transform block
 * carries what the call is made <em>of</em> — model, preset, params, template — while the schema the model
 * is held to is the derived top-level {@code response_schema}, which ADAS resolves (the declaration's
 * stored one, or the one composed from the target and the transform's outputs). Reading the authored
 * member of the same name inside the transform would send an unconstrained call for every pipeline that
 * stores no schema of its own.
 *
 * <p><strong>Grain-agnostic by design.</strong> A row's inputs and a group's assembled member list differ
 * only in what the variables hold, so both grains share this one pipeline. That matters less for the
 * rendering than for what travels with it — rate-limiter acquisition, spend accounting, the DIAL span, and
 * the retryable-vs-permanent classification. Two copies of those would be free to drift on exactly the
 * decisions whose cost is highest.
 *
 * <p>What it does <em>not</em> own: the grain key, the provenance columns, dead-lettering and persistence.
 * Those differ per grain and stay with the caller.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransformInvoker {

    private final List<DialCall> calls;
    private final DialRateLimiter dialRateLimiter;
    private final TemplateRenderer templateRenderer;
    private final ResponseValidator responseValidator;
    private final OutputMapper outputMapper;
    private final SpendService spendService;
    private final RunnerMetrics runnerMetrics;
    private final RunnerTracer runnerTracer;
    private final WorkerProperties workerProperties;
    private final JsonMapper jsonMapper;

    /**
     * Renders, calls, validates and output-maps one evaluation.
     *
     * @return the mapped physical enrichment columns, without grain key or provenance
     * @throws TransientChunkException     on a retryable failure (rate-limit exhaustion, DIAL 429/5xx)
     * @throws ResponseValidationException when the response still fails its schema after the repair budget
     */
    public Map<String, Object> derive(PipelineDto pipeline, TransformDto transform, Map<String, Object> inputs) {
        DialCall call = callFor(transform);
        String rendered = templateRenderer.render(transform.requestTemplate(), inputs);
        String requestBody = call.body(rendered, transform, pipeline.responseSchema());
        String content = callWithRepair(call, pipeline, transform, requestBody);
        return outputMapper.map(pipeline, parseContent(content));
    }

    /** The call for the transform's preset; an absent preset is a chat completion, as ADAS defaults it. */
    private DialCall callFor(TransformDto transform) {
        TransformPreset preset = transform.preset() == null ? TransformPreset.CHAT_COMPLETION : transform.preset();
        return calls.stream()
                .filter(call -> call.preset() == preset)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no DIAL call implements preset " + preset));
    }

    private String callWithRepair(DialCall call, PipelineDto pipeline, TransformDto transform, String requestBody) {
        Map<String, Object> responseSchema = pipeline.responseSchema();
        String currentBody = requestBody;
        DialCall.Reply reply = invoke(call, pipeline, transform, currentBody);
        List<String> errors = responseValidator.validate(responseSchema, reply.output());

        int repairs = 0;
        while (!errors.isEmpty() && call.repairs() && repairs < workerProperties.repairRetries()) {
            currentBody = call.withCorrection(currentBody, errors);
            reply = invoke(call, pipeline, transform, currentBody);
            errors = responseValidator.validate(responseSchema, reply.output());
            repairs++;
        }
        if (!errors.isEmpty()) {
            throw new ResponseValidationException(String.join("; ", errors), reply.evidence());
        }
        return reply.output();
    }

    /** Rate-limited, span-wrapped DIAL call that records best-effort spend and returns what the reply yields. */
    private DialCall.Reply invoke(DialCall call, PipelineDto pipeline, TransformDto transform, String requestBody) {
        if (!dialRateLimiter.acquire(pipeline.name(), pipeline.rateRpm())) {
            throw new TransientChunkException(DlqStage.DIAL_CALL, "rate limited for pipeline " + pipeline.name(), null);
        }
        var sample = runnerMetrics.startDialTimer();
        String raw;
        try {
            raw = runnerTracer.inSpan("enrichment.dial-call", dialSpanAttributes(pipeline, transform, call),
                    () -> call.call(transform, requestBody));
        } catch (RuntimeException e) {
            runnerMetrics.recordDialError(sample);
            if (e instanceof RestClientResponseException http && http.getStatusCode().value() == 429) {
                // Also counted as an error above: rate-limit pressure needs telling apart from model failure,
                // but a 429 is still a failed call and dropping it would make the error rate under-report.
                runnerMetrics.recordDialRateLimited();
            }
            throw e;
        }
        runnerMetrics.recordDialSuccess(sample);
        DialCall.Reply reply;
        try {
            reply = call.read(pipeline, transform, raw);
        } catch (ResponseValidationException unreadable) {
            // The call succeeded and is counted; only its reply yielded nothing to validate.
            spendService.record(pipeline.name(), pipeline.generation(), 0, SpendService.CALLS_UNIT, false);
            throw unreadable;
        }
        spendService.record(pipeline.name(), pipeline.generation(), reply.usage(), reply.usageUnit(), false);
        return reply;
    }

    private static Map<String, String> dialSpanAttributes(PipelineDto pipeline, TransformDto transform, DialCall call) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("pipeline.name", pipeline.name());
        attributes.put("pipeline.generation", Long.toString(pipeline.generation()));
        attributes.put("model", transform.model());
        attributes.put("preset", call.preset().name().toLowerCase(Locale.ROOT));
        putIfPresent(attributes, "chunk.id", MDC.get(WorkerMdc.CHUNK_ID));
        putIfPresent(attributes, "grain.key", MDC.get(WorkerMdc.GRAIN_KEY));
        putIfPresent(attributes, "group.key", MDC.get(WorkerMdc.GROUP_KEY));
        return attributes;
    }

    private static void putIfPresent(Map<String, String> attributes, String key, String value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    private Object parseContent(String content) {
        if (content == null) {
            // No schema to validate against + an empty model reply lands here: dead-letter at VALIDATE
            // rather than throwing a raw Jackson error out of the work item.
            throw new ResponseValidationException("model returned no content", null);
        }
        try {
            return jsonMapper.readValue(content, Object.class);
        } catch (RuntimeException e) {
            // The validate gate already rejects an unparseable reply, so reaching here means the two
            // parsers disagreed. Reported as a validation failure carrying the reply all the same: a raw
            // Jackson error out of here is dead-lettered at output-map with no raw response attached.
            throw new ResponseValidationException("response is not valid JSON", content);
        }
    }
}
