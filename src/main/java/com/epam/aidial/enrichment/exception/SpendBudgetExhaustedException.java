package com.epam.aidial.enrichment.exception;

/**
 * The pipeline has reached the soft daily spend budget, so a dry run would spend past it. Mapped to HTTP 429
 * {@code spend_budget_exhausted}; the gate reopens when the UTC day rolls over.
 */
public class SpendBudgetExhaustedException extends RuntimeException {

    public SpendBudgetExhaustedException(String pipelineName) {
        super("pipeline '" + pipelineName + "' has reached its daily spend budget; a dry run would spend past it");
    }
}
