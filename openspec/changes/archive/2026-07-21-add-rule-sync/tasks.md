## 1. Dependencies & config

- [x] 1.1 Add `db-scheduler:15.6.0`, explicit `jackson-databind`, test `wiremock-standalone`
- [x] 1.2 Add `AdasProperties`, `DbSchedulerProperties`; `adas.*`/`db-scheduler.*`/`scan.*` in
  `application.yml`; document all in `docs/configuration.md`

## 2. ADAS registry client

- [x] 2.1 Add read-only wire DTOs (`RuleDto`, `EvaluatorVersionDto`, `EvaluatorVarDto`,
  `InputBindingDto`, `OutputBindingDto`) + enums mirroring the ADAS contract
- [x] 2.2 Add `AdasClientConfiguration` (ADAS `RestClient` with timeouts + `Api-Key`)
- [x] 2.3 Add `AdasRegistryClient.fetchRules(updatedSince)`

## 3. db-scheduler wiring

- [x] 3.1 Add `RuleScanTask` (`recurringWithPersistentSchedule`, placeholder handler)
- [x] 3.2 Add `ScheduleFactory` (fixed-delay from cadence / cron from trigger)
- [x] 3.3 Add `DbSchedulerConfiguration` (`Scheduler` bean: tx-aware datasource + `JacksonSerializer`)
- [x] 3.4 Add `SchedulerLifecycle` (`SmartLifecycle`, configurable auto-start)

## 4. Sync & reconciliation

- [x] 4.1 Add `RuleCache`
- [x] 4.2 Add `RuleSyncService` (`@Scheduled` full-list poll → generation diff → create/reschedule/cancel)

## 5. Verification

- [x] 5.1 Unit tests: `RuleCache`, `ScheduleFactory`
- [x] 5.2 Integration test: WireMock ADAS + Testcontainers PG — enabled rules → `scheduled_tasks`
  rows; removed rule cancelled; enum binding
- [x] 5.3 `./gradlew clean build` passes
- [x] 5.4 `openspec validate add-rule-sync --strict` passes
