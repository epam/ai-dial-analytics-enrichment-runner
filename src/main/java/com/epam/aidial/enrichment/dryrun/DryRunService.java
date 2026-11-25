package com.epam.aidial.enrichment.dryrun;

import com.epam.aidial.enrichment.adas.AdasTrialInputClient;
import com.epam.aidial.enrichment.adas.InboundCredential;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.TrialInputRequest;
import com.epam.aidial.enrichment.adas.dto.TrialInputResponse;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.AdasContractMismatchException;
import com.epam.aidial.enrichment.exception.DialRateLimitedException;
import com.epam.aidial.enrichment.exception.DryRunAdasRefusalException;
import com.epam.aidial.enrichment.exception.DryRunAdasUnavailableException;
import com.epam.aidial.enrichment.exception.DryRunInProgressException;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.exception.PipelineNotRunnableException;
import com.epam.aidial.enrichment.exception.SpendBudgetExhaustedException;
import com.epam.aidial.enrichment.scan.InputMapper;
import com.epam.aidial.enrichment.spend.SpendPauseGate;
import com.epam.aidial.enrichment.sync.PipelineRouting;
import com.epam.aidial.enrichment.web.dto.dryrun.DryRunEvaluationDto;
import com.epam.aidial.enrichment.web.dto.dryrun.DryRunRequest;
import com.epam.aidial.enrichment.web.dto.dryrun.DryRunResponse;
import com.epam.aidial.enrichment.worker.TransformInvoker;
import com.epam.aidial.enrichment.worker.TrialOutcome;
import com.epam.aidial.enrichment.worker.group.GroupInputAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The dry run of one {@code llm} pipeline: read a sampled row or group through ADAS as the person who asked, then
 * evaluate it through the same code a worker runs — input mapping or group assembly, render, the DIAL call, the
 * corrective retry, validation and output mapping — and answer with ADAS's preview beside what the model made of it.
 *
 * <p><b>It spends, and changes nothing else.</b> Each DIAL call is recorded in the pipeline's daily spend as a
 * worker's is, and the soft daily spend gate refuses a dry run that would spend past it. Nothing is dead-lettered or
 * written back, no cursor moves, no group is stamped or charged against its {@code cost_ceiling}, and the
 * dead-letter circuit breaker is not told.
 *
 * <p><b>Nothing it reads or renders is logged.</b> The sample, the rendered request and the reply travel only in the
 * response, as no rendered prompt or member row is ever logged by this service. One boundary line per dry run names
 * the pipeline and its outcome.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DryRunService {

    /** The cap on a returned request or reply; the full request is still sent. */
    static final int RETURNED_BYTES = 64 * 1024;

    private static final String OK = "ok";
    private static final String FAILED = "failed";

    private final AdasTrialInputClient trialInputClient;
    private final InputMapper inputMapper;
    private final GroupInputAssembler groupInputAssembler;
    private final TransformInvoker transformInvoker;
    private final SpendPauseGate spendPauseGate;
    private final JsonMapper jsonMapper;

    /** The pipelines a dry run is running for. The runner is single-replica, so this guard is the whole guard. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Dry-runs {@code pipelineName}.
     *
     * @param caller the credential the request authenticated with, forwarded to ADAS; null when it carried none
     * @throws DryRunInProgressException      when a dry run of this pipeline is already running
     * @throws DryRunAdasRefusalException     when ADAS refused the trial-input read
     * @throws DryRunAdasUnavailableException when ADAS could not be read
     * @throws PipelineNotRunnableException   when this runner would refuse to admit the declaration
     * @throws SpendBudgetExhaustedException  when the pipeline has reached its daily spend budget
     * @throws DialRateLimitedException       when the pipeline's rate limiter had no permit free, and so no call was made
     */
    public DryRunResponse dryRun(String pipelineName, DryRunRequest request, InboundCredential caller) {
        if (!inFlight.add(pipelineName)) {
            throw new DryRunInProgressException(pipelineName);
        }
        long started = System.nanoTime();
        String outcome = "refused";
        try {
            DryRunResponse response = run(pipelineName, request, caller);
            long failed = response.evaluations().stream().filter(e -> FAILED.equals(e.status())).count();
            outcome = "evaluations=" + response.evaluations().size() + " failed=" + failed;
            return response;
        } finally {
            inFlight.remove(pipelineName);
            log.info("dry run pipeline={} {} total_ms={}", pipelineName, outcome, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private DryRunResponse run(String pipelineName, DryRunRequest request, InboundCredential caller) {
        // Asked before the read: the budget is the pipeline's, not the sample's, and a refused dry run should not
        // first pull a whole group of bodies out of ADAS. A sql pipeline records no spend, so it is never over.
        if (spendPauseGate.isOverBudget(pipelineName)) {
            throw new SpendBudgetExhaustedException(pipelineName);
        }
        TrialInputResponse input = readTrialInput(pipelineName, request, caller);
        PipelineDto pipeline = input.pipeline();
        String problem = PipelineRouting.unsupportedReason(pipeline);
        if (problem != null) {
            throw new PipelineNotRunnableException(pipelineName, problem);
        }
        List<Map<String, Object>> rows = input.inputRows() == null ? List.of() : input.inputRows();
        if (rows.isEmpty()) {
            // ADAS's notes say why the sample is empty; there is nothing to call the model on.
            return new DryRunResponse(input.preview(), List.of());
        }
        List<Evaluation> evaluations = PipelineRouting.isGroup(pipeline)
                ? List.of(evaluateGroup(pipeline, groupKeyOf(input.preview()), rows, Boolean.TRUE.equals(input.truncated())))
                : evaluateRows(pipeline, rows);
        // Refused as a whole only when the limiter stopped it before anything was spent; once a call has been paid
        // for, its answer is returned, with the call the limiter refused as a failed evaluation beside it.
        boolean anyCall = evaluations.stream().anyMatch(e -> e.dto().attempts() > 0);
        if (!anyCall && evaluations.stream().anyMatch(Evaluation::rateLimited)) {
            throw new DialRateLimitedException(pipelineName);
        }
        return new DryRunResponse(input.preview(), evaluations.stream().map(Evaluation::dto).toList());
    }

    /** One evaluation's answer, and whether it ended at a rate-limit refusal. */
    private record Evaluation(DryRunEvaluationDto dto, boolean rateLimited) {

        static Evaluation of(DryRunEvaluationDto dto) {
            return new Evaluation(dto, false);
        }
    }

    private TrialInputResponse readTrialInput(String pipelineName, DryRunRequest request, InboundCredential caller) {
        TrialInputRequest body = new TrialInputRequest(request.groupKey(), request.from(), request.to(), request.limit());
        try {
            return trialInputClient.fetch(pipelineName, body, caller);
        } catch (AdasContractMismatchException e) {
            throw new DryRunAdasUnavailableException(DryRunAdasUnavailableException.CONTRACT_MISMATCH, e.getMessage(), e);
        } catch (RestClientResponseException e) {
            // ADAS produced the refusal when it carries the contract echo: pass its answer through. Anything else in
            // front of ADAS answering an error is ADAS being unavailable to this call.
            String code = ErrorMessages.adasErrorCode(e).orElse(null);
            if (code == null) {
                throw new DryRunAdasUnavailableException(DryRunAdasUnavailableException.UNAVAILABLE,
                        "ADAS answered HTTP " + e.getStatusCode().value() + " to the dry run's trial-input read", e);
            }
            throw new DryRunAdasRefusalException(e.getStatusCode().value(), code, adasMessage(e));
        } catch (ResourceAccessException e) {
            throw new DryRunAdasUnavailableException(DryRunAdasUnavailableException.UNAVAILABLE, e.getMessage(), e);
        }
    }

    /** ADAS's human-readable message from its error body, or its status when the body names none. */
    private String adasMessage(RestClientResponseException e) {
        try {
            JsonNode message = jsonMapper.readTree(e.getResponseBodyAsString()).get("message");
            if (message != null && message.isString()) {
                return message.asString();
            }
        } catch (RuntimeException ignored) {
            // Not ErrorView JSON; fall through to the status.
        }
        return "ADAS refused the dry run's trial-input read with HTTP " + e.getStatusCode().value();
    }

    private static String groupKeyOf(JsonNode preview) {
        JsonNode key = preview == null ? null : preview.get("group_key");
        return key == null || key.isNull() ? null : key.asString();
    }

    /** Each row in order, concurrently: at most five, one model call each, on threads apart from the worker pool. */
    private List<Evaluation> evaluateRows(PipelineDto pipeline, List<Map<String, Object>> rows) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Evaluation>> futures = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                futures.add(executor.submit(() -> evaluateRow(pipeline, row)));
            }
            List<Evaluation> evaluations = new ArrayList<>(rows.size());
            for (Future<Evaluation> future : futures) {
                evaluations.add(await(future));
            }
            return evaluations;
        }
    }

    private static Evaluation await(Future<Evaluation> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("dry run interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private Evaluation evaluateRow(PipelineDto pipeline, Map<String, Object> row) {
        Object grainKey = row.get(pipeline.grainKey());
        Map<String, Object> inputs;
        try {
            inputs = inputMapper.map(pipeline, row);
        } catch (RuntimeException e) {
            return Evaluation.of(failedBeforeCall(grainKey, null, DlqStage.INPUT_MAP, e.getMessage(), pipeline));
        }
        TrialOutcome outcome = transformInvoker.trial(pipeline, pipeline.transform(), inputs);
        return new Evaluation(toDto(grainKey, null, outcome, null), outcome.rateLimited());
    }

    private Evaluation evaluateGroup(PipelineDto pipeline, String groupKey, List<Map<String, Object>> members,
                                     boolean fetchTruncated) {
        GroupInputAssembler.AssembledInput assembled;
        try {
            assembled = groupInputAssembler.assemble(pipeline, members, fetchTruncated);
        } catch (RuntimeException e) {
            return Evaluation.of(failedBeforeCall(null, groupKey, DlqStage.GROUP_MAP, e.getMessage(), pipeline));
        }
        TrialOutcome outcome = transformInvoker.trial(pipeline, pipeline.transform(), assembled.asInputs());
        return new Evaluation(toDto(null, groupKey, outcome, assembled), outcome.rateLimited());
    }

    private DryRunEvaluationDto failedBeforeCall(Object grainKey, String groupKey, DlqStage stage, String message,
                                                 PipelineDto pipeline) {
        return new DryRunEvaluationDto(grainKey, groupKey, FAILED, stage, null, null, null, null, null, null,
                ErrorMessages.sanitize(message), null, 0,
                new DryRunEvaluationDto.Usage(0, transformInvoker.usageUnit(pipeline.transform())), 0, null, null, null);
    }

    private static DryRunEvaluationDto toDto(Object grainKey, String groupKey, TrialOutcome outcome,
                                             GroupInputAssembler.AssembledInput assembled) {
        Capped request = Capped.of(outcome.request());
        Capped response = Capped.of(outcome.response());
        return new DryRunEvaluationDto(
                grainKey,
                groupKey,
                outcome.ok() ? OK : FAILED,
                outcome.stage(),
                outcome.outputs(),
                request.text(),
                request.flag(),
                response.text(),
                response.flag(),
                outcome.dialStatus(),
                outcome.message() == null ? null : ErrorMessages.sanitize(outcome.message()),
                outcome.errors().isEmpty() ? null : outcome.errors(),
                outcome.attempts(),
                new DryRunEvaluationDto.Usage(outcome.usage(), outcome.usageUnit()),
                outcome.latencyMs(),
                assembled == null ? null : assembled.members().size(),
                assembled == null ? null : assembled.omitted(),
                assembled == null ? null : assembled.truncated());
    }

    /**
     * A returned text, cut to {@link #RETURNED_BYTES} of UTF-8 at a code point boundary, and whether it was cut; both
     * null for an absent text.
     */
    record Capped(String text, Boolean flag) {

        static Capped of(String text) {
            if (text == null) {
                return new Capped(null, null);
            }
            if (text.getBytes(StandardCharsets.UTF_8).length <= RETURNED_BYTES) {
                return new Capped(text, false);
            }
            int end = 0;
            int size = 0;
            while (end < text.length()) {
                int codePoint = text.codePointAt(end);
                int width = utf8Width(codePoint);
                if (size + width > RETURNED_BYTES) {
                    break;
                }
                size += width;
                end += Character.charCount(codePoint);
            }
            return new Capped(text.substring(0, end), true);
        }

        /** The bytes {@code codePoint} takes in UTF-8, as {@code getBytes} writes it: a lone surrogate as one {@code ?}. */
        private static int utf8Width(int codePoint) {
            if (codePoint < 0x80) {
                return 1;
            }
            if (codePoint < 0x800) {
                return 2;
            }
            if (codePoint < 0x10000) {
                return Character.isSurrogate((char) codePoint) ? 1 : 3;
            }
            return 4;
        }
    }
}
