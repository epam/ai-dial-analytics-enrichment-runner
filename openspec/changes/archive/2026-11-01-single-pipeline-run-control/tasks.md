## 1. Sync

- [x] 1.1 `AdasRegistryClient`: request `GET /v1/pipelines?kind=enrich&view=compiled` (no `enabled` filter)
- [x] 1.2 `PipelineSyncService` / `PipelineCache`: admit `enabled: false` entries as stopped (same admission gate); expose `isStopped(name)`; detect a change of `enabled` as well as of `generation`
- [x] 1.3 Reconciliation: cancel a stopped pipeline's recurring instance as for an absent one, keep it cached, keep persisted state; evict its per-pipeline in-memory gate state and gauges
- [x] 1.4 Re-create reset: drop the "operator pause kept" branch; a breaker pause is still lifted
- [x] 1.5 Tests: unfiltered request; stopped entry cached and unscheduled; a flag-only change (same generation) detected both ways; refused stopped entry stays absent

## 2. Stop gate

- [x] 2.1 Scan, readiness sweep, Class-1 materialize and backfill executors: treat a stopped pipeline like a paused one at every per-fire, per-page and per-window check
- [x] 2.2 `WorkerService`, `GroupWorkerService`, `UpsertService`: a stopped pipeline throws the held exception before any model call, and the upsert lane re-checks before the write; absent keeps today's drop/archive
- [x] 2.3 `UpsertService`: a `409` whose body's `error` is `pipeline_conflict` throws the held exception — no dead letter, no group stamp, no breaker record; `Class1MaterializeExecutor`: the same answer on `…/materialize` ends the tick without recording a failure
- [x] 2.4 Tests: a chunk, a group item and an upsert queued for a stopped pipeline are held without an attempt being consumed and run after re-enable at the same generation; a declaration edit while stopped still drops the chunk as stale — the re-enable case is pinned on the write lane; the chunk and group lanes pin the hold, and run through the same held-item path

## 3. Pause

- [x] 3.1 Remove `POST /v1/pipelines/{name}/pause` and `PauseOrigin.OPERATOR`; `PipelinePauseRegistry` holds breaker pauses only
- [x] 3.2 `POST /v1/pipelines/{name}/resume`: lift a breaker pause only; `409 pipeline_not_paused` (new `DefaultExceptionHandler` mapping) when none; keep the operator-action log line with the principal
- [x] 3.3 `GET /v1/pipelines/paused` unchanged in shape (`origin` always `breaker`)
- [x] 3.4 Tests: resume of a breaker pause; resume of an unpaused pipeline answers 409; `…/pause` answers 404

## 4. Backfill

- [x] 4.1 `V14__backfill_paused_reason_stopped.sql`: extend `run_history_paused_reason_check` with `pipeline_stopped` (one-line header linking `openspec/specs/backfill/spec.md`); check no in-flight branch claims V14
- [x] 4.2 `BackfillPauseReason.PIPELINE_STOPPED`; executor pauses with it on a stopped pipeline; `pipeline_absent` only for absent; `pipeline_paused_operator` never written
- [x] 4.3 `BackfillService`: start/resume on a stopped pipeline → `409 backfill_conflict` naming the stop
- [x] 4.4 Tests: stop pauses a running backfill with `pipeline_stopped`; restart leaves it paused; resume after restart drains from the committed cursor — the stop and its persisted reason are pinned; resume-and-drain relies on the existing resume tests, not a stop-specific one

## 5. Runtime and observability

- [x] 5.1 `PipelineStatusResolver` / runtime view: `stopped` state with highest precedence; 200 for a stopped pipeline
- [x] 5.2 `GET /v1/pipelines/cache` lists stopped pipelines with `enabled: false`
- [x] 5.3 `RunnerMetrics`: `runner.pipelines.stopped`; `cached` includes stopped; both paused gauges count breaker pauses
- [x] 5.4 `PipelineStatusReporter`: stopped line at INFO; breaker-paused stays WARN
- [x] 5.5 Tests for each of the above

## 6. Docs

- [x] 6.1 `docs/e2e-runbook.md`: replace pause/resume procedures with ADAS `enabled`; rewrite the disable/absent and breaker sections
- [x] 6.2 `docs/kubernetes-exposure.md`: drop the `…/pause` route
- [x] 6.3 `docs/configuration.md`: breaker paragraph — resume lifts a breaker pause; no operator pause
- [x] 6.4 Upgrade note in the release that ships this: deploy after ADAS; convert operator pauses to `enabled: false` first; `…/pause` removed; `…/resume` 409 semantics; runtime view 200 for stopped
- [x] 6.5 OpenAPI descriptions in `PipelineAdminController` and `OpenApiConfiguration` — the admin controller carries Javadoc only; no OpenAPI text named the pause

## 7. Verification

- [x] 7.1 `openspec validate single-pipeline-run-control --strict`
- [x] 7.2 `./gradlew clean build`
