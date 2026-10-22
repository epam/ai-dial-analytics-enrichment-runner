-- Re-key the runner's runtime state from the ADAS rule UUID to the pipeline name — and DISCARD that state
-- rather than carrying its values across.
--
-- ADAS has folded its `rule` registry into its `pipeline` registry as the ENRICH kind, and with it the
-- identity of the thing this runner tracks: a UUID minted by ADAS becomes the pipeline's own name. All five
-- runner-state tables are keyed on it, so this migration renames the column everywhere it appears — plus the
-- one table whose name carried the old noun — and then empties them.
--
-- THE VALUES ARE NOT REMAPPED. The id -> name mapping lives in ADAS's catalog, which Flyway here cannot read
-- (separate databases), so preserving the state means an operator step wedged between the two deploys:
-- generate UPDATEs from ADAS, apply them here, verify row counts, commit — inside a window whose ordering is
-- load-bearing. Discarding needs no window, no ordering between the two deploys and no hand-written SQL. The
-- cost of that choice is set out below rather than left to be re-discovered from behaviour.
--
-- WHAT DISCARDING COSTS
--
-- The cursor is the whole of it. A missing `pipeline_cursor` row means "start from the beginning", and
-- nothing on the scan path skips a source row that already carries an enrichment value — "already enriched"
-- is not a predicate anywhere. So every enabled pipeline re-derives its target from the start of its source,
-- once.
--
--   * A Class-1 (`type=sql`) lane re-derives inside ClickHouse: ADAS issues one INSERT … SELECT per window
--     and this runner makes no model call at all. Idempotent (the target replaces by grain) and bounded —
--     `batch_scan_limit` rows per window, `SCAN_MAX_PAGES_PER_FIRE` windows per fire. At the time of writing
--     that is 297 source rows for `turn-feedback-live`, and ~2.6M for `usage-client-identity-live`: 130
--     windows, three cadence ticks. Compute, not spend. A window that fails leaves the cursor untouched and
--     is re-fired on cadence, so the failure mode is a slow re-derivation, never a half-written one.
--   * A group LLM lane re-evaluates EVERY group, because `group_state` held the `computed_version` that said
--     which ones were done. Each of those is a real chat-completion call. Both such lanes
--     (`conversation-insights-live`, `session-insights-live`) are disabled at the time of writing, so this
--     migration spends nothing itself; the bill lands on whoever next enables one. Measured on dev:
--     ~24.6k prompt tokens and ~$0.0025 per group, so ~7.2k sessions is ~$18 and about an hour.
--
-- WHAT DISCARDING DOES NOT COST — PROVIDED THE CUT-OVER LEAVES `enabled` ALONE
--
-- The position dependent rollups clamp against is not in this database. It is ADAS's
-- `materialized_through_*`, carried across the fold by its V36 and advanced monotonically in SQL, so a
-- re-derivation from zero cannot lower it and no rollup is held back while one runs. That mark is qualified
-- by `generation`, however, and toggling `enabled` bumps `generation`: ADAS then discards the mark and
-- re-records it at the end of the FIRST REPLAYED WINDOW — near the start of the source — and every rollup
-- reading that enrichment stalls until the re-derivation catches back up.
--
-- So the cut-over quiesces by STOPPING THIS RUNNER, never by disabling pipelines in ADAS, and restores
-- nothing afterwards: V36 carries each pipeline's `enabled` value across unchanged, and the sync feed hands
-- the enabled ones back within one poll.
--
-- THE FIVE TABLES AND THE QUEUE GO TOGETHER; a partial discard is worse than either extreme
--
--   * The scan cursor advances at chunk ENQUEUE, so a queued work item stands for rows the cursor has
--     already passed. Dropping the cursor while keeping the queue evaluates those rows twice; keeping the
--     cursor while dropping the queue loses them permanently. Dropping both is the consistent state:
--     nothing has been seen, nothing is queued.
--   * `GroupWorkerService.deadLetter` stamps the group computed and then writes the DLQ row, so the
--     readiness sweep never retries a dead-lettered group on its own and that row is its only route back.
--     Dropping `dlq_item` while keeping `group_state` would strand every one of them — computed, unlabelled
--     and invisible, with no later member to re-dirty a closed session. Dropping both makes them dirty again
--     and they are retried like anything else.
--
-- WHAT A RESCAN DOES NOT REBUILD, so capture it before the window if it is wanted: `dlq_item` (why each
-- poison item failed), `run_history` (a paused or running backfill's own cursor lives nowhere else, so such
-- a run cannot be resumed afterwards — cancel or finish it first) and `spend_counter` (today's per-day
-- counters; inert while SPEND_PAUSE_ENABLED is false, which is the default). See docs/e2e-runbook.md.

-- ---------------------------------------------------------------------------------------------------
-- Part A — rename the tables, columns and indexes onto the new identity.
-- ---------------------------------------------------------------------------------------------------

ALTER TABLE rule_cursor RENAME COLUMN rule_id TO pipeline_name;
ALTER TABLE rule_cursor RENAME TO pipeline_cursor;
ALTER INDEX rule_cursor_pkey RENAME TO pipeline_cursor_pkey;

ALTER TABLE run_history RENAME COLUMN rule_id TO pipeline_name;
ALTER INDEX run_history_rule_id_idx RENAME TO run_history_pipeline_name_idx;

ALTER TABLE dlq_item RENAME COLUMN rule_id TO pipeline_name;
ALTER INDEX dlq_item_rule_id_created_at_idx RENAME TO dlq_item_pipeline_name_created_at_idx;
-- dlq_item_row_identity_uk keeps its name: it describes the identity it enforces, not the columns.

ALTER TABLE spend_counter RENAME COLUMN rule_id TO pipeline_name;
ALTER INDEX spend_counter_rule_id_day_idx RENAME TO spend_counter_pipeline_name_day_idx;

ALTER TABLE group_state RENAME COLUMN rule_id TO pipeline_name;
-- group_state_dirty_idx and group_state_activity_idx keep their names: they describe the purpose.

-- ---------------------------------------------------------------------------------------------------
-- Part B — discard the state the renamed columns now key.
-- ---------------------------------------------------------------------------------------------------

-- Counted and announced before it is thrown away. A destructive step that leaves no trace is one nobody can
-- reconcile afterwards against what the pre-window snapshot held, and these counts are the only record that
-- survives into the deploy log.
DO $$
DECLARE
    cursor_rows BIGINT;
    group_rows  BIGINT;
    dlq_rows    BIGINT;
    run_rows    BIGINT;
    spend_rows  BIGINT;
BEGIN
    SELECT count(*) INTO cursor_rows FROM pipeline_cursor;
    SELECT count(*) INTO group_rows  FROM group_state;
    SELECT count(*) INTO dlq_rows    FROM dlq_item;
    SELECT count(*) INTO run_rows    FROM run_history;
    SELECT count(*) INTO spend_rows  FROM spend_counter;

    RAISE NOTICE 'V8 discarding runner state keyed on the old identity: % cursor(s), % group(s), '
                 '% dead-letter item(s), % run history row(s), % spend counter row(s). Every enabled '
                 'pipeline re-derives its target from the start of its source once.',
                 cursor_rows, group_rows, dlq_rows, run_rows, spend_rows;

    IF run_rows > 0 THEN
        -- Not an abort: an unresumable backfill is a consequence of the choice this migration implements,
        -- not evidence that something went wrong. Named separately because it is the one discard a rescan
        -- does not make good on its own.
        RAISE NOTICE 'V8: % run history row(s) discarded; any paused or running backfill among them cannot '
                     'be resumed and must be re-requested.', run_rows;
    END IF;
END
$$;

TRUNCATE TABLE pipeline_cursor, group_state, dlq_item, run_history, spend_counter;

-- ---------------------------------------------------------------------------------------------------
-- Part C — clear the queue.
-- ---------------------------------------------------------------------------------------------------

-- Every row db-scheduler holds for this runner goes, recurring instances and work items alike, under both
-- spellings of the scan task ('rule-scan' before the fold, 'pipeline-scan' after).
--
-- A work item cannot survive the identity change in any case: `DeterministicId` puts the identity in both
-- the instance-id prefix and the hashed basis, so a queued chunk, group or upsert addresses a pipeline that
-- no longer answers to that name. Its payload also carries the old field names. Earlier this migration
-- ASSERTED such rows were absent and failed the deploy otherwise, because the designed cut-over drained the
-- queue by disabling every rule first. That quiesce is gone with the remap, so the assertion would now abort
-- startup over leftovers from before a lane was switched off — and there is nothing left for a surviving
-- item to be consistent with, since the cursor that would have skipped its rows has just been truncated.
--
-- Recurring instances are recreated by the next sync's scheduleIfNotExists, so deleting them is free.
DO $$
DECLARE
    cleared BIGINT;
BEGIN
    DELETE FROM scheduled_tasks
     WHERE task_name IN ('rule-scan', 'pipeline-scan', 'class1-materialize',
                         'enrich-chunk', 'enrich-group', 'upsert-rows', 'backfill-run');
    GET DIAGNOSTICS cleared = ROW_COUNT;

    RAISE NOTICE 'V8 cleared % scheduled task row(s); the next pipeline sync recreates the recurring '
                 'instances of every enabled pipeline.', cleared;
END
$$;
