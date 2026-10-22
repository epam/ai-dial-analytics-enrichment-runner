## Context

`run_history` is the durable record of a backfill, and `BackfillService` / `BackfillExecutor` /
`BackfillPageCommitter` are the only code that writes it. This change hardens the run's state machine, makes
the run self-describing, and makes it findable. It is one change rather than seven because the
parts share one table, one class pair and one migration, and read together, their interactions are where the
bugs live:

- guarding `pause` turns the executor's pause-after-operator-cancel race into a `409` inside a batch, which
  the new failure handler would record as a failure;
- pausing on an absent pipeline fires falsely on every restart unless the cold-cache hold intercepts first;
- a batch retried after a mid-batch failure keeps an instance id derived from a cursor the row no longer
  holds, so "resume reschedules the pending retry" misses it and starts a second chain;
- a pause-reason `CHECK` tied to `state` breaks the previous image's resume and cancel on rollback.

The decisions below are the ones that close those.

## Goals / Non-Goals

**Goals:**

- No sequence of operator calls, batch outcomes, retries or restarts produces a row whose state and timestamps
  contradict each other, a completion instant written twice, a cursor moved backwards, or two executions
  advancing one run.
- A run is `running` without a live chain only transiently — during a held or retried batch, or until
  dead-execution revival after a crash.
- An operator reading a run learns why it is paused, whether its last batch failed and why, and what it
  covered, without a log search or SQL.
- Every run is findable without its id, and its dead letters — requeued ones included — are separable from
  the live path's.

**Non-Goals:**

- A terminal `failed` state, auto-pause after N failures, auto-resume of any kind.
- Persisting pipeline pauses (deferred; they live in memory and a restart clears them).
- Repairing rows already wedged or corrupted.
- A progress fraction, now or via ADAS.

## Decisions

### Lifecycle

**1. The transition table belongs on `RunState`.** `RunState` already owns the vocabulary (`dbValue()`);
`canPause()` / `canResume()` / `canCancel()` sit beside it. `cancel` keeps the wider domain (`running` or
`paused`) — abandoning a paused run is the ordinary way one ends. Rejected: guarding only in the console (a
state machine enforced by one client is enforced by nothing); `pause` on a terminal run as a silent `200`
(indistinguishable from a real pause); `cancel` idempotent by rewrite (a lost response is recovered by a
`GET`; a rewritten `finished_at` is not recoverable).

**2. A compare-and-set on the cursor is the single-writer mechanism.** Every page commit begins (taking the
row lock before any enqueue, so a losing execution never reaches a duplicate chunk insert) with
`UPDATE run_history SET cursor = :next, rows_done = rows_done + :n, last_error = NULL, last_error_at = NULL
WHERE id = :id AND state = 'running' AND cursor IS NOT DISTINCT FROM :cursorBefore
AND pipeline_generation IS NOT DISTINCT FROM :generation`, where `:next` is
already passed through `CursorGuard.advance`, so a blank `next_cursor` still keeps the run's position. On zero
rows the committer throws `RunSupersededException` inside the page transaction, which rolls back that page's
enqueued chunks, marks and dead letters. The executor catches it and returns without chaining, and the
failure handler never sees it.

This one check fixes a family of races that each looked like it needed its own mechanism:

- A **cancel or pause mid-batch** stops spend at the next page, rather than after up to 49 more.
- **Two executions for one run** — a pending retry and a resume's fresh batch, a batch that outlived a
  pause-then-resume, a crash-revived batch — cannot both advance. Whichever commits second finds the cursor
  moved and stops. The cost of an extra execution is at most one wasted ADAS read.
- The **executor's own terminal writes** are conditional on the same `state = 'running'`
  (`pauseIfRunning`, `markDoneIfRunning`, each a single `UPDATE`), and yield quietly on zero rows.
- The **generation** is part of the check (and of `pauseIfRunning`). Pause and resume do not move the cursor,
  so a batch that outlived a pause, an edit and a resume would otherwise still pass the cursor check, commit a
  page keyed to the old generation, and then pause the resumed run with `pipeline_changed`.

Known residual: when a page does not move the stored cursor (a stall, or an empty final page with a blank
`next_cursor`), two concurrent executions can both pass the check; the stall doubles that page's `rows_done`
contribution and chunk ids dedupe the work. Closing it needs a monotonic version column, which is not worth a
column for a counter that is already not a coverage measure (see `rows_total`).

Rejected: a row lock held across the batch (a batch spans many transactions and ADAS calls); finding and
rescheduling the pending retry at resume. The retry's id is `hash(cursor at chain time)`, not the row's
cursor, so it has to be found by data rather than recomputed. Even then, an execution that is currently picked
cannot be rescheduled — db-scheduler throws `TaskInstanceCurrentlyExecutingException` — so this approach still
needs a fallback for the case that wedges the run.

**3. No `run_history` write reloads and saves the whole entity.** `RunHistoryEntity` has no `@DynamicUpdate`
and no `@Version`, so a save writes every column, and an operator verb that loaded the row before a page
commit would write the old cursor back. Operator verbs are conditional `UPDATE`s too (`… WHERE state IN
(admissible)`); zero rows → reload to name the current state in the `409`. All of these are `@Modifying`
repository methods and run inside `@Transactional` service methods.

**4. Resume schedules a fresh instance, unconditionally.** `backfill-run#<runId>#<cursorHash>#r<epochMillis>`,
where the suffix is the resume's own instant — no column needed, and two resumes of one run cannot share a
millisecond because each must first find the run `paused`. A resume can then never be swallowed by
`scheduleIfNotExists` against an execution that is currently running and about to exit on `paused` — the one
sequence that wedged the run. Continuation batches keep the existing `backfill-run#<runId>#<cursorHash>`
shape, so a re-chained batch still dedups against itself.

**5. Start and resume refuse what the first batch would pause on.** The executor's entry gates move forward
to the API, in order: cold cache → `503 pipeline_cache_cold`; absent → `404`; pipeline paused → `409` naming
the origin; over budget → `409` naming the 00:00 UTC reset; another `running` run → `409` naming its id. The
argument is the one the absent-pipeline case already makes: a verb that returns success and is undone a
second later is a surface that appears to work and does not. `cursor_stalled` cannot be pre-checked, and
resuming one is allowed. A partial unique index backs the last check, because a check-then-insert under
`@Transactional` at READ COMMITTED does not stop two concurrent starts, and `resume` never checked at all.

A cold cache gets its own `503` rather than `404` or `500`. `start` today reads `get() == null` and would tell
an operator in the first 10 seconds after a boot that their pipeline does not exist. `PipelineCacheColdException`
has no web handler, so `requirePopulated` on its own would surface as a `500`.

**6. Cold cache is held in the executor; absent pipeline pauses.** The executor calls `requirePopulated`
first. `PipelineCacheColdException` is a `HeldWorkException`, so the handler's `WorkHold.holdIfHeld`
reschedules in 30 s without counting. Only a miss in a populated cache is a fact about the feed — the
pipeline was disabled or deleted in ADAS, *or refused by the runner as unexecutable*, which also keeps it out
of the cache — and that pauses with `pipeline_absent`. Pause rather than cancel: cancel destroys the cursor's
usefulness. Pause rather than an expiring pause: absence is a fact, not an inference. **No auto-resume**:
absence is almost always an operator's decision in ADAS, and the returning declaration may differ.

**7. The run pins its pipeline generation.** `pipeline_generation` is set at start and on resume. A batch
that sees a different cached generation pauses with `pipeline_changed` before reading a page. The pause does
not undo the gap, but it stops it growing: chunks enqueued under the old generation are dropped as stale by
`WorkerService`, and the cursor has already passed their pages. Resume adopts the current generation, because
most edits (`rate_rpm`, cadence) do not change what a row produces and the operator knows which kind this
was. The alternative — cancel, then start a new run bounded to the window — is theirs to take. A run from
before V10 has no generation, so it adopts the current one on its next batch rather than pausing
spuriously. Not detected: a lane change (row ↔ Class-1) where the cursor belongs to a different read; that is
named in the proposal as not handled.

**8. A real batch failure handler, with no budget and no terminal state.** Every rule for entering `failed`
is wrong. On the first error, it kills runs on a Postgres blip or an ADAS `503`. After N errors, it destroys
the cursor's usefulness exactly when the operator wants to fix the cause and continue. On a permanent
classification, it rests on `RetryableStatus`, which governs work items, is knowingly approximate,
and does not cover the accounting refusals. An attempt budget that stops the chain without moving state is
the wedge decision 6 removes.

The handler's order:

1. `WorkHold.holdIfHeld`.
2. Load the run for its pipeline name.
3. `recordBatchFailure`, conditional on `state = 'running'`. Zero rows (the run is no longer running) →
   `operations.remove()` and stop.
4. Increment the counter.
5. Reschedule on `RetryBackoff.withJitter(WORKER_BACKOFF_BASE, consecutiveFailures)`.

Steps 2–4 are in a try/catch so a failure to record still reschedules, as `ChunkFailureHandler` does. A
failure to reschedule falls back to db-scheduler's dead-execution revival. A resume restarts the backoff
ladder, since the fresh instance starts at zero failures.

**9. `last_error` is classified, not copied.** `ErrorView.sanitizeMessage` is private, only strips stack
frames and caps at 2000 characters, and the service has never served a raw exception message: the exception
handler deliberately refuses to echo one. `last_error` would be the first surface that did, and a
`RestClientResponseException` message carries an ADAS response body while a `DataAccessException` carries SQL
and bound values. So the handler stores a classification:

- the curated message of the runner's own exceptions (`ScanPageColumnException`,
  `UnaccountedScanPageException`, the contract exceptions);
- `"ADAS <status> <error code>"` for an upstream rejection;
- the simple class name otherwise.

It is capped at 500 characters. The sanitizer moves to a shared `ErrorMessages` helper so `ErrorView` and the
classifier use one implementation.

### Self-description

**10. A closed enum for the pause reason, split by pipeline-pause origin.** A console cannot branch on prose.
`pipeline_paused_operator` and `pipeline_paused_breaker` stay separate because the runner knows the origin at
pause time and the in-memory registry forgets it on restart. `PauseOrigin` is not reused: its two values and
their expiry invariant are about pipelines. The executor takes the origin from one new single-key read,
`PipelinePauseRegistry.current(name)` → `Optional<Pause>`, which evaluates expiry for that key only. This
replaces the `isPaused` + `paused()` double read, which copied the whole map and could disagree with itself.
The mid-run log line names the actual origin; today it says "tripped the DLQ circuit breaker" whatever the
origin was.

**11. The reason exists only while paused.** It is cleared by resume and by cancel in their single
statements, and paired with `paused_at` by a `CHECK`. The `state = 'paused'` `CHECK` is deferred one release,
because the previous image's resume and cancel cannot clear a column they do not map, and on rollback the
constraint would turn both into `503`s. The reason records what *paused* the run, not what holds it now; the
resume gates answer the second question. `last_error` differs: it is kept on a cancelled run, because
"why was this given up on?" is what it answers.

**12. `last_error` on the row, the count in a metric.** The metric is tagged by pipeline (per-pipeline map
and `evictPipeline`, like `runner.class1.rows.written`; note `runner.class1.tick.failures` itself is
untagged). The run is named on its row.

**13. `rows_total` is unmapped, not populated, and its column dropped later.** Every way of filling it is a
fabrication, degenerate, or a paired two-repo count endpoint that would still leave unbounded runs without a
total. The column drop waits one release because the previous image's entity still selects it
(`ddl-auto: none`).

### Findability

**14. A listing, not an `active_run_id` on the pipeline-cache entry.** The cache entry is a zero-query
projection of the ADAS feed. An `active_run_id` field would add a query per pipeline, could not represent
"one running and three paused", and would answer no historical question. The listing adds a `paused_reason`
filter, because "every cursor-stalled run" is its main triage question. At three optional filters, that makes
one JPQL query with `(:x IS NULL OR …)` predicates rather than eight derived methods. It uses the DLQ
listing's spellings and clamp shape, with `MAX_LIMIT = 500` (the DLQ's is 1000; a run is rarer).

**15. Lenient lowercase enum binding.** Spring binds `@RequestParam` enums with `Enum.valueOf`, which is
case-sensitive, and Jackson's `ACCEPT_CASE_INSENSITIVE_ENUMS` does not apply to query parameters. The run body
reports `"state": "running"`, so without a converter the value an operator copies from a response is a `400`.
A `WebMvcConfigurer` registers case-insensitive converters for `RunState`, `BackfillPauseReason` and the DLQ
`Lane`. A failed conversion is still `MethodArgumentTypeMismatchException` → `400 bad_request`, with a message
naming the permitted values. `@Schema(allowableValues)` documents the lowercase spellings. The entity keeps
`pausedReason` as the `dbValue()` string, as `state` and `kind` do, so MapStruct does not serialize
`name()`.

**16. DLQ run attribution without a payload change.** Adding `runId` to `ChunkPayload` / `UpsertPayload`
would change the serialized shape of every queued item; V8 had to clear the queue for exactly that. The
backfill chunk id is `#bf#<runId>#…#c<n>`, the upsert id appends `#w`, and run ids are UUIDs with no `#`. Two
things must be threaded or they break the claim that every stage names its run:

- `UpsertService.write` must pass its `itemId` into `doWrite` → `deadLetter` / `archive`. Its permanent
  rejection (an ADAS `422`) is the likeliest write-phase dead letter.
- `DlqRequeueService` must build the requeue id as `<pipeline>#requeue#bf#<runId>#<id>` for a run's item.
  The pipeline prefix is kept, so backpressure counting is unchanged. `DeterministicId.backfillRunIdOf`
  parses both forms, and `DeterministicId.requeueInstanceId` writes the second, so format and parse live in
  one place.

The backfill page commit already holds `runId` and stamps its own items directly. A group pipeline's
mark-time dead letters are `input_map` items keyed by group, which the backfill commit writes; the sweep's
`group_fetch` / `group_map` items are never a run's.

**17. `run_id` is an upsert attribute, not part of the identity.** Adding it to `dlq_item_row_identity_uk`
would split a grain failing in both lanes into two rows. So it follows the last writer, back to `NULL` when
the live path re-fails a grain. This only affects grain-keyed items; chunk-exhaustion and write items have no
grain key, are plain inserts, and never collapse. The requeue selects by pipeline, by run, or by both (their
intersection); neither is `400`. `lane=live` with `run_id` is `400` through a dedicated exception, because a
`ResponseStatusException` would fall to the catch-all `500`. No foreign key to `run_history`.

## Risks / Trade-offs

- **A permanently failing batch still retries forever.** → By design, and now visible through `last_error`
  and the counter; Cancel is the lever.
- **An extra execution per resume costs one wasted ADAS read.** → Bounded, and the price of never wedging.
- **The unique index can fail to build** on a database that already has two `running` runs for a pipeline. →
  The migration header gives the query; an operator cancels one and restarts. This fails loudly, which is
  better than an invariant that silently does not hold.
- **`start` and `resume` refuse more than before.** → Each refusal replaces a `2xx` that was undone on the
  first batch.
- **A retried `cancel` now returns `409`.** → The run is cancelled either way; a `GET` says so.
- **A `pipeline_changed` resume under a lane-changing edit** reads a cursor from the wrong lane. → Named as not
  handled; the operator's alternative is cancel and restart.
- **Forward compatibility of the `CHECK` lists.** → A new reason needs a migration, as with `state` and
  `dlq_item.stage`; V4 takes the same trade.

## Migration Plan

`V10__backfill_run_operability.sql` is additive, and its header records per statement why it exists (V4/V8/V9
style). That includes a pre-check query for duplicate `running` runs before the unique index.

Rollback of the image needs nothing: every new column is nullable, and the old image never writes
`paused_reason`, so neither of V10's pause-reason constraints can fire. One behaviour does change under a
rolled-back image, and the V10 header understates it: the old image's `resume` never checked for another
running run, so against the unique index a resume of a pipeline that already has a running run is now an
unhandled `500` (it used to succeed and leave two running). That is the invariant holding, not a regression,
but an operator on a rolled-back image should expect it. The `dlq_item` index is built without
`CONCURRENTLY` (Flyway runs V10 in a transaction); at 30-day retention the build is short, but it blocks
dead-letter writes from a still-running old pod for its duration.

A follow-up migration in the **next** release, once no deployable image predates V10, does three things:

1. `UPDATE run_history SET paused_reason = NULL, paused_at = NULL WHERE state <> 'paused'`, cleaning up after
   any rollback window;
2. adds `CHECK (paused_reason IS NULL OR state = 'paused')`;
3. drops `rows_total`.

It belongs to the next release and is not a task here, so that this change can archive.
