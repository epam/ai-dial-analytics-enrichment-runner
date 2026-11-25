package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.dlq.DlqStage;

import java.util.List;
import java.util.Map;

/**
 * One dry-run evaluation as {@link TransformInvoker#trial} ran it: what was sent, what came back, and what a worker
 * would have written or dead-lettered — returned instead of thrown, because a model's failure is the dry run's answer.
 *
 * @param outputs    the mapped physical enrichment columns when the evaluation succeeded; null when it failed
 * @param request    the request body of the first attempt, or null when the evaluation failed before one was built
 * @param response   the raw reply of the last attempt — DIAL's error body for a refused call — or null when none came
 * @param attempts   the DIAL calls made, the corrective retries included
 * @param usage      the usage the replies reported, in {@code usageUnit}
 * @param usageUnit  {@code tokens} for a chat completion, {@code calls} for an application route
 * @param latencyMs  the whole evaluation's wall time
 * @param stage      the stage a worker would dead-letter the failure at; null when the evaluation succeeded
 * @param dialStatus DIAL's HTTP status for a refused call; null otherwise
 * @param message    what failed, for people; null when the evaluation succeeded
 * @param errors     the response-schema violations of the last reply, empty unless the failure is at {@code validate}
 * @param rateLimited whether the failure is a call the pipeline's rate limiter had no permit for — at {@code dial_call},
 *                    after {@code attempts} calls that were made
 */
public record TrialOutcome(
        Map<String, Object> outputs,
        String request,
        String response,
        int attempts,
        long usage,
        String usageUnit,
        long latencyMs,
        DlqStage stage,
        Integer dialStatus,
        String message,
        List<String> errors,
        boolean rateLimited) {

    public boolean ok() {
        return stage == null;
    }
}
