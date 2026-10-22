## Why

The admin console can show only what ADAS reports in a pipeline's `state` (the "Runtime state" block in its
Properties). For an `enrich` pipeline, ADAS deliberately reports almost nothing, because the runner owns the
execution position. The runner, in turn, has no per-pipeline read of its own state. Whether a pipeline is active, over budget, backpressured or stalled
is written only to the status heartbeat log. Lag, the next fire, queued work, group readiness and spend are in
Postgres or in metrics, where the UI cannot reach them. A SQL pipeline's failure is visible only in logs, since that
lane has no DLQ. So the console cannot answer "what is this pipeline doing right now?" for any lane the runner runs.

## What Changes

- New read-only endpoint `GET /v1/pipelines/{name}/runtime`. It returns one per-pipeline view whose shape is the same
  for all three lanes. A section that does not apply to the pipeline's lane is omitted rather than zero-filled:
  - `status`: `active`, `paused`, `held`, `over_budget` or `backpressured`, with the pause's origin, reason, since
    and resumes_at. No `stalled` flag: the heartbeat keeps computing and logging it, but it is a heuristic, and the
    console must not render a health verdict.
  - `schedule`: last scan, next run (or running now), consecutive failures, and the last error with its time.
  - `progress`: lag, `has_more`, the time the scan last caught up, and the last write.
  - `queue` (LLM lanes): items `computing` and `awaiting_write`. These are the same items backpressure counts.
  - `groups` (group lane): `pending`, `ready`, `waiting_idle` and `ceiling_blocked`.
  - `spend_today` (LLM lanes): successful `calls`, `tokens` and `failed_calls`.
  - `failures` (LLM lanes): the live DLQ's `total` and `requeueable_total`, as `/v1/dlq` names and counts them.
    The items stay on `GET /v1/dlq?pipeline_name=…&lane=live`.
- No `backfill` or `settings` section: backfill runs are already served by `GET /v1/backfills?pipeline_name=…&state=…`,
  and the settings mostly echo the ADAS declaration. The view is trimmed to what the console needs (design: "Trimmed to what the console needs").
- The status verdict moves out of `PipelineStatusReporter` into a shared resolver. The heartbeat and the endpoint
  then derive it from one place and cannot disagree. The heartbeat's output does not change.
- Each run's outcome is recorded in memory: `has_more`, caught-up time, last error and last error time. This sits
  next to the existing in-memory `PipelineActivity`, with no schema change. The runner is a single replica. After a
  restart these fields are absent until the pipeline's first run. The exception is `last_error_at` of a pipeline that
  is still failing: it falls back to db-scheduler's durable `last_failure`, so a failing pipeline never reads as
  recovered.
- **BREAKING:** `GET /v1/pipelines/paused` renames `pipelineName` → `pipeline_name` and `resumesAt` → `resumes_at`.
  It was the only `/v1` response not in snake_case, and the runbook already documents `resumes_at`. No client in this
  repository or the admin UI parses it, but operator scripts written against 0.2.x must rename the two keys.
- A pipeline that is not in the runner's cache gets 404 `not_found`, the same as the group-state endpoint. A cache
  that has never synced gets the existing 503 `pipeline_cache_cold`, checked before the lookup. The group-state
  endpoint does not make that check.
- Pause state stays in memory as it is today. The endpoint reports the pause the runner is enforcing.
- Doc fixes in `docs/configuration.md`:
  - A breaker pause expires; it does not wait for an operator.
  - Spend is keyed by pipeline, generation and day, not by evaluator.
  - The daily call budget counts successful calls; failed calls are counted separately and not charged.
- Doc fix in `docs/release-0.2-upgrade.md`: `origin` is `operator` or `breaker` (lowercase), not `OPERATOR` or
  `BREAKER`.
- Apart from the pause listing's two keys, nothing existing changes: no other endpoint, no metric, no log line.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `runner-ops`: adds a requirement that the runner serve a per-pipeline runtime view across all three lanes, with
  lane-specific sections omitted when they do not apply. Also adds requirements on which fields are durable and which
  are held in memory, and on the view carrying no source content. Adds a requirement that the paused-pipeline
  listing uses snake_case.

## Impact

- **API:** new `GET /v1/pipelines/{name}/runtime`, guarded by the same `FULL_ADMIN` rule as every `/v1` endpoint.
  The admin UI's server calls it directly, so no ADAS change and no CORS configuration are needed.
  `GET /v1/pipelines/paused` renames two response keys (**BREAKING**, see above).
- **Code:**
  - New `runtime/` package: `PipelineRuntimeService`, `PipelineRunOutcome` (in memory) and `PipelineStatusResolver`
    (extracted from `PipelineStatusReporter`).
  - New `PipelineRuntimeController` and `web/dto/runtime/*`.
  - Hook points for the run outcome: `PipelineScanExecutor`, `Class1MaterializeExecutor`, and its `retain` on every
    successful sync in `PipelineSyncService`.
  - New read queries: `SchedulerQueueReader` (the recurring row), `InflightCounter` (queue counts per task under
    the pipeline's prefix), `GroupStateWriter` (aggregate counts reusing the readiness predicate, with a `Readiness`
    factory in `GroupReadinessSweep` shared with the sweep) and `SpendService` (failed calls, on the injected
    `Clock`). The failure totals reuse the existing `DlqItemRepository.counts`.
  - `ScheduleFactory.cadence` throws for a schedule pipeline without a cron, as `forPipeline` does, so the
    heartbeat's stall rule treats its cadence as unknown instead of using the default delay.
- **Schema:** none.
- **Config:** no new properties.
- **Docs:** `docs/configuration.md` (three corrections), `docs/release-0.2-upgrade.md` (`origin` casing), the
  next release's upgrade notes (the pause listing's renamed keys), a runtime-view example in `docs/e2e-runbook.md`, and the
  new path in the ownership table of `docs/kubernetes-exposure.md`. ADAS also serves `GET /v1/pipelines/{name}`,
  so a bare path does not say which service answers it.
