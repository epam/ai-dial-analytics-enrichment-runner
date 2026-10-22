package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.exception.UnaccountedScanPageException;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import lombok.extern.slf4j.Slf4j;

/**
 * The one rule every cursor advance passes through: a page whose accounted outcomes do not cover every row
 * it read does not get its advance committed.
 *
 * <p>Stated once and applied by all three committers — {@link ScanPageCommitter}, the group lane's
 * {@code GroupDirtyMarkCommitter} and the backfill's {@code BackfillPageCommitter} — because a cursor
 * advance is exactly what it protects and there are three cursors. The backfill's is the one where a silent
 * skip is unrecoverable by waiting: a run drains a whole window, marks itself complete, and nothing
 * afterwards says the window was not covered.
 *
 * <p>Deliberately <b>blind to cause</b>. It does not know whether a required column was missing, a loop
 * exited early or a future branch forgot to count; it knows the page read N rows and fewer than N reached an
 * outcome, which is enough to refuse. The dead-letter circuit breaker cannot serve this role because it
 * returns immediately when nothing was dead-lettered — so a page that produced nothing at all never reaches
 * it.
 */
@Slf4j
public final class CursorAccountingGuard {

    private CursorAccountingGuard() {
    }

    /**
     * Throws unless every row of the page reached an outcome, counting the refusal against the pipeline.
     * Called before anything is written, so the transaction has nothing to roll back.
     */
    public static void requireAccounted(RunnerMetrics runnerMetrics, String pipelineName, PageAccounting accounting) {
        if (accounting.coversEveryRow()) {
            return;
        }
        runnerMetrics.recordRefusedPage(pipelineName, RunnerMetrics.RefusalReason.UNACCOUNTED_ROWS);
        log.error("Refusing the cursor advance for pipeline {}: the page read {} row(s), of which {} became work, "
                        + "{} were dead-lettered and {} were skipped for a declared reason",
                pipelineName, accounting.rowCount(), accounting.workRows(), accounting.deadLetteredRows(),
                accounting.accountedRows() - accounting.workRows() - accounting.deadLetteredRows());
        throw new UnaccountedScanPageException(pipelineName, accounting.accountedRows(), accounting.rowCount());
    }
}
