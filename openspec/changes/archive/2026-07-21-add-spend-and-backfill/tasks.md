## 1. Spend

- [x] 1.1 `SpendProperties` (pause.enabled, budget.daily-calls/daily-tokens) + `application.yml` + docs
- [x] 1.2 `SpendService` (UPSERT increment + daily totals) + `DailySpend`
- [x] 1.3 `SpendPauseGate`; `WorkerService` records spend per DIAL call; `RuleScanExecutor` gate

## 2. Priority + refactor

- [x] 2.1 `DbSchedulerConfiguration.enablePriority()`; `EnrichChunkTask` priority lanes
- [x] 2.2 Extract `ChunkBuilder`/`ChunkBatch`; `RuleScanExecutor` uses it; `ScanPageCommitter` enqueues
  at live priority; `DeterministicId` backfill-namespaced id

## 3. Backfill engine

- [x] 3.1 `RunHistoryEntity` + repo; `RunKind`/`RunState`
- [x] 3.2 `BackfillPageCommitter` (enqueue backfill-priority chunks + advance run cursor/rows)
- [x] 3.3 `BackfillTask` (self-continuing one-time) + `BackfillExecutor` (drain batch)
- [x] 3.4 `BackfillService` (start/pause/resume/cancel/get); register task in `DbSchedulerConfiguration`

## 4. Backfill web API

- [x] 4.1 `BackfillRunDto` + `BackfillRunMapper`
- [x] 4.2 `BackfillController` (start / get / pause / resume / cancel); `BackfillRunNotFoundException` + handler

## 5. Verification

- [x] 5.1 Unit: `SpendPauseGate`
- [x] 5.2 Integration: `SpendService` increments/totals; backfill drains → backfill-priority chunks +
  run cursor + done, paused run does not drain (Testcontainers PG + WireMock ADAS)
- [x] 5.3 `./gradlew clean build` passes
- [x] 5.4 `openspec validate add-spend-and-backfill --strict` passes
