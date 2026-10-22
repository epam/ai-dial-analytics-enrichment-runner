## 1. Schema and vocabulary

- [x] 1.1 `db/migration/postgres/V10__backfill_run_operability.sql` exactly as `proposal.md` gives it:
      `run_history` += `paused_reason`, `paused_at`, `last_error`, `last_error_at`, `pipeline_generation`;
      `run_history_paused_reason_check` (NULL or the seven values);
      `run_history_paused_reason_at_check` (reason and instant together);
      `run_history_one_running_per_pipeline_uk` (partial unique on `pipeline_name WHERE state = 'running'`);
      `dlq_item` += `run_id` and the partial `dlq_item_run_id_created_at_idx`. The header is in V4/V8/V9
      style: why each constraint exists; the pre-check query for duplicate `running` runs, which the unique
      index would reject; why the `run_id` index is partial and has no foreign key; why nothing is
      backfilled; and that the `state` `CHECK` and the `rows_total` drop are deliberately deferred. Re-check
      the highest applied version before writing.
- [x] 1.2 `backfill/BackfillPauseReason` (`OPERATOR`, `PIPELINE_PAUSED_OPERATOR`, `PIPELINE_PAUSED_BREAKER`,
      `SPEND_BUDGET_EXHAUSTED`, `CURSOR_STALLED`, `PIPELINE_ABSENT`, `PIPELINE_CHANGED`), with a lowercase
      `dbValue()` and a lenient `fromDbValue`. Javadoc why the two pipeline-pause values are separate, and
      that `PIPELINE_ABSENT` also covers a pipeline refused as unexecutable.
- [x] 1.3 `backfill/RunState` += `canPause()`, `canResume()`, `canCancel()` and a lenient `fromDbValue`.
- [x] 1.4 `persistence/RunHistoryEntity`: += `pausedReason` (a `String` holding `dbValue()`, like `state`
      and `kind`), `pausedAt`, `lastError`, `lastErrorAt`, `pipelineGeneration`; − `rowsTotal`.
- [x] 1.5 `persistence/RunHistoryRepository`: conditional `@Modifying` single-statement updates, each
      returning a row count, each called from inside `@Transactional`:
      - `advance(id, cursorBefore, next, rowsDelta)` — `WHERE state = 'running' AND cursor IS NOT DISTINCT
        FROM :cursorBefore`; also clears `last_error` and `last_error_at`;
      - `pauseIfRunning(id, reason, at)`;
      - `markDoneIfRunning(id, at)`;
      - `pauseByOperator(id, at)`;
      - `resumeIfPaused(id, generation)` — clears the reason and the instant;
      - `cancelIfActive(id, at)` — clears the reason, keeps `last_error`;
      - `recordBatchFailureIfRunning(id, message, at)`;
      - `adoptGeneration(id, generation)`;
      - one listing JPQL with optional `pipelineName`, `state` and `pausedReason`, ordered
        `started_at DESC`, taking a `Pageable`.
- [x] 1.6 `persistence/DlqItemEntity` += `runId`. `persistence/DlqItemWriter` binds it and adds
      `run_id = EXCLUDED.run_id` to `DO UPDATE SET`, with javadoc why it is not in the identity.

## 2. Lifecycle

- [x] 2.1 Remove every load-and-save of `run_history` from `BackfillService` and `BackfillPageCommitter`;
      each transition uses 1.5.
- [x] 2.2 `pause` / `resume` / `cancel`: guard through `RunState`; on zero rows, reload and refuse with
      `BackfillConflictException` naming the current state.
- [x] 2.3 `start` and `resume` gates, in order:
      - `pipeline_cache_cold` `503` (new exception, with a `DefaultExceptionHandler` entry);
      - absent `404`;
      - pipeline paused `409` naming the origin;
      - over budget `409` naming the 00:00 UTC reset;
      - another `running` run `409` naming its id.

      Translate the unique-index violation into the same `409`. `start` records the pipeline's generation;
      `resume` adopts the current one.
- [x] 2.4 `resume` schedules a fresh instance, `BackfillTask.resumeInstanceId(runId, cursor, now)` →
      `…#r<epochMillis>`, unconditionally. Continuation batches keep `instanceId(runId, cursor)`.
- [x] 2.5 `BackfillPageCommitter`: every commit path (chunk, group mark, Class-1) ends with `advance`, passing
      the already-`CursorGuard`-advanced cursor. On zero rows throw `RunSupersededException`, rolling back the
      page's enqueues, marks and dead letters.

## 3. The executor

- [x] 3.1 `BackfillExecutor.run`:
      - call `pipelineCache.requirePopulated("backfill batch " + runId)` before the lookup;
      - a miss after that pauses with `PIPELINE_ABSENT`;
      - a generation mismatch pauses with `PIPELINE_CHANGED` before any page. A run with a null generation
        (pre-V10) adopts the current one instead.

      Correct the class javadoc.
- [x] 3.2 Every pause site calls `pauseIfRunning` with its reason; completion calls `markDoneIfRunning`.
      Catch `RunSupersededException` and return without chaining.
- [x] 3.3 `PipelinePauseRegistry.current(name)` → `Optional<Pause>`, evaluating expiry for that key only. The
      two pipeline-pause sites use it for both the gate and the origin, and the mid-run log line names the
      actual origin.

## 4. The failure handler

- [x] 4.1 `backfill/BackfillBatchFailureHandler`, wired on `BackfillTask` in place of `onFailureRetryLater()`.
      Its order:
      1. `WorkHold.holdIfHeld("backfill batch " + runId, …)`;
      2. load the run;
      3. `recordBatchFailureIfRunning` with the classified message; on zero rows, `operations.remove()`;
      4. increment the counter;
      5. reschedule on `RetryBackoff.withJitter(WORKER_BACKOFF_BASE, consecutiveFailures)`.

      Steps 2–4 are in a try/catch, so a recording failure still reschedules.
- [x] 4.2 `exception/ErrorMessages`: extract `ErrorView.sanitizeMessage`, and add the classifier. It returns the
      curated text for the runner's own exceptions, `"ADAS <status> <error code>"` for an upstream rejection,
      and the simple class name otherwise, capped at 500 characters.
- [x] 4.3 Javadoc the deliberate omissions: no attempt budget, no archive, no terminal `failed` state.
- [x] 4.4 `observability/RunnerMetrics`: `runner.backfill.batch.failures`, a per-pipeline map like
      `class1RowsByPipeline`, removed in `evictPipeline`; comment why it is not tagged by run.

## 5. The wire

- [x] 5.1 `BackfillRunDto`: += `paused_reason`, `paused_at`, `last_error`, `last_error_at`; − `rows_total`.
- [x] 5.2 `BackfillRunListResponse(List<BackfillRunDto> runs)`; `BackfillRunMapper` gains the list method.
- [x] 5.3 `BackfillService.list(pipelineName, state, pausedReason, limit)` with
      `Math.clamp(limit, 1, 500)`.
- [x] 5.4 `BackfillController`: `GET /v1/backfills` with `pipeline_name`, `state`, `paused_reason`, `limit`
      (default 100), and `@Schema(allowableValues)` listing the lowercase spellings.
- [x] 5.5 A `WebMvcConfigurer` registering case-insensitive converters for `RunState`,
      `BackfillPauseReason` and the DLQ `Lane`. A failed conversion stays
      `MethodArgumentTypeMismatchException` → `400`, with a message naming the permitted values.
- [x] 5.6 `DlqItemDto` += `run_id`.

## 6. DLQ attribution

- [x] 6.1 `scan/DeterministicId`:
      - `backfillRunIdOf(instanceId)` parses both the backfill chunk/write form and the run-carrying requeue
        form, and returns null for live;
      - `requeueInstanceId(pipeline, itemId, runId)` writes `<pipeline>#requeue#bf#<runId>#<id>` when there
        is a run, and today's form otherwise.
- [x] 6.2 `BackfillPageCommitter` stamps its dead-letter items with its `runId`, on the chunk and the
      group-mark paths.
- [x] 6.3 Thread the instance id into every row-lane dead-letter write:
      - `worker/WorkerService.doProcess` → `dlqItem`;
      - `worker/ChunkFailureHandler` and `worker/upsert/UpsertFailureHandler`, from
        `execution.taskInstance.getId()`;
      - `worker/upsert/UpsertService.write` → `doWrite` → `deadLetter` / `archive`, which covers the
        absent-pipeline archive and the permanent rejection.
- [x] 6.4 `DlqRequeueService`: build the requeue id through `requeueInstanceId`, and select by
      `pipeline_name`, `run_id` or both (intersection); neither is `400`.
      `DlqItemRepository` += `findByRunId` and `findByPipelineNameAndRunId`. Add a comment that requeued run
      items count toward live backpressure and keep their task's priority.
- [x] 6.5 `DlqController`: `run_id` and `lane` on the listing. `lane=live` with `run_id` raises a dedicated
      exception mapped to `400 bad_request`.
- [x] 6.6 A comment at the readiness sweep's enqueue: group-sweep items are never a run's.

## 7. Tests

- [x] 7.1 Transition table: pausing a done, cancelled or paused run is `409`, with state and `finished_at`
      unchanged; cancelling a done or cancelled run is `409`; the permitted transitions succeed.
- [x] 7.2 Concurrency:
      - a cancel mid-batch enqueues nothing more after the next page and leaves the run cancelled with the
        cancel's `finished_at`, recording no failure;
      - pause-then-resume during a draining batch gives one drain and a correct `rows_done`;
      - resume while a retry is pending after a failure on page ≥ 2, and while a retry is executing, leaves
        exactly one advancing execution and never a chainless `running` run;
      - an operator verb concurrent with `advance` never moves the cursor backwards;
      - two concurrent starts give one run and one `409`.
- [x] 7.3 Gates:
      - start or resume into a paused or over-budget pipeline is `409`;
      - into an absent one is `404`;
      - with a cold cache is `503 pipeline_cache_cold`;
      - resume while another run is running is `409` naming it.
- [x] 7.4 Pause reasons:
      - each site records its reason and instant;
      - the two origins record different values, and so do the log lines;
      - resume and cancel clear both;
      - a non-paused run serializes neither;
      - a rolled-over UTC day does not resume a spend-paused run.
- [x] 7.5 Absent pipeline and cold cache:
      - a populated-cache miss pauses with `pipeline_absent`, after which a new backfill is accepted, a
        resume while still absent is `404`, and a resume after the pipeline returns drains from the committed
        cursor;
      - a batch due before the first sync is held, with no reason and no `last_error`.
- [x] 7.6 Generation:
      - an edit mid-run pauses with `pipeline_changed` before the next page;
      - resume adopts the current generation;
      - a pre-V10 run with no generation adopts one without pausing.
- [x] 7.7 Failure handler:
      - records the classified message (an ADAS `422` stores status and code, never the body), increments
        the counter and reschedules on the jittered backoff;
      - a held batch does none of these;
      - a successful commit clears `last_error`;
      - a failure on a cancelled run removes the execution and keeps `last_error`;
      - a recording failure still reschedules.
- [x] 7.8 Listing:
      - the filter matrix over pipeline, state and pause reason, newest first;
      - `state=running` and `state=RUNNING` both bind;
      - an unknown state or reason is `400` naming the permitted values;
      - the clamp.
- [x] 7.9 DLQ:
      - the run is recorded at every row-lane stage (`input_map`, `dial_call`, `validate`, `output_map`),
        on write-phase exhaustion, and on a write-phase permanent rejection;
      - group mark-time `input_map` names the run, and a group-sweep item does not;
      - live items carry none;
      - the `DeterministicId` parse cases, including requeue ids;
      - a per-row both-lane collapse gives one row carrying the last writer's run, back to null;
      - the run and lane filters work, an unknown lane is `400`, and `lane=live` with `run_id` is `400`;
      - requeue by run, by pipeline, by both, and by neither (`400`);
      - a requeued run item that fails again keeps its run.
- [x] 7.10 Repository: each `CHECK` and the unique index reject bad writes.
- [x] 7.11 Metrics: the counter is per pipeline and evicted with its pipeline.

## 8. Docs and specs

- [x] 8.1 `docs/e2e-runbook.md`:
      - §6: the Backfill bullet gets the listing; the DLQ bullet gets the run and lane filters and the
        run-scoped requeue;
      - §10: the verbs' permitted states, re-cancel `409`, and the new fields in the `jq` line;
      - §10 failure table: rows for a disabled pipeline (`pipeline_absent`), an edited pipeline
        (`pipeline_changed`), a failing batch (`last_error`, the counter, Cancel), and a paused pipeline now
        reported by `paused_reason`;
      - §11 "Work that is held": the held backfill batch;
      - fold appendix: a note beside the pre-V8 capture query that names `rows_total`, without editing the
        query.
- [x] 8.2 `docs/release-0.2-upgrade.md` and the fold runbook's "confirm no backfill is RUNNING": point at
      `GET /v1/backfills?state=running`.
- [x] 8.3 Record as deferred work outside this change the next-release migration — clean up stale reasons,
      add `CHECK (paused_reason IS NULL OR state = 'paused')`, drop `rows_total` — and the named non-goals
      (auto-pause after N failures, chain-liveness detector, lane-change detection across `pipeline_changed`).
      The group-payload `COALESCE` is handled in this change instead (10.4).
- [x] 8.4 Confirm every delta matches what shipped; run
      `openspec validate 2026-09-23-backfill-run-operability --strict`.

## 9. Verification

- [x] 9.1 `./gradlew clean build` passes; prefix it with `TESTCONTAINERS_RYUK_DISABLED=true` if the reaper
      cannot start.

## 10. Review follow-ups

- [x] 10.1 The generation joins the single-writer check: `advance` and `pauseIfRunning` require
      `pipeline_generation IS NOT DISTINCT FROM :generation`, so a batch that outlived a pause, an edit and a
      resume is superseded instead of committing a page keyed to the old generation and re-pausing the resumed
      run with `pipeline_changed`. A pre-V10 run adopts its generation at the first page that finds the
      pipeline, so an absent pipeline pauses `pipeline_absent` rather than later `pipeline_changed`.
- [x] 10.2 Start and resume translate only a `run_history_one_running_per_pipeline_uk` violation into
      `409`; any other integrity violation is rethrown.
- [x] 10.3 The run listing trims `pipeline_name` like the DLQ listing, and orders `started_at DESC, id DESC`.
- [x] 10.4 `DlqItemWriter`: a payload-less repeat keeps an existing row's payload, task and run, so a replay's
      mark-time failure cannot make a live group failure unrequeueable and file it under the run.
- [x] 10.5 `ErrorMessages` moves to `exception/` (the backfill handler no longer depends on `web/`);
      `classify` names a wrapper's root-cause type and never returns an empty name.
- [x] 10.6 Tests that could not fail are made to (verb/cursor interleaving, the listing ceiling, constraint
      names), plus coverage for the stale-generation race, the index race path, the executor's yields after
      cancel, the payload-keeping upsert and write-phase exhaustion attribution.

