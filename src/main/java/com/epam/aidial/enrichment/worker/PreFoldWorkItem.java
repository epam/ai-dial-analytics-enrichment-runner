package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.observability.RunnerMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * Recognises a work item enqueued <em>before</em> ADAS's evaluator fold, and reports what the lane that
 * found it did with it.
 *
 * <p><strong>Why this exists.</strong> Scaling this service to zero stops execution; it does not empty
 * {@code scheduled_tasks}. An item enqueued by the pre-fold image carries a payload naming
 * {@code evaluatorVersion}, and db-scheduler's {@code JacksonSerializer} disables
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} — so the new build does not reject it, it deserializes it with
 * {@code pipelineGeneration = 0}. Every lane's stale-declaration guard then matched
 * {@code pipeline.generation() != 0} and dropped the item at {@code info}, in the same line an ordinary
 * knob edit produces. On the row lane that lost rows the scan cursor had already advanced past; on the
 * write lane it destroyed a result the model had already been paid for.
 *
 * <p><strong>Why {@code 0} is a safe discriminator.</strong> ADAS bumps {@code generation} on every accepted
 * mutation and a registered pipeline's first generation is 1, so 0 is a value the feed never serves. The
 * absent-member reading and the sentinel therefore coincide, and no heuristic over the payload's text is
 * needed.
 *
 * <p><strong>What the lanes do with it, and why they differ.</strong> The runner <em>tolerates</em> such an
 * item rather than requiring the queue to have been drained first, because draining is an operator action
 * this deployment model does not have: the drain would have to live in the 0.2 image that is already
 * deployed without it, and an execution SIGKILLed at shutdown comes back as a pre-fold item on the next
 * start regardless.
 *
 * <ul>
 *   <li><strong>chunk</strong> and <strong>upsert</strong> — <em>adopt</em> the live generation
 *       ({@code payload.setPipelineGeneration(pipeline.generation())}) and carry on. Normalising is not
 *       the same as skipping the stale check: the payload's generation is read again downstream — it
 *       builds the derived {@link com.epam.aidial.enrichment.worker.upsert.UpsertPayload}, it stamps the
 *       DLQ row identity, it keys failure spend and it is an MDC field — and a 0 left in place turns each
 *       of those into its own defect, the worst being a derived write the write lane would then see as
 *       pre-fold in turn.</li>
 *   <li><strong>group</strong> — <em>discarded</em>, with no dead-letter row. A {@code GroupPayload} is
 *       keys only, the group is still dirty ({@code group_version > computed_version}), and a discarded
 *       item stamps nothing, so the readiness sweep re-enqueues it at the current generation by itself.
 *       Adopting would be actively worse than doing nothing: the sweep's in-flight dedup keys on
 *       {@code DeterministicId.groupInstanceId(...)}, which embeds the generation, so a pre-fold group
 *       item does not suppress the sweep's own item — tolerating it would pay for two model calls on one
 *       group, on the most expensive lane the runner has.</li>
 * </ul>
 *
 * <p><strong>What bounds the downside of adopting.</strong> A chunk's rows carry input values extracted
 * under the previous {@code transform.inputs}, so the current template may name a variable they do not
 * bind. That is already handled and costs nothing: {@code TemplateRenderer.requireEveryPlaceholderBound}
 * throws {@code UnresolvedPlaceholderException} <em>before</em> the request is assembled, so no DIAL call
 * is made and no spend is recorded, and the row dead-letters at {@code input_map} naming the placeholder.
 * A whole chunk failing that way can count enough dead-letters to trip the DLQ circuit breaker and pause
 * its pipeline, which self-lifts after {@code DLQ_BREAKER_PAUSE_FOR} — an accepted cost, and the reason
 * this is a tolerance rather than a promise.
 */
@Slf4j
public final class PreFoldWorkItem {

    /**
     * The generation a pre-fold payload deserializes to. Never served by ADAS, whose first generation for a
     * registered pipeline is 1.
     */
    public static final long PRE_FOLD_GENERATION = 0L;

    /** Which lane found the item — the metric's only tag, and part of the log line. */
    public enum Lane {

        CHUNK, GROUP, UPSERT;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private PreFoldWorkItem() {
    }

    /** Whether this payload was serialized by an image that predates the evaluator fold. */
    public static boolean isPreFold(long pipelineGeneration) {
        return pipelineGeneration == PRE_FOLD_GENERATION;
    }

    /**
     * Reports and counts an adoption. The caller does the assignment and the MDC refresh, because only it
     * knows which payload type it holds; this exists so the two lanes that adopt say the same thing.
     *
     * <p>WARN rather than the {@code info} a stale drop gets, and rather than the ERROR an archive used to
     * get. It is not a failure — the item runs — but it is the only evidence that work crossed the fold,
     * and it is over as soon as the pre-fold queue is consumed.
     */
    public static void reportAdopted(Lane lane, String pipelineName, long adopted, RunnerMetrics metrics) {
        log.warn("Adopting generation {} for a {} work item of pipeline {} enqueued before ADAS's evaluator "
                        + "fold: it names no pipeline generation, so it is normalised onto the live declaration "
                        + "and run rather than dropped as stale",
                adopted, lane.tag(), pipelineName);
        metrics.recordPreFoldWorkItem(lane);
    }

    /**
     * Reports and counts a discarded group item. No dead-letter row is written, deliberately: the sweep has
     * already redone this work by the time anyone reads the DLQ, so a terminal, un-requeueable row would be
     * misinformation — and it would inflate {@code runner.dlq.size}, which alerts are written against.
     */
    public static void reportDiscarded(Lane lane, String pipelineName, String groupKey, RunnerMetrics metrics) {
        log.warn("Discarding a {} work item for group {} of pipeline {}: it was enqueued before ADAS's "
                        + "evaluator fold and carries keys only, so the readiness sweep re-derives it at the "
                        + "current generation — nothing is dead-lettered and nothing is lost",
                lane.tag(), groupKey, pipelineName);
        metrics.recordPreFoldWorkItem(lane);
    }
}
