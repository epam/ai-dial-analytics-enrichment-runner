## Why

A pipeline can be stopped in two places: ADAS's `enabled` flag, and this runner's operator pause. A UI user
sees two buttons that both "stop the pipeline", with no way to know when to choose which, and a pipeline can
read as running in ADAS while the runner has it paused. The two also behave differently in ways a user should
not have to know about:

- **Disabling loses work.** A disabled pipeline drops out of the `enabled=true` feed, which the runner cannot
  tell apart from a deletion. It cancels the pipeline's tasks, drops its queued chunks for good (their rows'
  cursor has already advanced), drops its queued group items, and archives already-paid writes to the DLQ.
- **Pausing is not durable.** An operator pause lives only in this process's memory. A restart clears it,
  ADAS cannot see it, and it survives a disable/enable and even a delete and re-create under the same name.

ADAS makes `enabled` the pipeline's single run control (its change `single-pipeline-run-control`). This
change makes the runner follow that control, keep a stopped pipeline's work, and stop offering a pause of
its own.

## What Changes

- **Sync follows `enabled`.** The poll requests the unfiltered `GET /v1/pipelines?kind=enrich&view=compiled`.
  Entries carrying `enabled: false` are admitted and cached as **stopped**. A pipeline absent from the feed
  is deleted or refused, and is handled as an absent pipeline is today. A change of `enabled` is detected by
  comparing the flag, since ADAS no longer moves `generation` on a flag-only patch.
- **Stopping keeps work.** A stopped pipeline gets no scan, materialize or readiness-sweep work, and a
  multi-page drain or a Class-1 window stops at its next gate check. Its queued chunk, group and upsert items
  are **held**: rescheduled without using an attempt and never archived. They run once the pipeline is
  re-enabled. The cursor, group state, DLQ and spend are kept.
- **BREAKING (admin API): operator pause is removed.** `POST /v1/pipelines/{name}/pause` is removed.
  `POST /v1/pipelines/{name}/resume` stays, but it only lifts a circuit-breaker pause and answers
  `409 pipeline_not_paused` when the pipeline is not breaker-paused. `GET /v1/pipelines/paused` lists
  breaker pauses only, and keeps its `origin` field.
- **Backfill:** a run whose pipeline is stopped pauses with a new reason, `pipeline_stopped`. Starting or
  resuming a run on a stopped pipeline answers `409 backfill_conflict`. `pipeline_absent` now means deleted
  or refused only. `pipeline_paused_operator` is no longer written.
- **Runtime and ops views:** `GET /v1/pipelines/{name}/runtime` answers 200 with `status.state: stopped` for
  a stopped pipeline, instead of 404. `GET /v1/pipelines/cache` lists stopped pipelines with
  `enabled: false`. A new gauge, `runner.pipelines.stopped`, is added, and `runner.pipelines.cached` now
  includes stopped pipelines. Per-pipeline gauges are evicted when a pipeline stops. The status heartbeat
  reports a stopped pipeline at INFO.
- **Migration:** Flyway `V14` adds `pipeline_stopped` to the `run_history.paused_reason` check.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `pipeline-sync`: an unfiltered feed, stopped pipelines cached and their tasks cancelled, flag changes
  detected without `generation`, and no operator pause kept across a re-create reset.
- `resilience-and-dlq`: the breaker is the only source of a pause, and resume lifts a breaker pause only.
- `runner-ops`: cache introspection, runtime view `stopped` state, paused listing, operator-action logging,
  metrics, single-replica wording.
- `backfill`: the `pipeline_stopped` reason and its start/resume conflict, and `pipeline_absent` narrowed.
- `enrichment-worker`: a stopped pipeline's work items are held, not dropped or archived.
- `group-enrichment`, `incremental-scan`: the stop gate on sweeps and drains.
- `observability`: the stopped gauge, gauge eviction on stop, the paused gauges' meaning, and heartbeat level.
- `service-runtime`: the paused-reason set gains `pipeline_stopped`.

## Impact

- **Admin API (breaking)**: `POST /v1/pipelines/{name}/pause` is gone. `…/resume` answers 409 when there is
  no breaker pause. The runtime view no longer answers 404 for a stopped pipeline. Tools and UIs that call
  pause must switch to ADAS `PATCH /v1/pipelines/{name}` with `enabled`.
- **ADAS contract**: unchanged. The runner contract version does not move, and the feed request only drops
  a filter.
- **Code**: `AdasRegistryClient`, `PipelineSyncService`, `PipelineCache`, `PipelinePauseRegistry` and
  `PauseOrigin`, `PipelineAdminController`, the scan, sweep, Class-1 and backfill executors, the three
  work-item services and their failure handlers, `PipelineStatusResolver` and the runtime view,
  `RunnerMetrics`, `PipelineStatusReporter`, and `BackfillPauseReason`.
- **Migration**: `V14__backfill_paused_reason_stopped.sql`. It is additive, and rollback-safe because the
  old image never writes the new value.
- **Docs**: `docs/e2e-runbook.md` (pause/resume, the disable section and the breaker section),
  `docs/kubernetes-exposure.md` (routes), `docs/configuration.md` (breaker paragraph), and the upgrade notes.
- **Deploy order**: ADAS first. Against an older ADAS a stop/start still bumps `generation`, so held work is
  dropped as stale on restart, which is no worse than today.
