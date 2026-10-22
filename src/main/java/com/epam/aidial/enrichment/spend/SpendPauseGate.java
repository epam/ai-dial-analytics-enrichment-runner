package com.epam.aidial.enrichment.spend;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.configuration.SpendProperties;
import com.epam.aidial.enrichment.observability.TransitionLog;
import com.epam.aidial.enrichment.sync.PipelineRouting;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Decides whether a pipeline has exceeded its daily spend budget and should be skipped at enqueue. The
 * gate is soft: it re-evaluates each scan fire against the current UTC day's spend, so a pipeline
 * auto-resumes when the day rolls over (unlike the DLQ circuit breaker's hard pause).
 *
 * <p>The gate covers model-calling pipelines only. A Class-1 ({@code transform.type=sql}) pipeline is excluded
 * outright rather than evaluated against a zero counter: it makes no DIAL calls, so a budget expressed
 * in calls or tokens has no meaning for it. The exclusion lives here, not merely in the Class-1 tick's
 * choice not to ask, so every present and future caller inherits it.
 *
 * <p>The gate logs its own transitions — closed, then open again — with the counters that decided them, once per
 * change rather than once per fire, so every caller (scan, sweep, backfill) can skip quietly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpendPauseGate {

    private final SpendService spendService;
    private final SpendProperties spendProperties;

    /** Whether the gate was last reported closed, per pipeline. */
    private final TransitionLog<Boolean> transitions = new TransitionLog<>(Boolean.FALSE);

    /** Whether the pipeline is over its daily budget. A Class-1 pipeline never is — it spends nothing. */
    public boolean isOverBudget(PipelineDto pipeline) {
        return !PipelineRouting.isClass1(pipeline) && isOverBudget(pipeline.name());
    }

    public boolean isOverBudget(String pipelineName) {
        if (!spendProperties.pause().enabled()) {
            return false;
        }
        DailySpend spend = spendService.today(pipelineName);
        long callBudget = spendProperties.budget().dailyCalls();
        long tokenBudget = spendProperties.budget().dailyTokens();
        boolean closed = (callBudget > 0 && spend.calls() >= callBudget)
                || (tokenBudget > 0 && spend.tokens() >= tokenBudget);
        if (transitions.changed(pipelineName, closed)) {
            log.info("spend gate {}: pipeline={} calls={}/{} tokens={}/{}", closed ? "closed" : "open",
                    pipelineName, spend.calls(), callBudget, spend.tokens(), tokenBudget);
        }
        return closed;
    }

    /** Forgets a pipeline's last reported gate state when it leaves the synced set, bounding the map. */
    public void evict(String pipelineName) {
        transitions.forget(pipelineName);
    }
}
