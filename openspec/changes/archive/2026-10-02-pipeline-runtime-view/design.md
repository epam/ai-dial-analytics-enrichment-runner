## Context

The admin console's "Runtime state" block, in a pipeline's Properties, reads ADAS `GET /v1/pipelines/{name}` →
`state`. ADAS fills that state per kind:

| Kind | What ADAS reports in `state` |
|---|---|
| `aggregate` | cursor, lag, last and next run, `has_more`, last error, clamp |
| `enrich` with `transform.type: sql` | `materialized_through_*` and `drained_at` |
| `enrich` with `llm` | only the freshness diagnostics computed on read (`unclamped_reads`, `rebuild_required`) |

ADAS's spec says it SHALL NOT report a position it does not know for an `enrich` pipeline, because the runner owns
that position. ADAS never calls the runner.

The runner holds the missing state, but it is scattered and none of it is served per pipeline:

| Where | What |
|---|---|
| Postgres `pipeline_cursor` | lag, last scan |
| Postgres `scheduled_tasks` | next fire, consecutive failures, in-flight work items |
| Postgres `group_state` | group readiness |
| Postgres `spend_counter` | calls, tokens, failures per day |
| Postgres `dlq_item` | dead letters |
| Postgres `run_history` | backfills |
| `PipelineCache` (memory) | effective settings |
| `PipelinePauseRegistry` (memory) | pauses |
| `PipelineActivity` (memory) | last write |
| `PipelineStatusReporter` (log only) | the state verdict |

Constraints:
- The runner is a single active replica (`runner-ops` topology requirement), so in-memory state is authoritative for
  the process that serves the read.
- `/v1` requires `FULL_ADMIN` (`api-authentication`).
- Source content is never exposed (sensitive-data posture).

## Goals / Non-Goals

**Goals:**
- One per-pipeline read that serves all three lanes in one shape, so the UI renders sections, not lane-specific
  pages.
- Reuse existing state and queries. Add no schema.
- One status verdict, shared by the heartbeat and the endpoint.
- Give the SQL lane a failure signal (`schedule.last_error`, `consecutive_failures`), since it has no DLQ.

**Non-Goals:**
- A list or all-pipelines runtime endpoint. It may come later with batched queries.
- Persisting pause state or run outcomes.
- ADAS-owned facts (`materialized_through_*`, `drained_at`, clamp, freshness diagnostics). The UI reads those from
  ADAS and shows them alongside.
- Recording "held back by upstream" for LLM scans. That is an ADAS change.
- Money cost, and prompt vs completion tokens.
- Changing the heartbeat's output.

## Decisions

### One endpoint, lane-shaped by omission
Path: `GET /v1/pipelines/{name}/runtime`.

| Field or section | Present for |
|---|---|
| `pipeline_name`, `lane` (`row`, `group`, `class1`: `PipelineRouting.lane`, the spelling the heartbeat and the sync log lines use), `generation` | every lane |
| `status` (`state`, and `origin`, `reason`, `since`, `resumes_at` while paused) | every lane |
| `schedule` (`last_scan_at`, `next_run_at`, `running_now`, `consecutive_failures`, `last_error`, `last_error_at`) | every lane |
| `progress` (`lag_seconds`, `has_more`, `caught_up_at`, `last_write_at`) | every lane |
| `queue` (`computing`, `awaiting_write`), `spend_today` (`calls`, `tokens`, `failed_calls`), `failures` (`total`, `requeueable_total`) | `row` and `group` |
| `groups` (`pending`, `ready`, `waiting_idle`, `ceiling_blocked`) | `group` |

The shape is deliberately the minimum the console needs; what was dropped from the first cut, and why, is in "Trimmed to what the console needs".

An absent section means "does not apply to this lane". Inside a present section, a field the runner does not know
right now (for example after a restart) is absent too. The primary `JsonMapper` already omits every null
(`JsonMapperConfiguration`, `NON_NULL`), the same rule `BackfillRunDto` documents ("absent rather than null"), so the
DTOs need no `@JsonInclude` of their own. Because a section's presence already says it applies, a missing field inside
it reads as "unknown", never as "not applicable". This matches ADAS's convention for `state` (omit what does not apply)
and the heartbeat's "unknown, never estimated" rule.

Every property is snake_case through an explicit `@JsonProperty`, as in `DlqItemDto`, `GroupStateDto` and
`BackfillRunDto`. There is no global naming strategy. `PausedPipelineDto` has no annotations, so it emits
`pipelineName` and `resumesAt`. It is the one outlier, and this change fixes it (see "The paused-pipeline listing moves to snake_case").

*Alternative:* one DTO per lane behind a discriminator. Rejected: most sections are shared, and the UI would have to
branch on lane for every one of them.

### Status from a shared resolver
`PipelineStatusResolver` is extracted from `PipelineStatusReporter.state()`, `paused()`, `isStalled()` and
`stallAfter()`.

- It returns `Status(state, stalled, stallAfter, pause)`, where `pause` is the registry's `Pause` (origin, reason,
  since, resumesAt) and `stallAfter` is the threshold the heartbeat prints. `state` is an enum
  (`ACTIVE`, `PAUSED`, `HELD`, `OVER_BUDGET`, `BACKPRESSURED`) and is written lowercase on the wire.
  `origin` is `operator` or `breaker`, spelled as in `/v1/pipelines/paused`.
- It takes the last write, the lag and `now` as inputs. The reporter passes the `lastWrite` of the snapshot it has
  already drained, and the endpoint passes the non-draining read (see "Run outcome in memory").
- The resolver owns the startup instant (`startedAt`) and the grace, so the two callers share one clock origin.
- Precedence is unchanged: paused > held > over_budget > backpressured > active.
- Class-1 is never over_budget, because `SpendPauseGate.isOverBudget(PipelineDto)` excludes it. It is never
  backpressured, because the reporter skips that check for it. Both stay as they are today.
- `stalled` keeps the existing rule: lag > 0 and no write for more than 3 cadences, with the startup grace. It is
  never true while paused. Only the heartbeat reads it: the view does not carry it (see "Trimmed to what the console needs"). The cadence comes from
  `ScheduleFactory.cadence`, which throws for a schedule pipeline without a cron, as `forPipeline` does, so such a
  pipeline (never scheduled) has no cadence and never reads as stalled, rather than being measured against the
  default delay.
- The reporter maps `Status` to its words (`over budget`, `PAUSED by breaker 3m ago: …`). The log text is
  unchanged: `PipelineStatusReporterTest`'s assertions stay as they are. Only its fixture changes, because the
  reporter's constructor now takes the resolver.
- The resolver calls the same gates the heartbeat calls, and they have the same side effects. `SpendPauseGate` and
  `BackpressureGate` log a transition the first time any caller observes it. `PipelinePauseRegistry.current` lifts
  and logs an expired breaker pause. A view read can therefore be where such a line is first logged. That is still
  one line per change, which the gate-transition requirement allows.

*Alternative:* compute the status independently in the service. Rejected: the heartbeat and the endpoint would drift.

### Run outcome in memory, next to `PipelineActivity`
`PipelineRunOutcome` is a `ConcurrentHashMap<String, Outcome>`, where `Outcome` is an immutable record replaced
through `compute`. It never mutates a shared object, so a concurrent read sees one whole outcome. Each pipeline has
one recurring row, so two fires of the same pipeline never run at once. Every successful sync calls
`retain(desired names)`. Removal is a sync event, and the heartbeat's clock is not the place for it. A one-shot evict
when a pipeline leaves is not enough: a fire still running at that moment records its outcome afterwards and would
re-create the entry, so the next sync drops it instead.

Each executor writes it once per fire:
- At the end of `PipelineScanExecutor.drain` and `Class1MaterializeExecutor.drain`:
  - `hasMore`: the last page's or window's `has_more`. A drain that stopped early (budget, cursor stall, a gate
    tripping mid-drain) therefore reads true.
  - `caughtUpAt`: set to now when that is false, and otherwise kept from the previous outcome.
  - The last error is cleared.
- In each executor's failure path: `lastError = ErrorMessages.classify(e)` and `lastErrorAt` are recorded, then the
  exception is rethrown unchanged. `hasMore` and `caughtUpAt` keep their previous values.
  - Class-1: added to the existing catch-and-rethrow in `execute`, which already covers the drain.
  - Scan: a new `catch (RuntimeException e)` in `execute`. It covers the spend and backpressure gate reads as well as
    the drain, because both of those query Postgres and a failure in either fails the fire. The catch only records
    and rethrows: no log line and no metric. The fire's one failure line stays the `ExecutionMdcListener`'s, as it is
    today. `@LogExecution` sits outside `execute` and is not affected.
- A fire that fails with a `HeldWorkException` (an ADAS contract skew reaching the scan or materialize call) is
  recorded like any other failure. db-scheduler counts it in `consecutive_failures` whatever the listener logs, and
  the two fields would disagree otherwise. `status.state` reads `held` alongside.

A fire skipped by a gate, or by the cache-absent guard, records nothing, because it is not a run. db-scheduler still
completes that fire normally. For a recurring task that is a success, and it resets `consecutive_failures` to 0
(`ExecutionOperations.reschedule`). So after a gated fire `last_error` can be set while `consecutive_failures` is 0.
The spec states this so the UI does not read it as a contradiction.

Backfill fires (`backfill-run`) never touch the outcome. A run's own failure is the run's `last_error` in
`GET /v1/backfills`.

`last_write_at` comes from `PipelineActivity.lastWrite`, through a new non-draining accessor (`drain()` resets the
heartbeat's counts and must not be called by a read). It is fed by every upsert success, including a backfill run's
writes on the LLM lanes, and by a live Class-1 window that wrote rows.

*Alternative:* new `pipeline_cursor` columns. Deferred by decision: single replica, the loss window is one cadence
after a restart, and the columns can be added later on the row each commit already updates.

### Field sources

| Field | Source |
|---|---|
| `schedule.last_scan_at` | `pipeline_cursor.last_scan_at`, the column the heartbeat reports as "last scan". Durable. The instant a live fire last committed a page or window. The committers write it on every page, an empty one included, so a fire that fails after committing some pages has still moved it. A backfill never writes it. |
| `schedule.next_run_at`, `schedule.running_now` | One new single-row read of the pipeline's recurring row: `task_name = PipelineRouting.taskName(pipeline) AND task_instance = name`, returning `execution_time`, `picked`, `consecutive_failures` and `last_failure` together. `running_now` is `picked`. While it is true, `next_run_at` is absent, because `execution_time` is then the running fire's due time and not a future one. |
| `schedule.consecutive_failures` | `scheduled_tasks.consecutive_failures` from the same row. Durable, so it survives a restart even when `last_error` does not. It counts consecutive fires that threw. A gated or cache-absent skip completes normally and resets it to 0 (see "Run outcome in memory"). |
| `schedule.last_error`, `schedule.last_error_at` | `PipelineRunOutcome` when it names an error. Otherwise there is a durable fallback from the same recurring row: when `consecutive_failures > 0`, `last_error_at` = `last_failure` and `last_error` is absent (db-scheduler stores no message). When the count is 0, both are absent. This covers a restart before the next fire, and a failure the executors' `RuntimeException` catch never sees (a JVM `Error`, or a throw outside `execute`), which db-scheduler still counts. A gated skip resets the count, so the fallback can only under-report a failure, never invent one. |
| `progress.lag_seconds` | `pipeline_cursor.lag_seconds` |
| `progress.has_more`, `progress.caught_up_at` | `PipelineRunOutcome`. For the group lane these describe the dirty-mark scan, not evaluation. Evaluation progress is `groups`. |
| `progress.last_write_at` | `PipelineActivity` |
| `queue.*` | `InflightCounter.countsByTaskAndInstancePrefix`: one `GROUP BY task_name` over `scheduled_tasks` with `task_name IN WorkTasks.PIPELINE_SCOPED` and the `name#` prefix: the same task set and prefix `BackpressureGate` counts, through `InflightCounter`'s escaped `LIKE`. So `computing + awaiting_write` is exactly the backpressure depth. It covers live work and every requeued item, including a requeued backfill item (`name#requeue#bf#…`). It excludes a backfill run's own chunks (`#bf#…`), which no pipeline prefix can match. |
| `queue.computing` | `enrich-chunk` and `enrich-group` rows: compute-phase items, queued or running |
| `queue.awaiting_write` | `upsert-rows` rows |
| `groups.*` | One aggregate `SELECT … FILTER (WHERE …)` over `group_state` for the pipeline, using the dirty and readiness fragments `GroupStateWriter` already composes, so the count matches what the sweep would select. The `Readiness` value is built by one factory shared with `GroupReadinessSweep`, which needs the resolved idle default, max staleness, signal and the UTC day. With pending = dirty (`computed_version IS NULL OR group_version > computed_version`): `ready` = pending ∧ readiness ∧ within ceiling; `ceiling_blocked` = pending ∧ readiness ∧ over ceiling (0 when no ceiling is declared); `waiting_idle` = pending ∧ ¬readiness. So `pending = ready + ceiling_blocked + waiting_idle`. A never-computed group can be ready only by idle or signal, as in the sweep. `ready` ignores the sweep's batch size and its in-flight suppression, so it counts groups already enqueued. The ceiling check uses the UTC day on the runner's `Clock`, the same day `SpendService` now reads (it used `LocalDate.now` before). |
| `spend_today` | `SpendService.today()`, extended with `failures`. `calls` counts successful calls only (`SpendService.record` books a failed call under `failures`, not `calls`), and the gate compares `calls`, so `failed_calls` is never charged to the budget. |
| `failures` | The existing `DlqItemRepository.counts(pipeline, null, "live")`, the query behind the DLQ listing's totals, over its own `LISTING_FILTER`. So `total` and `requeueable_total` equal what `GET /v1/dlq?pipeline_name=…&lane=live` reports under the same names. No new DLQ query. |

### Errors
- Unparseable declaration: a readiness window that does not parse leaves out only the `groups` section. A cadence
  that does not parse only makes the heartbeat's stall rule treat the cadence as unknown, and the view does not
  report that rule (see "Trimmed to what the console needs"). The read itself does not fail. The pipeline's own fires and sweeps
  already fail on it, and this view is where an operator looks to find out why.
- Cold cache: checked first. `!pipelineCache.isPopulated()` → `PipelineCacheNotReadyException` → the existing 503
  `pipeline_cache_cold`, the same check `BackfillService` makes. `/groups` does not make this check and answers 404
  on a cold cache. The runtime view must not copy that, or a boot would report every pipeline as missing.
- Unknown pipeline (populated cache, no entry, including one refused at sync): `PipelineNotFoundException` → 404
  `not_found`, the same as `/groups`.
- No new error codes.

### The paused-pipeline listing moves to snake_case (**BREAKING** for its clients)
`PausedPipelineDto` gains `@JsonProperty("pipeline_name")` and `@JsonProperty("resumes_at")`. `origin`, `reason`
and `since` are single words and do not change. After this, every `/v1` response uses snake_case, and one pause reads
the same in `/v1/pipelines/paused` and in the runtime view's `status`.

The tie-breaker is that the docs disagree with each other. `docs/e2e-runbook.md` already documents `resumes_at`.
`docs/release-0.2-upgrade.md` documents the camelCase that 0.2.x actually emits, and also documents `origin` as
`OPERATOR` and `BREAKER`, which the code never wrote (`PipelineAdminController` lowercases it).

Judged against the code at the time of this change, nothing in this repository or the admin UI parses the listing: the only test that
calls it checks authentication, not the body. A client that scripted against 0.2.x's camelCase (an operator's `jq`)
has to rename two keys. The release notes of the version that ships this change must say so.

*Alternative:* keep camelCase and fix the runbook instead. Rejected: it would leave the one camelCase response in
the API, and the runtime view would have to choose between matching it and matching every other DTO.

### No list endpoint
The per-pipeline read is at most eight small queries: the cursor, the recurring row, the queue `GROUP BY`, the group
aggregate (group lane), today's spend, the DLQ counts, plus the resolver's gate reads (spend and backpressure). A
Class-1 read skips the queue, group, spend and DLQ queries. That is fine for one pipeline's page. A table across N
pipelines would need every query batched by `pipeline_name`. That is deferred until the console asks for it.

### Trimmed to what the console needs
The first cut also carried `status.stalled`, `queue.retrying`, `spend_today.budget`, `failures.by_stage`,
`failures.last`, a `backfill` section, a `settings` section, and `groups.tracked`, `oldest_pending_at`,
`last_evaluated_at` and `evaluations_today`. They were removed before release:
- **No consumer yet.** The console has no Runtime tab today. Its "Runtime state" block in a pipeline's Properties
  shows ADAS's `state`, which is empty for `enrich` pipelines. The view is shaped for the tab the console will build,
  and that tab needs the state, the schedule, the progress and the counts that explain them.
- **Already served elsewhere.** Dead letters are listed by `GET /v1/dlq?pipeline_name=…&lane=live`, and backfill
  runs by `GET /v1/backfills?pipeline_name=…&state=…`. The console links there instead of reading a copy. The
  remaining `failures` totals use the listing's own count query, so the link and the number agree.
- **Echoes the declaration.** `settings` mostly repeated the ADAS declaration the console already shows. Its only
  runner-side content was default resolution, and that alone did not justify a section the UI would have to keep in
  step with every new setting.
- **No health verdict.** The console's spec forbids the UI from rendering a health verdict, and `stalled` is a
  heuristic (no write for three cadences while lagging), not a state. It stays in the heartbeat's log line, which
  still computes and logs it.
- **Easy to misread, or derivable.** `retrying` is a subset of `computing` and `awaiting_write`, and reads as a
  third bucket. `budget` is runner-wide configuration repeated on every pipeline, encoding three cases by absence
  (gate off, gate on with no caps, an unlimited cap). Of the group figures, `evaluations_today` nearly repeats
  `spend_today.calls`, `oldest_pending_at` is last activity rather than "pending since", and `tracked` and
  `last_evaluated_at` answer no question an operator acts on.
- **Removing is breaking; adding is not.** Once the console parses a field, dropping it is a breaking change, while
  adding one later is not. Starting small keeps the contract cheap to grow.

Code reverted as a consequence: `DialProperties.resolveRpm` (the limiter resolves the rate inline again),
`Sampler.effectiveFraction`, `DlqStageCounts` with `DlqItemRepository.countsByStage`, and
`RunHistoryRepository.countByPipelineNameAndStateIn`. Kept, because they stand on their own: `ScheduleFactory.cadence`
throwing on a blank cron (the heartbeat's stall rule uses it; see "Status from a shared resolver"), `SpendService` reading the injected `Clock`, and
the `Readiness` factory in `GroupReadinessSweep` that the `groups` counts share with the sweep.

## Risks / Trade-offs

- **[Restart blanks in-memory fields]**: `last_error`, `has_more`, `caught_up_at` and `last_write_at` are absent for
  up to one cadence; a pause is lost entirely. → The fields are documented as "since process start".
  `consecutive_failures` and `last_scan_at` are durable and still show a failing or idle pipeline, and
  `last_error_at` falls back to the durable `last_failure` (see "Field sources"), so only the error message is lost. Pause persistence
  stays a separate decision.
- **[Lag looks healthy while the LLM queue is deep]**: the cursor advances at enqueue. → `queue` is always present
  for the LLM lanes, and the spec says lag alone does not mean written.
- **[Error text could leak source content]**: → `last_error` is `ErrorMessages.classify(e)`, the same text the logs
  already carry under the never-log-content posture. Dead letters appear only as counts, so no item field reaches
  the view.
- **[Query cost on a hot group table]**: the aggregate visits every one of the pipeline's `group_state` rows, through
  its pipeline-leading index. → It runs only on an explicit read and never on a clock.
- **[Poison groups look clean]**: a poison group is stamped computed. → `failures` counts it; `groups` does not. A
  group archived after exhausting its retries is not stamped. It stays pending and the sweep re-enqueues it, so it
  is counted under both. The spec states both cases so the UI does not promise "failed groups" under `groups`.
- **[Backfill writes inflate `last_write_at`]**: it includes a backfill run's upserts on the LLM lanes. → It is the
  same input the heartbeat's stall rule uses. The spec says "last write", not "last live write".
- **[The console wants a dropped field later]**: → Adding a field is not breaking (see "Trimmed to what the console needs"), so it can return when a
  screen needs it, with its source already described in this change's history.

## Migration Plan

No schema and no config. Rollback = deploy the previous image. Nothing persisted depends on the new code.

The pause listing's two renamed keys (see "The paused-pipeline listing moves to snake_case") are the only break. They go in the next release's upgrade notes with a
before/after example, in the same style as the 0.1.2 → 0.2 entry in `docs/release-0.2-upgrade.md`.

## Resolved Questions

- **`failures` is live-only.** Its totals match the console's link to `GET /v1/dlq?pipeline_name=…&lane=live`. A
  backfill's failures belong to its run (`GET /v1/dlq?run_id=…`).
- **The admin UI calls the runner from its server, not from the browser.** No CORS configuration is needed. The
  admin UI's server forwards the operator's `FULL_ADMIN` credential like any other `/v1` client.
- **After a restart, `last_error_at` falls back to `scheduled_tasks.last_failure`** while `consecutive_failures > 0`
  (see "Field sources"). A failing pipeline therefore never reads as cleared. Only the message is lost until the next fire.
