package com.epam.aidial.enrichment.worker.group;

import com.epam.aidial.enrichment.adas.AdasGroupFetchClient;
import com.epam.aidial.enrichment.adas.AdasWorkGate;
import com.epam.aidial.enrichment.adas.dto.GroupFetchResponse;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.TransformDto;
import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.epam.aidial.enrichment.dlq.DlqCircuitBreaker;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.OutputMappingException;
import com.epam.aidial.enrichment.exception.ResponseValidationException;
import com.epam.aidial.enrichment.exception.TransientChunkException;
import com.epam.aidial.enrichment.exception.UnresolvedPlaceholderException;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.RunnerTracer;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.persistence.GroupStateWriter;
import com.epam.aidial.enrichment.spend.SpendService;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.PreFoldWorkItem;
import com.epam.aidial.enrichment.worker.ProvenanceColumns;
import com.epam.aidial.enrichment.worker.RetryableStatus;
import com.epam.aidial.enrichment.worker.TransformInvoker;
import com.epam.aidial.enrichment.worker.WorkerMdc;
import com.epam.aidial.enrichment.worker.upsert.UpsertEnqueuer;
import com.epam.aidial.enrichment.worker.upsert.UpsertPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Processes one group work item: fetch the group's members from ADAS, assemble them into one prompt, make
 * one model call, and write one enrichment row keyed by the group key.
 *
 * <p>A group is <strong>1:0..1</strong>, which is what makes this different from the row worker rather than
 * just coarser. There is no partial outcome to isolate: the group either produces a label or it fails whole.
 * So a retryable failure fails the item (leaving the group dirty, which is exactly the state that gets it
 * re-swept) and a deterministic failure dead-letters the group at group granularity.
 *
 * <p>Nothing here runs while ADAS is incompatible with this build's contract: {@link AdasWorkGate} refuses
 * the item at the top, so no {@code group-rows} call is made and no model call is charged. The
 * {@code group-rows} call preceding the DIAL call means the exception arm covers this lane well even
 * without the gate — but only for the <em>first</em> item, which is an argument for having both arms rather
 * than for skipping the cheap one here.
 *
 * <p><strong>Body handling.</strong> A group's assembled members are the largest and most sensitive payload
 * the runner handles. They are never logged — not at {@code info}, not at {@code debug}, not at {@code trace} —
 * and neither is a rendered prompt; a test asserts this rather than trusting review. The only place derived
 * content persists is a dead-letter item's raw response, bounded by the dead-letter retention window. Members
 * exist solely in memory for the duration of one execution.
 *
 * <p>This is the <strong>compute</strong> phase only: it ends by enqueueing an {@code upsert-rows} item, and the
 * successful write is what stamps the group clean. Stamping here instead would mark a group clean before its row
 * landed, so a write that then failed permanently would leave a group the sweep never revisits and an enrichment
 * table with no row for it. A crash before the write lands leaves the group dirty, so it is re-evaluated and the
 * identical row collapses on the target's {@code ReplacingMergeTree}: at-least-once execution, exactly-once
 * effect.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogExecution
public class GroupWorkerService {

    private final PipelineCache pipelineCache;
    private final AdasGroupFetchClient groupFetchClient;
    private final GroupInputAssembler inputAssembler;
    private final UpsertEnqueuer upsertEnqueuer;
    private final TransformInvoker transformInvoker;
    private final GroupStateWriter groupStateWriter;
    private final DlqItemWriter dlqItemWriter;
    private final DlqCircuitBreaker dlqCircuitBreaker;
    private final SpendService spendService;
    private final RunnerMetrics runnerMetrics;
    private final RunnerTracer runnerTracer;
    private final GroupProperties groupProperties;
    private final AdasWorkGate adasWorkGate;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public void process(String itemId, GroupPayload payload) {
        try (WorkerMdc.Scope ignored = WorkerMdc.scope()
                .put(WorkerMdc.PIPELINE_NAME, payload.getPipelineName())
                .put(WorkerMdc.CHUNK_ID, itemId)
                .put(WorkerMdc.PIPELINE_GENERATION, Long.toString(payload.getPipelineGeneration()))
                .put(WorkerMdc.GROUP_KEY, payload.getGroupKey())) {
            // The costliest lane per item and the easiest to overlook: this one assembles a group's members
            // through ADAS and then makes one model call over them, so a group pipeline left ungated would
            // keep paying for the largest calls the runner makes for the whole length of a skew.
            adasWorkGate.requireCompatible("group " + payload.getGroupKey());
            log.info("Processing group {} for pipeline {} at version {}",
                    payload.getGroupKey(), payload.getPipelineName(), payload.getGroupVersion());
            doProcess(itemId, payload);
        }
    }

    private void doProcess(String itemId, GroupPayload payload) {
        if (PreFoldWorkItem.isPreFold(payload.getPipelineGeneration())) {
            // FIRST, ahead of the cache and stale guards, and the one lane that does NOT adopt the live
            // generation — see PreFoldWorkItem. A GroupPayload is keys only and the group is still dirty
            // (group_version > computed_version), so the readiness sweep re-derives it at the current
            // generation by itself; discarding here stamps nothing and therefore takes nothing away from
            // it. Running it instead would be worse than doing nothing: GroupReadinessSweep's in-flight
            // dedup keys on DeterministicId.groupInstanceId(...), which embeds the generation, so this
            // item does not suppress the sweep's own — one group, two model calls, on the most expensive
            // lane there is.
            //
            // Discarded WITHOUT a dead-letter row, deliberately. A terminal, un-requeueable row for work
            // the sweep has already redone is misinformation, and it inflates runner.dlq.size, which
            // alerts are written against. The WARN and the lane-tagged counter are the whole record.
            PreFoldWorkItem.reportDiscarded(PreFoldWorkItem.Lane.GROUP, payload.getPipelineName(),
                    payload.getGroupKey(), runnerMetrics);
            return;
        }
        // Asked before the lookup: an empty cache at startup means "no poll has happened yet", not "the
        // pipeline is disabled". Dropping here costs a re-derivation rather than the data — the group stays
        // dirty and is re-swept — but a restart would otherwise blow through the whole queued backlog in
        // seconds, which is how a deploy turns a drained pipeline back into an undrained one.
        pipelineCache.requirePopulated("group item " + itemId);
        PipelineDto pipeline = pipelineCache.get(payload.getPipelineName());
        if (pipeline == null) {
            log.info("Dropping group item for pipeline {} (absent from cache)", payload.getPipelineName());
            return;
        }
        if (isStale(pipeline, payload)) {
            log.info("Dropping stale group item for pipeline {} (built against generation {})",
                    payload.getPipelineName(), payload.getPipelineGeneration());
            return;
        }

        GroupFetchResponse fetched;
        try {
            fetched = fetchMembers(payload);
        } catch (PoisonGroupException e) {
            deadLetter(payload, e, false);
            return;
        }
        List<Map<String, Object>> memberRows = fetched.rows() == null ? List.of() : fetched.rows();
        if (memberRows.isEmpty()) {
            // An empty group is a success, not a failure: ADAS answers an unknown or fully filtered-out
            // group with 200 and no rows. Stamping it clean is what stops it being re-swept forever.
            log.info("Group {} for pipeline {} has no members; stamping clean without a write",
                    payload.getGroupKey(), payload.getPipelineName());
            stamp(payload);
            // A completed group, and reported as one: the breaker's group lane measures a failure rate, and a
            // denominator that counted only the groups reaching a model would read a pipeline whose groups mostly
            // resolve empty as far more failure-prone than it is.
            dlqCircuitBreaker.recordGroupSuccess(payload.getPipelineName());
            return;
        }

        // Assembly is its own block so an assembly failure is distinguishable from a post-model one. The
        // distinction decides whether the group's cost ceiling is charged: nothing here has called the
        // model yet, and charging for a call that never happened would let a misconfigured pipeline exhaust a
        // ceiling without ever producing a label.
        GroupInputAssembler.AssembledInput input;
        try {
            input = runnerTracer.inSpan("enrichment.group-assembly", spanAttributes(payload),
                    () -> inputAssembler.assemble(pipeline, memberRows, fetched.truncated()));
        } catch (TransientChunkException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Dead-lettering group {} for pipeline {} during assembly: {}",
                    payload.getGroupKey(), payload.getPipelineName(), e.toString());
            deadLetter(payload, new PoisonGroupException(DlqStage.GROUP_MAP, e.getMessage(), null), false);
            return;
        }

        // One truth for "the judgement is partial": the assembly folds ADAS's caps into its own trimming, so
        // the prompt's caveat and the row's provenance cannot disagree.
        boolean truncated = input.truncated();
        if (input.itemsCut()) {
            // Counted separately from `truncated`, which a pipeline's own member-selection policy sets on every
            // narrowed group and which therefore says nothing about the budget. This counts only the case
            // where content the model would otherwise have seen was removed — the one measurement a change
            // to group.input-token-budget can be argued from.
            runnerMetrics.recordInputBudgetCut();
        }
        TransformDto transform = pipeline.transform();
        Map<String, Object> writeRow;
        // Past this point a failure means the model call was attempted, so the evaluation is charged.
        try {
            Map<String, Object> columns = transformInvoker.derive(pipeline, transform, input.asInputs());
            writeRow = buildRow(pipeline, transform, payload, columns, truncated);
        } catch (RestClientResponseException e) {
            if (RetryableStatus.isRetryable(e)) {
                throw new TransientChunkException(DlqStage.DIAL_CALL, e);
            }
            spendService.record(payload.getPipelineName(), payload.getPipelineGeneration(), 0, true);
            deadLetter(payload, new PoisonGroupException(DlqStage.DIAL_CALL, e.getMessage(),
                    e.getResponseBodyAsString()), true);
            return;
        } catch (RestClientException e) {
            // Connection/IO failure with no HTTP status: transient, retry the whole group.
            throw new TransientChunkException(DlqStage.DIAL_CALL, e);
        } catch (ResponseValidationException e) {
            runnerMetrics.incrementValidationFailures();
            deadLetter(payload, new PoisonGroupException(DlqStage.VALIDATE, e.getMessage(), e.getRawResponse()),
                    true);
            return;
        } catch (TransientChunkException e) {
            // Rate-limiter exhaustion lands here without a call having been made; either way a transient
            // failure neither stamps nor charges, since the group stays dirty and will be retried.
            throw e;
        } catch (OutputMappingException e) {
            deadLetter(payload, new PoisonGroupException(DlqStage.GROUP_MAP, e.getMessage(), null), true);
            return;
        } catch (UnresolvedPlaceholderException e) {
            // Its own arm, ahead of the catch-all below, because of the flag it passes: the render happens
            // before the request is assembled, so no model call was made and the evaluation must NOT be
            // charged against the group's daily ceiling. Left to the catch-all it would be archived as
            // `modelCalled`, letting a template with one bad placeholder exhaust a ceiling without ever
            // producing a label.
            log.warn("Dead-lettering group {} for pipeline {}: {}",
                    payload.getGroupKey(), payload.getPipelineName(), e.getMessage());
            deadLetter(payload, new PoisonGroupException(DlqStage.GROUP_MAP, e.getMessage(), null), false);
            return;
        } catch (RuntimeException e) {
            log.warn("Dead-lettering group {} for pipeline {}: {}",
                    payload.getGroupKey(), payload.getPipelineName(), e.toString());
            deadLetter(payload, new PoisonGroupException(DlqStage.GROUP_MAP, e.getMessage(), null), true);
            return;
        }

        // Re-check before persisting: a disable or re-pin mid-flight drops the group without writing.
        PipelineDto current = pipelineCache.get(payload.getPipelineName());
        if (current == null || isStale(current, payload)) {
            log.info("Dropping group item for pipeline {}: pipeline changed mid-flight", payload.getPipelineName());
            return;
        }

        // Compute ends here. The write is a separate item, and its success is what stamps the group clean —
        // stamping now would mark it clean before the row landed.
        upsertEnqueuer.commit(itemId, new UpsertPayload(payload.getPipelineName(), payload.getPipelineGeneration(),
                List.of(writeRow), payload.getGroupKey(), payload.getGroupVersion(), truncated), List.of());
        log.info("Computed group {} for pipeline {}; queued for writing",
                payload.getGroupKey(), payload.getPipelineName());
        runnerMetrics.recordGroupEvaluated();
    }

    private GroupFetchResponse fetchMembers(GroupPayload payload) {
        try {
            return groupFetchClient.fetch(payload.getPipelineName(), payload.getGroupKey(),
                    groupProperties.fetchLimit());
        } catch (RestClientResponseException e) {
            if (RetryableStatus.isRetryable(e)) {
                throw new TransientChunkException(DlqStage.GROUP_FETCH, e);
            }
            // A rejection ADAS will keep rejecting (422 non-group pipeline, 403 not entitled, 400 bad limit):
            // replaying it cannot help, so the group is dead-lettered rather than retried to exhaustion.
            // ADAS now reports its own resource exhaustion as a 5xx, so a 4xx capacity refusal is its own
            // read ceiling — see RetryableStatus. deadLetter() still archives the payload, because that is
            // what keeps a wrong ruling a requeue rather than the work.
            throw new PoisonGroupException(DlqStage.GROUP_FETCH, e.getMessage(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new TransientChunkException(DlqStage.GROUP_FETCH, e);
        }
    }

    /**
     * The group's single enrichment row: its grain value is the group key, and it carries the full provenance
     * set. {@code group_version} and {@code truncated} are what let a downstream reader tell a label derived
     * from a trimmed or stale group from a complete, current one — ADAS drops either silently when the target
     * does not carry it, so they are always sent.
     *
     * <p>The key column is the target enrichment's <b>grain key</b>, not the pipeline's {@code group_by}. The two
     * are the same name only while {@code group_by} names a bare source column; when it names an enrichment
     * column it carries the qualified spelling ({@code <enrichment>.<column>}) that the read source projects
     * it under, and that name exists on no enrichment table. Writing it would be rejected by ADAS with 422
     * {@code write_column_not_allowed} — a permanent rejection, so every group of such a pipeline dead-letters.
     * The grain key is what the target is physically keyed on and the spelling the key round-trips as
     * through the write-back, exactly as on the row-grain path.
     */
    private Map<String, Object> buildRow(PipelineDto pipeline, TransformDto transform, GroupPayload payload,
                                         Map<String, Object> columns, boolean truncated) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(pipeline.grainKey(), payload.getGroupKey());
        row.putAll(columns);
        row.put(ProvenanceColumns.PIPELINE_GENERATION, pipeline.generation());
        row.put(ProvenanceColumns.MODEL, transform.model());
        row.put(ProvenanceColumns.ENRICHED_AT, clock.instant().toString());
        row.put(ProvenanceColumns.GROUP_VERSION, payload.getGroupVersion());
        row.put(ProvenanceColumns.TRUNCATED, truncated);
        return row;
    }

    /**
     * Stamps without charging: for an outcome that spent no model call.
     *
     * <p>This and {@link #stampAndCharge} record {@code truncated = false}: they serve the empty-group and
     * dead-letter outcomes, which wrote no label for a trimmed input to qualify.
     *
     * <p>Through the injected clock, not {@code Instant.now()} — {@code computed_at} is the value the pipeline's
     * {@code max_staleness} and state eviction later compare against, so a stamp written off a different clock
     * than the one those read is a bug that only shows up as a group firing at the wrong time.
     */
    private void stamp(GroupPayload payload) {
        groupStateWriter.stampComputed(payload.getPipelineName(), payload.getGroupKey(),
                payload.getGroupVersion(), clock.instant(), false);
    }

    /** Stamps and charges one evaluation against the pipeline's daily ceiling: the model call was spent. */
    private void stampAndCharge(GroupPayload payload) {
        Instant now = clock.instant();
        GroupStateWriter.ChargeOutcome outcome = groupStateWriter.stampComputedAndCharge(payload.getPipelineName(),
                payload.getGroupKey(), payload.getGroupVersion(), now, false,
                LocalDate.ofInstant(now, ZoneOffset.UTC));
        if (outcome.superseded()) {
            reportSuperseded(payload);
        }
    }

    /**
     * A newer evaluation of the same group stamped it first, so this one's stamp was refused — but its model
     * call was spent and is charged. Reported because the sweep enqueued two versions of one group, which
     * means its evaluations are outrunning the sweep interval: cheap to see, invisible otherwise.
     */
    private void reportSuperseded(GroupPayload payload) {
        runnerMetrics.recordSupersededGroupEvaluation();
        log.info("Charged a superseded evaluation of group {} for pipeline {}: a newer version stamped it first",
                payload.getGroupKey(), payload.getPipelineName());
    }

    /**
     * Dead-letters a poison group and completes the execution rather than failing it. A deterministic failure
     * gains nothing from replay, so retrying it would only burn a `group-rows` call and a model call per
     * attempt.
     *
     * <p>The evaluation is charged against the pipeline's daily ceiling only when the model call was actually
     * spent — a group ADAS refused to assemble consumed no evaluation.
     *
     * <p>The group is also <strong>stamped clean</strong>. Leaving it dirty would have the readiness sweep
     * re-enqueue it on every pass, re-running the same doomed evaluation forever — the DLQ row would dedup on
     * its partial unique key, so the loop would be invisible in the DLQ while still spending. Stamping it at
     * this version means it is retried only when something actually changes: a new member raises
     * `group_version` and makes it dirty again, which is exactly when a re-attempt could succeed.
     *
     * <p>The item is archived <strong>with its payload</strong>, so the stamp is not the end of the story. It
     * used to be archived without one, on the reasoning that a deterministic failure gains nothing from
     * replay — which is true only while the ruling that it <em>is</em> deterministic is correct. The runner
     * classifies from the HTTP status alone, so it depends on the upstream reporting its own exhaustion as a
     * 5xx; ADAS does (see {@link com.epam.aidial.enrichment.worker.RetryableStatus}), but that is the
     * upstream's promise rather than something this side can check. Without a payload, a wrong ruling
     * destroys the work silently, and produces the perverse result that a clean deterministic refusal is
     * worse to receive than a socket timeout, which is ruled transient and archived requeueably. A
     * {@link GroupPayload} is four scalars; storing it makes a misclassification a question of latency
     * instead of loss.
     */
    private void deadLetter(GroupPayload payload, PoisonGroupException poison, boolean modelCalled) {
        dlqItemWriter.upsert(DlqItemEntity.builder()
                .pipelineName(payload.getPipelineName())
                .grainKey(payload.getGroupKey())
                .pipelineGeneration(payload.getPipelineGeneration())
                .stage(poison.stage().dbValue())
                .error(poison.getMessage())
                .rawResponse(poison.rawResponse())
                .payload(jsonMapper.writeValueAsString(payload))
                .taskName(GroupChunkTask.TASK_NAME)
                .build());
        if (modelCalled) {
            stampAndCharge(payload);
        } else {
            stamp(payload);
        }
        dlqCircuitBreaker.recordGroupFailure(payload.getPipelineName());
        runnerMetrics.recordGroupDeadLettered();
        log.warn("Dead-lettered group {} for pipeline {} at stage {}",
                payload.getGroupKey(), payload.getPipelineName(), poison.stage().dbValue());
    }

    private static Map<String, String> spanAttributes(GroupPayload payload) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("pipeline.name", payload.getPipelineName());
        attributes.put("group.key", payload.getGroupKey());
        attributes.put("group.version", Long.toString(payload.getGroupVersion()));
        return attributes;
    }

    private static boolean isStale(PipelineDto pipeline, GroupPayload payload) {
        return pipeline.generation() != payload.getPipelineGeneration();
    }
}
