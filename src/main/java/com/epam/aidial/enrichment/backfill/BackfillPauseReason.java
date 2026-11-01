package com.epam.aidial.enrichment.backfill;

import com.epam.aidial.enrichment.exception.UnknownEnumValueException;

import java.util.Arrays;
import java.util.Locale;

/**
 * Why a backfill run was paused — recorded on {@code run_history.paused_reason} at the moment it pauses, and
 * present only while the run stays paused. The database stores the lowercase form and constrains the column to
 * exactly this set.
 *
 * <p>One word is not an answer: the conditions call for materially different operator responses. The condition
 * behind a spend pause lifts at 00:00 UTC; behind a breaker pause when its window elapses; behind a
 * {@link #PIPELINE_STOPPED stopped pipeline} when somebody re-enables it in ADAS; behind a
 * {@link #CURSOR_STALLED cursor stall} only when somebody fixes something. In every case it is the
 * <em>condition</em> that lifts — the run stays paused until it is resumed.
 *
 * <p>The value records what <strong>paused</strong> the run, not what holds it now. The start and resume gates
 * answer the second question.
 */
public enum BackfillPauseReason {

    /** An operator paused the run through the API. */
    OPERATOR,

    /**
     * The run's pipeline was paused by an operator through the runner's own pause, which no longer exists: a
     * pipeline is stopped in ADAS now, recorded as {@link #PIPELINE_STOPPED}. Never written; kept so a run paused
     * before that change still reads back, and so the database constraint and this set stay one list.
     */
    PIPELINE_PAUSED_OPERATOR,

    /** The run's pipeline was paused by the DLQ circuit breaker; the condition lifts when its window elapses. */
    PIPELINE_PAUSED_BREAKER,

    /**
     * The run's pipeline is stopped — disabled in ADAS, its single run control. Kept apart from
     * {@link #PIPELINE_ABSENT}: a stopped pipeline is still cached and still runs once re-enabled, so its run is
     * resumable then, where an absent one's answers {@code 404} until the pipeline is back.
     */
    PIPELINE_STOPPED,

    /** The pipeline's daily spend budget was exhausted; the budget resets at 00:00 UTC. */
    SPEND_BUDGET_EXHAUSTED,

    /**
     * ADAS reported more rows without advancing the cursor. Resuming without investigating re-enters the same
     * page, which is why the guard exists at all.
     */
    CURSOR_STALLED,

    /**
     * The pipeline was absent from a populated pipeline cache: deleted in ADAS or no longer served by it, <strong>or
     * refused by the runner as unexecutable</strong>, which also keeps a pipeline out of the cache. A disabled
     * pipeline is not absent: it is {@link #PIPELINE_STOPPED}.
     */
    PIPELINE_ABSENT,

    /** The pipeline's declaration changed (its {@code generation} moved) since the run was started or resumed. */
    PIPELINE_CHANGED;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses the stored or wire spelling, case-insensitively. */
    public static BackfillPauseReason fromDbValue(String value) {
        for (BackfillPauseReason reason : values()) {
            if (reason.dbValue().equalsIgnoreCase(value)) {
                return reason;
            }
        }
        throw new UnknownEnumValueException(Arrays.stream(values()).map(BackfillPauseReason::dbValue).toList());
    }
}
