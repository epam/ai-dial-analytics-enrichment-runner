## 1. Shared status resolver

- [x] 1.1 Extract `PipelineStatusResolver` from `PipelineStatusReporter`. Move `state()`, `paused()`, `isStalled()`
  and `stallAfter()` and the startup grace (`startedAt`), and return a `Status(state, stalled, stallAfter, pause)`
  record with `state` as an enum. The last write and lag are inputs, so neither caller drains for the
  other.
- [x] 1.2 Make `PipelineStatusReporter` format its line from `Status`. `PipelineStatusReporterTest`'s assertions pass
  unchanged, so the log text is the same. Only its fixture changes, to construct the resolver.
- [x] 1.3 Add a non-draining `lastWrite(name)` accessor to `PipelineActivity`.

## 2. In-memory run outcome

- [x] 2.1 Add `PipelineRunOutcome`, an in-memory map of immutable `{hasMore, caughtUpAt, lastError, lastErrorAt}`
  records per pipeline, updated through `compute`, with a `retain(names)` that every successful sync in
  `PipelineSyncService` calls.
- [x] 2.2 Record the outcome in `PipelineScanExecutor`: on success, the last page's `has_more`, `caughtUpAt` when it
  is false (kept otherwise), and a cleared error. On failure, record `ErrorMessages.classify(e)` and rethrow
  unchanged. The new catch covers the gate reads and the drain, and adds no log line and no metric. Gated and
  cache-absent skips record nothing.
- [x] 2.3 Record the outcome the same way in `Class1MaterializeExecutor`, in the existing catch-and-rethrow.
- [x] 2.4 Unit tests:
  - success clears the error and keeps `caughtUpAt` when `has_more` is true;
  - failure records the error and still rethrows, a held failure included;
  - a gated skip leaves the outcome untouched;
  - an evicted pipeline is forgotten.

## 3. Read queries

- [x] 3.1 `SchedulerQueueReader`:
  - a single-row read of the pipeline's recurring row (`PipelineRouting.taskName`, instance = name) returning
    `execution_time`, `picked`, `consecutive_failures` and `last_failure`;
  - queue counts by task (computing, awaiting write, retrying) over `WorkTasks.PIPELINE_SCOPED` and the `name#`
    prefix. Implemented as `InflightCounter.countsByTaskAndInstancePrefix`, so it shares the escaped prefix match
    with the backpressure count. *`retrying` superseded by 10.2.*
- [x] 3.2 `GroupStateWriter` (or a sibling reader): add one aggregate query for `tracked`, `pending`, `ready`,
  `waiting_idle`, `ceiling_blocked`, `oldest_pending_at`, `last_evaluated_at` and `evaluations_today`. It reuses the
  dirty, readiness and ceiling fragments `findDirtyAndReady` and `countCeilingBlocked` are composed from. Move the
  `Readiness` construction out of `GroupReadinessSweep.enqueueReady` into one factory that both use. *`tracked`,
  `oldest_pending_at`, `last_evaluated_at` and `evaluations_today` superseded by 10.2.*
- [x] 3.3 `SpendService.today()`: add `failures` to `DailySpend`.
- [x] 3.4 `DlqItemRepository`: add a count by stage, with the payload-carrying count per stage, over `LISTING_FILTER`.
  The newest live item reuses `list(name, null, "live", PageRequest.of(0, 1))` and `DlqItemMapper`. *Superseded by
  10.3.*
- [x] 3.5 `RunHistoryRepository`: add the pipeline's running run, or else its most recently started paused run, and
  the count of its running and paused runs. *Superseded by 10.3.*
- [x] 3.6 Extract `DialProperties.resolveRpm(Integer)` from `DialRateLimiter.acquire`, and use it in both places.
  *Superseded by 10.3.*

## 4. Service and endpoint

- [x] 4.1 Add the `web/dto/runtime/*` records (`PipelineRuntimeDto` and one record per section): snake_case through
  explicit `@JsonProperty`, with `@Schema` descriptions. `failures.last` reuses `DlqItemDto`, and `backfill` reuses
  `BackfillRunDto`, nested as `{active_runs, run}`. The primary mapper already omits nulls, so a section or field left null is
  absent. *`failures.last` and `backfill` superseded by 10.2.*
- [x] 4.2 Add `PipelineRuntimeService`. It resolves the lane from `PipelineCache`, assembles the sections per the design's
  "Field sources" table, includes only the sections and settings that apply, and resolves `settings` through the existing resolvers.
  Before a fire has recorded an outcome, it fills `last_error_at` from the recurring row's `last_failure` when
  `consecutive_failures > 0`. *`settings` superseded by 10.2.*
- [x] 4.3 Add `GET /v1/pipelines/{name}/runtime` in a `PipelineRuntimeController` with an `@Tag`. It answers 503
  `pipeline_cache_cold` through `PipelineCacheNotReadyException` when the cache was never populated, checked first,
  and otherwise 404 `not_found` through `PipelineNotFoundException`.
- [x] 4.4 Endpoint tests: the response shape and the section omission per lane, and 404, in
  `PipelineRuntimeIntegrationTest`. The 503 path is checked in `PipelineRuntimeServiceTest`, against a cold cache that
  a shared Spring context cannot provide.

## 5. Integration tests (Testcontainers)

- [x] 5.1 Row lane:
  - queued chunks with lag 0;
  - a dead letter reflected in `failures` (no payload), with totals equal to `/v1/dlq`'s for the same filter;
  - `spend_today`;
  - `settings.rows_per_call` defaulted. *Superseded by 10.4.*
- [x] 5.2 Group lane: ready, idle-waiting and ceiling-blocked groups counted as in the spec scenario.
- [x] 5.3 Class-1 lane:
  - a failing materialize sets `consecutive_failures` and `last_error`;
  - a successful fire clears the error and resets the count;
  - the LLM-only sections are absent.
- [x] 5.4 Status parity: an over-budget pipeline reads `over_budget` in the view and `over budget` in the heartbeat,
  and a breaker pause reports its origin and `resumes_at`.
- [x] 5.5 Restart semantics: a fresh `PipelineRunOutcome` leaves the in-memory fields absent while the durable fields
  are populated.
- [x] 5.6 Backfill: a paused run with none running is reported in `backfill`. *Superseded by 10.4.*
- [x] 5.7 Restart fallback: a pipeline with `consecutive_failures` 2 and no recorded outcome reports `last_error_at` as
  the row's `last_failure` and no `last_error`. With a count of 0, it reports neither.

## 6. Paused-pipeline listing in snake_case

- [x] 6.1 Annotate `PausedPipelineDto` with `@JsonProperty("pipeline_name")` and `@JsonProperty("resumes_at")`.
- [x] 6.2 Add a controller test that pins the listing's keys: `pipeline_name`, `origin` lowercase, `reason`, `since`,
  `resumes_at`, and no camelCase key.

## 7. Code-review follow-ups

- [x] 7.1 Fall back to the recurring row's `last_failure` whenever the outcome names no error and the row counts failures,
  not only before the first recorded outcome. This covers a JVM `Error` the executors never catch.
- [x] 7.2 Guard the readiness-window parses: an unparseable window omits `groups` and the affected settings instead of
  failing the read. *The settings part superseded by 10.2.*
- [x] 7.3 Test the group counts against `findDirtyAndReady` and `countCeilingBlocked` for {idle, staleness, signal} ×
  {ceiling, none}.
- [x] 7.4 Test that a running backfill wins over a newer paused one. *Superseded by 10.4.*
- [x] 7.5 Test the queue composition (group items, write phase, requeued backfill item counted, backfill chunk
  excluded), a page-budget stop recording `has_more`, and the exact classified error of a held fire.
- [x] 7.6 Emit `budget` as `{}` when the spend gate is enabled with both caps unlimited. *Superseded by 10.2.*
- [x] 7.7 Replace the one-shot outcome eviction with `retain` on every sync, so a fire that finishes after its pipeline
  left does not leave an entry behind.

## 8. Docs and verification

- [x] 8.1 `docs/configuration.md`:
  - correct the breaker-pause wording (pauses expire after `pause-for`, doubling up to `pause-max`);
  - correct the spend key (pipeline, generation, day);
  - state that the daily call budget counts successful calls only.
- [x] 8.2 `docs/e2e-runbook.md`: add a runtime-view curl example for each lane.
- [x] 8.3 `docs/kubernetes-exposure.md`: add `GET /v1/pipelines/{name}/runtime` to the ownership table as this
  runner's.
- [x] 8.4 `docs/release-0.2-upgrade.md`: confirm the `origin` casing fix (`operator` or `breaker`), which was applied
  while drafting this change.
- [x] 8.5 (done in `docs/release-0.3-upgrade.md` §2, §3) Next release's upgrade notes: the pause listing's `pipelineName` → `pipeline_name` and `resumesAt` →
  `resumes_at`, with a before/after example and a `jq` hint. Also the new `GET /v1/pipelines/{name}/runtime`.
- [x] 8.6 `openspec validate pipeline-runtime-view --strict`.
- [x] 8.7 `./gradlew clean build` passes (use `TESTCONTAINERS_RYUK_DISABLED=true` if Ryuk cannot start).

## 9. Second review follow-ups

- [x] 9.1 A fire that reads no page or window (`scan.max-pages-per-fire` 0) records `has_more` true, so it never
  reads as caught up.
- [x] 9.2 `ScheduleFactory.cadence` refuses a schedule trigger without a cron, as `forPipeline` does, so the stall
  rule reports no cadence for a pipeline that is never scheduled. Kept by 10.3: the heartbeat still uses it.
- [x] 9.3 `settings.sample_fraction` reports the share kept (`Sampler.effectiveFraction`), not the raw declaration.
  *Superseded by 10.3.*
- [x] 9.4 `SpendService` reads the day from the injected `Clock`, the same day the group counts use.
- [x] 9.5 The readiness factory moves from `PipelineRouting` to `GroupReadinessSweep`, so `sync` does not depend on
  `persistence`. `GroupStateWriter` builds its dirty and within-ceiling predicates from one fragment each.
- [x] 9.6 Spec: settings fields per lane, the `backfill` shape, an empty `budget`, `last_write_at` recorded per
  write, and which absences carry a meaning. *The settings, `backfill` and `budget` parts superseded by 10.5.*
- [x] 9.7 Tests: the status resolver, a zero-page and a stalled-cursor fire, the blank-cron cadence, the effective
  sample fraction, the group counts' pipeline and day filters, `active_runs`, and authentication on the new path.
  *The effective sample fraction and `active_runs` superseded by 10.4.*

## 10. Trim the view

Design, "Trimmed to what the console needs": the view is trimmed to what the console needs before any client parses it.

- [x] 10.1 Decide the trimmed shape: `pipeline_name`, `lane`, `generation`; `status{state, origin, reason, since,
  resumes_at}`; `schedule`; `progress`; `queue{computing, awaiting_write}`; `groups{pending, ready, waiting_idle,
  ceiling_blocked}`; `spend_today{calls, tokens, failed_calls}`; `failures{total, requeueable_total}`.
- [x] 10.2 Remove from the DTOs and `PipelineRuntimeService`: `status.stalled` (the heartbeat still computes and logs
  it), `queue.retrying`, `spend_today.budget`, `failures.by_stage`, `failures.last`, the `backfill` and `settings`
  sections, and `groups.tracked`, `oldest_pending_at`, `last_evaluated_at` and `evaluations_today`.
- [x] 10.3 Revert the code only the removed fields needed: `DialProperties.resolveRpm`, `Sampler.effectiveFraction`,
  `DlqStageCounts` and `DlqItemRepository.countsByStage`, and `RunHistoryRepository.countByPipelineNameAndStateIn`.
  The failure totals use the existing `DlqItemRepository.counts`. Keep `ScheduleFactory.cadence` throwing on a blank
  cron, `SpendService` on the injected `Clock`, and the readiness factory in `GroupReadinessSweep`.
- [x] 10.4 Tests: assert the removed fields are absent per lane, and drop the tests of removed fields and code.
- [x] 10.5 Spec, proposal and design: remove the dropped fields, record the trimming decision, and keep the main `runner-ops` spec
  identical to this change's delta. `openspec validate --specs --strict` passes.
- [x] 10.6 Docs: the runtime-view section of `docs/e2e-runbook.md` points to `/v1/dlq` and `/v1/backfills` for the
  dropped details, and `docs/kubernetes-exposure.md` describes the view's sections.
- [x] 10.7 `./gradlew clean build` passes (use `TESTCONTAINERS_RYUK_DISABLED=true` if Ryuk cannot start).
