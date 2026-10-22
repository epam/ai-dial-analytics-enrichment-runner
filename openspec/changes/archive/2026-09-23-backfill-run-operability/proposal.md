## Why

A backfill run is the most expensive object the runner manages — it re-drives history at real model cost —
and it is the one an operator can least see into or safely steer. The gaps below share one object
(`run_history`), one class pair (`BackfillService` / `BackfillExecutor`) and one migration, and several only
show up when they are read together. So they ship as one change, not as seven that each re-copy the same
`service-runtime` requirement and fight over migration numbers.

### A. The lifecycle can corrupt a run, wedge it, or drain it twice

1. **`pause` and `cancel` are unguarded.** `resume` refuses anything that is not `paused`; its siblings refuse
   nothing. `pause` on a `done` run writes `paused` while `finished_at` stays set, and `resume` then re-arms it
   from its end cursor — for an unbounded run, everything ingested since, at full model cost — and `markDone`
   rewrites `finished_at`. Re-cancelling moves `finished_at` forward too. A polled console is stale by
   construction; the server must refuse.

2. **Nothing stops a batch once it has started, and nothing stops two batches running for one run.**
   `BackfillExecutor.run` reads the run once and then drains up to `SCAN_MAX_PAGES_PER_FIRE` (50) pages. A
   cancel during that window does not stop it: it keeps enqueuing paid chunks, then `markDone` writes `done`
   and a new `finished_at` over the cancel. Worse, the batch instance id hashes the cursor the batch *started*
   from, while each page commits separately. So a batch that fails on page 2 leaves a retry under the old id
   while the row's cursor has moved on; `resume` computes a different id, and two executions drain the run
   concurrently, double-spending and double-counting `rows_done`. Pause-then-resume during a draining batch
   does the same. And a resume that lands while a retry is *executing* is swallowed by `scheduleIfNotExists`;
   the retry then exits on the `paused` it read, leaving the run `running` with no chain. The start conflict
   is a check-then-insert with no constraint behind it, and `resume` does not check it at all, so a pipeline
   can end up with two `running` runs.

3. **A pipeline leaving the cache wedges its run forever — and so does every restart.** The absent-pipeline
   branch logs "resume after re-enabling" and returns without changing state. `BackfillTask` is a
   self-chaining `OneTimeTask`, so the chain ends while the row says `running`: un-resumable, blocking every
   future backfill for the pipeline with `409`. It is reached by the most intuitive way to stop a runaway
   backfill: disabling the pipeline in ADAS. And because the executor never calls
   `PipelineCache.requirePopulated`, a batch due in the seconds before the first sync after a boot reads the
   empty cache as "absent" and wedges the same way. The work-item lanes already hold on a cold cache.

4. **A declaration edited mid-run is adopted silently, with a gap.** The executor re-reads the pipeline every
   batch, so an edit switches declarations part-way through the window. Chunks already enqueued under the old
   generation are then dropped by `WorkerService` as stale, while the run's cursor has already moved past the
   pages they came from.

5. **A batch that can never succeed retries silently forever.** `BackfillTask` uses `onFailureRetryLater()`:
   a flat five minutes, no attempt limit, no hook. A page missing the `grain_key`, an unaccounted page, or an
   ADAS `422` fails identically on every retry while the run reads `running`, `rows_done` freezes, no counter
   moves, and the pipeline's conflict stays held.

### B. A run cannot explain itself

6. **`paused` is one word for seven conditions** that need different responses. A spend pause's condition
   lifts at 00:00 UTC; a breaker pause's when its window elapses; an operator's when a human decides; and a
   **cursor stall** needs investigation, because resuming it blindly re-enters the same page. In every case
   the run stays paused until someone resumes it. The executor writes the distinction to a log line and
   discards it. The pipeline-pause registry does record a reason, but it is in-memory and a restart clears it
   (persisting it is deferred).

7. **`rows_total` promises a progress bar nothing can fill.** It is in the V2 column, the entity and the DTO,
   and written nowhere. It cannot be: ADAS exposes no cardinality, the cursor is opaque, the window's upper
   bound moves, and `rows_done` counts processed (for Class-1, *written*) rows under sampling, filtering and
   skips.

### C. A run and its failures cannot be found

8. **Runs are not enumerable, and their dead letters are not attributable.** The only read is
   `GET /v1/backfills/{runId}`, and the only place a run id is handed out is the `202` of the start. Lose it
   and the run is reachable only by SQL — the run blocking a `409`, a wedged run, any number of abandoned
   paused runs. `dlq_item` has no run reference, so a large replay's historical dead letters bury the live
   path's current ones, and a requeue cannot target one run.

## What Changes

**Lifecycle (A)**

- **The transition table lives on `RunState`** (`canPause()`, `canResume()`, `canCancel()`): `pause` admits
  `running`; `resume` `paused`; `cancel` `running` or `paused`. Anything else is `409 backfill_conflict`.
  `finished_at` is written once.
- **Single-writer page commits.** Every page commit advances the run with
  `UPDATE … WHERE id = ? AND state = 'running' AND cursor IS NOT DISTINCT FROM :cursorBefore`. Zero rows
  means the run was cancelled, paused or advanced by someone else. The page transaction then rolls back,
  undoing its enqueued chunks and dead letters, and the batch stops without chaining or recording a failure.
  This one check stops a cancelled run from spending at the next page, and makes duplicate, retried and
  crash-revived executions harmless.
- **Every `run_history` transition is a conditional single-statement update.** Operator verbs return `409` on
  zero rows; the executor's `pauseIfRunning(reason)` and `markDoneIfRunning` yield quietly. Nothing
  reloads-and-saves the whole row, since that writes back a stale cursor.
- **Resume always schedules a fresh instance** (`backfill-run#<runId>#<cursorHash>#r<epochMillis>`), so it
  cannot be swallowed by an execution already holding the old id. Any older execution is stopped at its first
  commit by the check above.
- **Start and resume refuse the gates up front.** With a populated cache: an absent pipeline is `404`; a
  paused or over-budget pipeline is `409 backfill_conflict` naming the condition; another `running` run for
  the pipeline is `409` naming its id. A cold cache is `503 pipeline_cache_cold`. A partial unique index
  enforces one `running` run per pipeline.
- **Cold cache holds the batch; absent pipeline pauses it.** The executor calls `requirePopulated` first;
  the held exception goes through `WorkHold`. Only a miss in a populated cache means absent (disabled,
  deleted, *or refused as unexecutable*), and that pauses with `pipeline_absent`. No auto-resume.
- **A run pins its pipeline generation.** It is recorded at start; a mismatch pauses with `pipeline_changed`,
  and resume adopts the current generation. The pause stops the gap from growing and puts the choice in front
  of the operator: resume, or cancel and re-run the window.
- **A real failure handler**, `BackfillBatchFailureHandler`, replaces `onFailureRetryLater()`. It holds via
  `WorkHold` first. Otherwise it records a *classified* `last_error` (curated text for the runner's own
  failures, HTTP status and ADAS error code for upstream rejections, the exception type otherwise; capped at
  500 characters; never a raw message) on a still-running run, increments
  `runner.backfill.batch.failures{pipeline}`, and reschedules on `RetryBackoff.withJitter` off
  `WORKER_BACKOFF_BASE`. It removes the execution if the run is no longer running. There is no attempt budget
  and no terminal `failed` state; auto-pause after N failures waits until the counter says what N is.

**Self-description (B)**

- **`paused_reason` / `paused_at`** from a closed enum `BackfillPauseReason`: `operator`,
  `pipeline_paused_operator`, `pipeline_paused_breaker`, `spend_budget_exhausted`, `cursor_stalled`,
  `pipeline_absent`, `pipeline_changed`. Present only while paused; cleared by resume and cancel. The reason
  records what *paused* the run, not what holds it now.
- **`last_error` / `last_error_at`**, cleared on the next successful page commit and kept on a cancelled run.
- **`rows_total` is removed** from the DTO and the entity; its column drop follows next release. The spec
  records why a total is not obtainable.

**Findability (C)**

- **`GET /v1/backfills?pipeline_name=&state=&paused_reason=&limit=`**, newest first, `MAX_LIMIT = 500`,
  envelope `{"runs": [BackfillRunDto…]}`. Values are accepted lowercase, case-insensitively, through lenient
  MVC converters (Spring's default enum binding is case-sensitive, and Jackson's setting does not apply to
  query parameters). Unknown values are `400`.
- **`dlq_item.run_id`** is taken from the backfill commit that holds it, or parsed from the instance id at
  every worker and write-phase dead-letter site. A requeue keeps it by carrying the run in the requeue
  instance id. `GET /v1/dlq` gains `run_id` and `lane=live|backfill`; the requeue selects by pipeline, run or
  both. `run_id` is overwritten by the upsert and is never part of its identity. Group-sweep dead letters
  carry no run.

The run body after this change:

```json
{
  "id": "0e6b…", "pipeline_name": "session-insights-live", "kind": "backfill",
  "state": "paused",
  "paused_reason": "cursor_stalled", "paused_at": "2026-09-23T11:04:17Z",
  "rows_done": 41200,
  "last_error": "ADAS 422 pipeline_validation_failed", "last_error_at": "2026-09-23T10:58:40Z",
  "window_from": "2026-07-01T00:00:00Z", "window_to": "2026-08-01T00:00:00Z",
  "started_at": "2026-09-23T10:51:02Z"
}
```

Every new property is absent under `NON_NULL` when it does not apply. `pipeline_generation` is internal and
not exposed, like the cursor.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `backfill`: the transition table, single-writer commits, resume and start gates; pause reasons; absent
  pipeline and cold cache; declaration change mid-run; batch-failure recording with no terminal state; the
  run listing; rows processed and no total.
- `resilience-and-dlq`: a dead letter records the backfill run, which survives a requeue; the listing and
  the requeue narrow by run and lane.
- `observability`, `runner-ops`: `runner.backfill.batch.failures`.
- `service-runtime`: the `run_history` and `dlq_item` columns, constraints and index.

## Impact

- **New code**: `backfill/BackfillPauseReason`, `backfill/BackfillBatchFailureHandler`,
  `backfill/RunSupersededException`, `web/dto/backfill/BackfillRunListResponse`, a shared
  `exception/ErrorMessages` (the sanitizer extracted from `ErrorView`, plus the classifier), lenient enum
  converters registered via a `WebMvcConfigurer`, an exception for `pipeline_cache_cold` (`503`) and one for
  contradictory DLQ filters (`400`), both with `DefaultExceptionHandler` entries.
- **Modified code**: `backfill/{RunState, BackfillService, BackfillExecutor, BackfillTask,
  BackfillPageCommitter}`, `pause/PipelinePauseRegistry` (a single-key `current(name)` read),
  `persistence/{RunHistoryEntity, RunHistoryRepository, DlqItemEntity, DlqItemWriter, DlqItemRepository}`,
  `scan/DeterministicId`, `worker/{WorkerService, ChunkFailureHandler}`,
  `worker/upsert/{UpsertService, UpsertFailureHandler}`, `dlq/DlqRequeueService`,
  `web/controller/{BackfillController, DlqController}`, `web/dto/backfill/BackfillRunDto`,
  `web/dto/dlq/DlqItemDto`, `web/mapper/BackfillRunMapper`, `web/ErrorView`,
  `observability/RunnerMetrics`.
- **Migration**: `V10__backfill_run_operability.sql` (the last applied is V9):

  ```sql
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
  ```

  Rollback-safe: every new column is nullable, the old image never writes `paused_reason`, so neither
  pause-reason `CHECK` can fire against it, and the unique index only turns an old-image start race into an
  error. The unique index fails to build if a pipeline already has two `running` runs; the header says so and
  gives the query to find them (cancel one, re-run). **Deferred to the next release's migration**, once no
  deployable image predates V10: `DROP COLUMN rows_total` (the old image still selects it), and
  `CHECK (paused_reason IS NULL OR state = 'paused')` (the old image's resume and cancel cannot clear a column
  they do not map), preceded by a cleanup `UPDATE` of any reason left stale by such a rollback. Nothing
  existing is backfilled — a past pause's reason, a past batch error or a past dead letter's run cannot be
  recovered.
- **API**: additive, with deliberate restrictions. `pause`/`cancel` on an inadmissible state, and `start`/
  `resume` into a paused or over-budget pipeline, now return `409` (previously `200` / `202` followed by an
  immediate pause). `start` before the first sync returns `503` instead of `404`. A run whose pipeline
  vanishes reports `paused / pipeline_absent` instead of `running` forever. No machine consumer exists yet.
- **Behaviour**: a failed batch retries on the jittered backoff (early retries faster, sustained ones slower,
  capped at 1h) instead of a flat five minutes. A run-scoped requeue counts toward the pipeline's live
  backpressure like any requeue.
- **Authorization**: unchanged; every `/v1` endpoint, reads included, still requires `FULL_ADMIN`.
- **ADAS contract**: none. **Configuration**: none.
- **Docs**: `docs/e2e-runbook.md` §6 (the Backfill and DLQ bullets: listing, run/lane filters, run-scoped
  requeue), §10 (verbs' permitted states, re-cancel `409`, new fields in the `jq` line, failure-table rows for
  a disabled pipeline, an edited pipeline, a failing batch, and a paused pipeline now reported by
  `paused_reason`), §11 "Work that is held" (a held backfill batch). The fold-appendix capture query that
  names `rows_total` is a pre-V8 capture and is **not** edited; it gets a note like its V8/V9 notes.
  `docs/release-0.2-upgrade.md` and the fold runbook's "confirm no backfill is RUNNING" point at
  `GET /v1/backfills?state=running`.
- **Not here**: repairing runs already wedged at `running` or corrupted by pause-after-done (cancel works on
  them); detecting a lane change (row ↔ Class-1) across a `pipeline_changed` resume beyond the generation
  check; persisting pipeline pauses, which today live in memory and are cleared by a restart; auto-pause after N failures; a chain-liveness detector for
  `running` runs with no execution; requeue at backfill priority; `COALESCE`-ing a group payload that a
  mark-time dead letter overwrites with null (pre-existing); a DLQ count on the run body; paging; an ADAS count
  endpoint.
