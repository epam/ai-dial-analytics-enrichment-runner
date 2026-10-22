-- Make a backfill run self-describing, single-writer and findable, and attribute a dead letter to the run
-- that produced it.
--
-- run_history.paused_reason / paused_at — WHY A RUN WAS PAUSED, AND SINCE WHEN. `paused` is one word for
-- seven conditions that call for different responses (a spend pause's condition lifts at 00:00 UTC, a
-- breaker pause's when its window elapses, a cursor stall's only when somebody fixes something). The
-- runner computed the distinction at pause time and wrote it only to a log line; the in-memory pipeline-pause
-- registry does record a reason, but a restart clears it. Stored here, the reason survives both.
--
--   * run_history_paused_reason_check keeps the column to the closed set BackfillPauseReason spells, for the
--     reason dlq_item.stage is constrained (V4): an unrecognised value is a database error, not a stored typo.
--     A new reason therefore needs a migration, exactly as a new state or stage does.
--   * run_history_paused_reason_at_check requires the reason and its instant to be present together or not at
--     all, so a reader never meets one without the other.
--   * The rule that a reason appears ONLY on a paused run — CHECK (paused_reason IS NULL OR state = 'paused')
--     — is DELIBERATELY DEFERRED to the next release's migration. The previous image's resume and cancel do
--     not map these columns and so cannot clear them; on a rollback that constraint would turn both verbs into
--     errors. In this release the runner's own transitions enforce it. The deferred migration first clears any
--     reason left stale by such a rollback window.
--
-- run_history.last_error / last_error_at — WHY THE LAST BATCH FAILED. The batch retries indefinitely by
-- design, and before this nothing on the run changed while it did: a stuck run and a slow one were
-- indistinguishable without a log search. The value is a CLASSIFICATION (curated text, or an HTTP status and
-- ADAS error code, or an exception type), never a raw exception message.
--
-- run_history.pipeline_generation — THE DECLARATION A RUN IS DRAINING UNDER, so an edit mid-run pauses the
-- run instead of being adopted silently part-way through its window. Existing rows get NULL and adopt the
-- pipeline's current generation on their next batch rather than pausing spuriously.
--
-- run_history_one_running_per_pipeline_uk — AT MOST ONE RUNNING RUN PER PIPELINE. The start conflict was a
-- check-then-insert under READ COMMITTED, which two concurrent starts both pass, and resume did not check it
-- at all. This index makes the invariant hold under concurrency. It FAILS TO BUILD if a pipeline already has
-- two running runs; find them with
--
--     SELECT pipeline_name, array_agg(id) FROM run_history WHERE state = 'running'
--     GROUP BY pipeline_name HAVING count(*) > 1;
--
-- then cancel all but one (UPDATE run_history SET state = 'cancelled', finished_at = now() WHERE id = ...)
-- and re-run the migration. Failing loudly is better than an invariant that silently does not hold.
--
-- dlq_item.run_id — THE BACKFILL RUN THAT PRODUCED A DEAD LETTER; NULL MEANS THE LIVE PATH. A replay of a
-- large window buries the live path's current failures beneath its own historical ones in a listing ordered by
-- recency. The index is PARTIAL (WHERE run_id IS NOT NULL) because the live majority must not pay write
-- throughput for a query only the run-scoped views issue. There is NO FOREIGN KEY to run_history: the two
-- tables' retention is independent (dlq_item is swept at 30 days; run_history is kept, and has been truncated
-- outright before), and a deleted run must neither cascade into nor block the deletion of the record of what
-- failed. run_id is NOT part of dlq_item_row_identity_uk; see DlqItemWriter.
--
-- NOTHING IS BACKFILLED. A past pause's reason, a past batch's error and a past dead letter's run were never
-- recorded and cannot be recovered; NULL is the truth about those rows, and inventing a value would be worse.
--
-- ALSO DEFERRED to the next release: DROP COLUMN rows_total. It was never written and is no longer mapped by
-- this image, but the previous image's entity still selects it, so dropping it now would break a rollback.
--
-- Rollback-safe: every new column is nullable, the old image never writes paused_reason (so neither
-- pause-reason CHECK can fire against it), and the unique index only turns an old-image start race into an
-- error.
--
-- This header is checksummed by Flyway. Do not edit it once the migration has been applied anywhere.

ALTER TABLE run_history ADD COLUMN paused_reason       TEXT;
ALTER TABLE run_history ADD COLUMN paused_at           TIMESTAMP WITH TIME ZONE;
ALTER TABLE run_history ADD COLUMN last_error          TEXT;
ALTER TABLE run_history ADD COLUMN last_error_at       TIMESTAMP WITH TIME ZONE;
ALTER TABLE run_history ADD COLUMN pipeline_generation BIGINT;
ALTER TABLE run_history ADD CONSTRAINT run_history_paused_reason_check
    CHECK (paused_reason IS NULL OR paused_reason IN (
        'operator', 'pipeline_paused_operator', 'pipeline_paused_breaker',
        'spend_budget_exhausted', 'cursor_stalled', 'pipeline_absent', 'pipeline_changed'));
ALTER TABLE run_history ADD CONSTRAINT run_history_paused_reason_at_check
    CHECK ((paused_reason IS NULL) = (paused_at IS NULL));
CREATE UNIQUE INDEX run_history_one_running_per_pipeline_uk
    ON run_history (pipeline_name) WHERE state = 'running';

ALTER TABLE dlq_item ADD COLUMN run_id TEXT;
CREATE INDEX dlq_item_run_id_created_at_idx ON dlq_item (run_id, created_at DESC)
    WHERE run_id IS NOT NULL;
