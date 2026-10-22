## Why

The runner is functionally complete but operationally opaque: there are no runner-specific metrics,
the readiness probe does not reflect whether rules have loaded, and there is no way to see what the
runner has cached. This change adds the operations surface:
metrics, a rule-sync readiness gate, rule-cache introspection, and structured chunk-boundary logging,
plus a local end-to-end runbook.

## What Changes

- **Metrics** (`RunnerMetrics`, Prometheus): gauges for rules cached, DLQ size, enrich-chunk queue
  depth, and per-rule scan lag; a timer for DIAL latency with success/error counters; counters for
  validation failures and enrichment rows upserted.
- **Readiness gate**: a `ruleSync` health contributor (in the readiness group alongside `db`) that is
  UP only after the first successful ADAS rule sync; liveness stays process health. Kubernetes probes
  enabled.
- **Rule-cache introspection**: `GET /v1/rules/cache` lists the cached rules (id, name, evaluator
  version, trigger, enabled, generation).
- **Structured chunk-boundary logging**: the worker logs at chunk boundaries and sets MDC correlation
  (`rule_id`, `chunk_id`) around chunk processing; full payloads are never logged at INFO.
- **Runbook**: `docs/e2e-runbook.md` — register evaluator + rule, seed rows, run, verify the
  enrichment table populates and reads back joined, and check idempotency and the ops paths.

## Capabilities

### New Capabilities
- `runner-ops`: the operational surface — Prometheus metrics, the rule-sync readiness gate, and
  rule-cache introspection.

### Modified Capabilities
<!-- none: the DLQ/backfill admin endpoints already shipped in `add-resilience-and-dlq` and `add-spend-and-backfill` -->

## Impact

- New code: `observability.*` (`RunnerMetrics`, `ReadinessState`, `RuleSyncHealthIndicator`),
  `web/dto/admin` rule-cache DTOs + `RuleCacheMapper`, `GET /v1/rules/cache` on `RuleAdminController`.
- `WorkerService` records DIAL/validation/upsert metrics + chunk-boundary MDC logging (and its
  `process` now takes the chunk id); `RuleScanExecutor` records per-rule lag; `RuleSyncService` marks
  readiness.
- `application.yml`: health probes enabled + readiness/liveness groups. No new migration.
