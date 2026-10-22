package com.epam.aidial.enrichment.support;

import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.scan.PageAccounting;

import java.util.Map;

/** Builds {@link PageAccounting} fixtures — what a scanned page did with the rows it read. */
public final class TestPages {

    private TestPages() {
    }

    /** Every row became work: the ordinary shape, which the cursor guard passes. */
    public static PageAccounting allWork(int rows) {
        return new PageAccounting(rows, rows, 0, Map.of());
    }

    /**
     * Rows read, none accounted for — the shape the cursor guard exists to refuse, and the one the
     * grain-key skew produced silently before it did.
     */
    public static PageAccounting unaccounted(int rows) {
        return new PageAccounting(rows, 0, 0, Map.of());
    }

    /** Some rows accounted for and the rest not: the partial case the equality test is what catches. */
    public static PageAccounting partly(int rows, int work, int deadLettered) {
        return new PageAccounting(rows, work, deadLettered, Map.of());
    }

    /** Every row excluded for one declared reason, which accounts for it and still advances the cursor. */
    public static PageAccounting allSkipped(int rows, RunnerMetrics.SkipReason reason) {
        return new PageAccounting(rows, 0, 0, Map.of(reason, rows));
    }

    /** Work and dead letters covering the page exactly. */
    public static PageAccounting workAndDeadLetters(int work, int deadLettered) {
        return new PageAccounting(work + deadLettered, work, deadLettered, Map.of());
    }
}
