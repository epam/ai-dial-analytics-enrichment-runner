## Why

ADAS is folding its `rule` registry into its `pipeline` registry as the `ENRICH` kind (the existing class-2 enrichment `rule` *is* the `enrich` kind — generalised, not duplicated by a parallel subsystem). This runner is the only out-of-process consumer of `/v1/rules`, and ADAS deletes that surface in the same release with no alias and no deprecation window, so the runner must move with it or stop syncing.

The fold also changes the **identity** the runner keys everything on, from a UUID to a name. Five state tables (`rule_cursor`, `run_history`, `dlq_item`, `spend_counter`, `group_state`) and every db-scheduler `task_instance` embed it, which is what makes this a coordinated deploy with a data migration and a maintenance window rather than a refactor.

## What Changes

- **BREAKING (upstream contract).** The ADAS client moves to `/v1/pipelines`: the sync feed becomes `GET /v1/pipelines?kind=enrich&enabled=true` and returns the `{"pipelines":[…]}` **envelope** instead of a bare array; `scan`, `group-rows`, `materialize` and `rows` re-path to `/v1/pipelines/{name}/…` with bodies and responses unchanged. `RuleDto` becomes `PipelineDto` — `id` removed, `trigger_kind`/`trigger_cron`/`group_by`/`ready_when` nested under `trigger`, `target_enrichment`→`target`, `source`→`inputs`, `filter_sql`→`filter`, and seven verified-unread fields dropped along with `RulePriority`.
- **BREAKING (this service's own API).** `/v1/rules/cache`, `/v1/rules/paused`, `/v1/rules/{id}/pause|resume`, `/v1/rules/{id}/groups[/{key}/requeue]` and `/v1/rules/{id}/backfill` become `/v1/pipelines/…` with `{name}`. `GET /v1/dlq?rule_id=` and `POST /v1/dlq/requeue?rule_id=` become `?pipeline_name=`, and the `rule_id` JSON property on `DlqItemDto` and `BackfillRunDto` becomes `pipeline_name`. The pipeline-cache entry **loses its `id` field** — one identity remains.
- **BREAKING (persistence).** `V8__rekey_and_discard_runner_state.sql` renames `rule_cursor`→`pipeline_cursor` and `rule_id`→`pipeline_name` across all five tables with their indexes and constraints, then **truncates them** and clears `scheduled_tasks`. The values are not carried across: the id→name mapping lives in ADAS's catalog and the runner's Flyway cannot read it, so keeping them would mean an out-of-band operator step wedged between the two deploys. Every enabled pipeline therefore re-derives its target from the start of its source once — inside ClickHouse and free of model calls for a Class-1 lane, and a real evaluation per group for a group lane, which is why group pipelines should stay disabled through the window. The cut-over quiesces by **stopping the runner**, never by disabling pipelines in ADAS: `enabled` bumps `generation`, which makes ADAS discard the Class-1 mark that dependent rollups clamp against.
- **BREAKING (configuration).** `adas.rules.poll-interval-ms` / `adas.rules.initial-delay-ms` → `adas.pipelines.*` (env `ADAS_RULES_*` → `ADAS_PIPELINES_*`); the health contributor bean `ruleSync` → `pipelineSync`, in lockstep with the readiness group's `include` list.
- **Additive (configuration), one deliberate scope addition.** New `scan.max-scan-limit` (`SCAN_MAX_SCAN_LIMIT`, default `10000`, mirroring ADAS's `ENRICHMENT_SCAN_MAX_LIMIT`) caps a pipeline's effective `batch_scan_limit`, the way `class1.max-limit` already caps the materialize window. ADAS refuses an over-limit scan with a `400` and does not validate the knob at registration, so a pipeline declaring a `batch_scan_limit` above ADAS's ceiling was accepted by the registry and then failed on **every** fire — a scan lane that never advanced, on the one of the two lanes that lacked the guard. Found by the cross-service consistency check, not by the fold itself.
- **BREAKING (observability).** Gauges `runner.rules.{cached,paused,unsupported}` → `runner.pipelines.*`; the `rule=` tag on `runner.spend.calls.today`, `runner.scan.lag.seconds` and `runner.class1.rows.written` → `pipeline=`; MDC `rule_id` → `pipeline_name`; span attribute `rule.id` → `pipeline.name` in seven places.
- db-scheduler task `rule-scan` → `pipeline-scan`. `class1-materialize`, `enrich-chunk`, `enrich-group`, `upsert-rows` and `backfill-run` keep their names — an ENRICH pipeline still enriches.
- `DeterministicId`'s instance-id prefix becomes `pipelineName#`. The runner now **depends on ADAS validating** `^[a-z][a-z0-9_-]{0,63}$` at registration: the `#` exclusion is what keeps `a#b` and `a` from sharing a prefix in `InflightCounter`'s `LIKE` scan. This is written into the spec on the side that relies on it, and deliberately **not** re-validated here — a second validator would drift.
- 16 `Rule*` classes renamed, `RulePriority` deleted with its field, ~25 operator-facing log messages re-worded. No package moves.
- **No behavior change is intended anywhere.** The three execution lanes and their routing, the evaluator registry, the group prompt-variable contract and its truncation policy, DIAL invocation, DLQ, circuit breaker, spend accounting, backfill semantics, at-least-once execution / exactly-once effect and the single-replica topology are all untouched.

## Capabilities

### New Capabilities
- `pipeline-sync`: syncing the enabled `enrich`-kind pipeline feed from the ADAS control plane, reconciling recurring per-pipeline tasks, and admitting only pipelines the runner can execute. Supersedes `rule-sync`.

### Modified Capabilities
- `rule-sync`: **REMOVED** as a capability — all three requirements are retired and superseded by `pipeline-sync`. The runner syncs pipelines of kind `enrich`, not rules.
- `incremental-scan`: the backpressure/lifecycle guard and the instance-prefix requirement re-keyed to the pipeline name; the prefix requirement gains the `#`-charset dependency on ADAS.
- `class1-enrichment`: routing by evaluator type re-keyed to pipelines; the materialize call re-paths to `/v1/pipelines/{name}/materialize`.
- `enrichment-worker`: pipeline-scoped upsert of a chunk's rows, and the stale-evaluator / pipeline-lifecycle guards.
- `group-enrichment`: group state, readiness sweep and deterministic group identity re-keyed to the pipeline name; the `#`-delimiter dependency stated where deterministic identity is specified.
- `resilience-and-dlq`: per-pipeline DIAL rate limiting, the DLQ circuit breaker's pause and resume, and the `/v1/dlq` `pipeline_name` parameter and DTO property.
- `spend-tracking`: spend counters keyed on `pipeline_name`.
- `backfill`: backfill runs keyed on `pipeline_name`, the trigger path, and the `BackfillRunDto` property.
- `runner-ops`: readiness gated on a successful **pipeline** sync; pipeline-cache introspection at `/v1/pipelines/cache`, whose entry no longer carries `id`.
- `observability`: Class-1 throughput attributable per pipeline; the gauge, tag, MDC and span-attribute renames.
- `service-runtime`: the five renamed PostgreSQL runtime-state tables and columns.
- `api-authentication`: the enumerated `/v1/rules/**` paths in the full-admin authorization and public-endpoint requirements.

## Impact

- **Upstream contract:** ADAS `/v1/rules` is deleted in the same release. The two services deploy in one maintenance window; there is no version in which the old and new runner both work.
- **Code:** `service/adas/` (5 clients + DTOs), `sync/`, `scan/`, `class1/`, `worker/`, `sweep/`, `dlq/`, `spend/`, `backfill/`, `persistence/`, `observability/`, `pause/`, `web/` — 16 class renames plus a mechanical pass through the call graph. Three hand-written SQL blocks (`GroupStateWriter`'s 12 constants, `SpendService`'s upsert and `DAILY_TOTALS`, `InflightCounter`'s `LIKE` scan) move with the columns and bypass JPA entirely.
- **Data:** the runner's five state tables are emptied by the migration. A Class-1 pipeline therefore re-derives from the beginning of its source (e.g. `dial_usage_log`) — inside ClickHouse, with no model call — and a group lane would re-evaluate every group at real model cost, which is why group pipelines should stay disabled through the window and be enabled afterwards only as a separate, costed decision. **Rollback is a database restore, not a migration.**
- **Deployment:** env vars `ADAS_RULES_*` → `ADAS_PIPELINES_*` must change in the same rollout, or the poller silently falls back to its defaults.
- **Tests:** ~30 classes — 20 of them register the two `adas.rules.*` keys via `@DynamicPropertySource` to park the poller, and would silently start polling ADAS mid-suite if the key moved without them.
- **Dashboards, alerts and log queries** break silently rather than loudly; every renamed metric, tag and MDC field is enumerated in the design's review checklist.
- **Docs:** `docs/configuration.md`, `docs/e2e-runbook.md` (whose re-keying appendix becomes the discard choreography), `docs/kubernetes-exposure.md`, `README.md`, `CLAUDE.md`, and `openspec/config.yaml`'s ADAS integration contract.
