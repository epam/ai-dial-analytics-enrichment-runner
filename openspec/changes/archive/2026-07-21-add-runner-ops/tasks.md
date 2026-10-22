## 1. Metrics

- [x] 1.1 `RunnerMetrics`: gauges (rules cached, DLQ size, queue depth, per-rule scan lag), DIAL
  timer + success/error counters, validation-failure + rows-upserted counters
- [x] 1.2 Wire into `WorkerService` (DIAL timing, validation, upsert) and `RuleScanExecutor` (lag)

## 2. Readiness

- [x] 2.1 `ReadinessState` + `RuleSyncHealthIndicator` (`ruleSync`); `RuleSyncService` marks ready
- [x] 2.2 `application.yml`: probes enabled + readiness (`db`, `ruleSync`) / liveness groups

## 3. Introspection & logging

- [x] 3.1 `GET /v1/rules/cache` (`RuleCacheResponse`/entry DTO + `RuleCacheMapper`)
- [x] 3.2 Chunk-boundary structured log + MDC correlation (`rule_id`, `chunk_id`); `process(chunkId, payload)`

## 4. Runbook & verification

- [x] 4.1 `docs/e2e-runbook.md`
- [x] 4.2 Ops integration test: rule-cache endpoint, Prometheus runner metric, readiness UP after sync
- [x] 4.3 `./gradlew clean build` passes
- [x] 4.4 `openspec validate add-runner-ops --strict` passes
