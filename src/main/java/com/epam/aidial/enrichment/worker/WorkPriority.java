package com.epam.aidial.enrichment.worker;

/**
 * The queue priorities the runner schedules work at. db-scheduler orders due executions
 * {@code priority desc, execution_time asc} (the scheduler is built with {@code enablePriority()}), so
 * these numbers are the only thing that decides which of two due items a free thread takes.
 *
 * <p>One holder because three tasks and five call sites share them. They used to live on
 * {@link EnrichChunkTask}, which is where the defect came from: the write-back borrowed
 * {@code PRIORITY_LIVE} from the task next to it and thereby declared itself to be in the same lane as the
 * compute that produces it.
 *
 * <p><strong>{@link #WRITE_BACK} outranks every compute lane — finish before you start.</strong> Within a
 * lane the tiebreak is age, and a write item is younger than the compute that created it and younger than
 * every compute enqueued alongside it, so at equal priority it can never reach the front while compute
 * remains. That is not a race the write occasionally loses; it is an ordering it cannot win, and a trigger
 * that replenishes the compute backlog faster than the pool drains it (a group readiness sweep, say) turns
 * "cannot win" into "never runs" — a pipeline that calls the model, is charged for every call, and writes
 * nothing.
 *
 * <p>The ranking is safe in the other direction because it is not symmetric: a write-back is bounded work
 * (one request over rows already in hand, no model call), it is idempotent, and the set of pending writes
 * is bounded by the set of computes that have already completed. Write-back generates no write-back, so it
 * cannot starve what sits below it.
 *
 * <p>The backfill's write-back shares the live one's lane deliberately. Its <em>compute</em> is ranked below
 * live compute on purpose; its result, once paid for, is worth what a live result is worth and carries the
 * same exposure while it sits queued. Splitting the write lane in two would reproduce the defect above one
 * level down.
 */
public final class WorkPriority {

    /** A computed result waiting to be written. Above every compute lane; see the class javadoc. */
    public static final int WRITE_BACK = 20;

    /** Live compute: a scanned chunk, a swept group, an operator's re-evaluation. */
    public static final int LIVE = 10;

    /** Backfill compute, kept below live so a replay cannot starve the current window. */
    public static final int BACKFILL = 1;

    private WorkPriority() {
    }
}
