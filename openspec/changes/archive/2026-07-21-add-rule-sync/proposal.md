## Why

The runner needs to know which enrichment rules are enabled and, for each, run a recurring scan on
the rule's cadence. Rules live in the ADAS control-plane and change over time (enabled, disabled,
re-pinned). This change syncs enabled rules from ADAS into a local cache and
reconciles one db-scheduler recurring task per enabled rule — the scaffolding `add-incremental-scan` fills with the
actual scan.

## What Changes

- **db-scheduler wiring**: add the db-scheduler core library and wire the `Scheduler` bean over the
  runner's PostgreSQL datasource (`TransactionAwareDataSourceProxy` + `JacksonSerializer`), with a
  configurable start/stop lifecycle.
- **ADAS registry client**: a `RestClient`-based `AdasRegistryClient.fetchRules(...)` calling
  `GET /v1/rules`, plus the read-only wire DTOs (`RuleDto`, `EvaluatorVersionDto`, bindings, enums)
  mirroring the ADAS contract.
- **Rule cache**: an in-memory `RuleCache` of enabled rules (the worker's future stale-evaluator
  guard reads it).
- **Recurring-task reconciliation**: a `@Scheduled` `RuleSyncService` that polls the full rule list,
  diffs on each rule's `generation`, and creates/reschedules/cancels a `rule-scan` recurring task per
  rule. The `rule-scan` task itself is a placeholder handler (`add-incremental-scan` implements the scan).
- **Config**: `adas.*` (base URL, api-key, timeouts, poll/initial-delay), `db-scheduler.*`
  (heartbeat, polling, threads, auto-start), `scan.default-cadence`.

## Capabilities

### New Capabilities
- `rule-sync`: syncing enabled rules from ADAS into a local cache and reconciling one db-scheduler
  recurring task per enabled rule.

### Modified Capabilities
<!-- none -->

## Impact

- New deps: `com.github.kagkarlsson:db-scheduler:15.6.0`, explicit `jackson-databind` (Jackson 2 for
  db-scheduler's serializer), test-scope `org.wiremock:wiremock-standalone`.
- New code under `com.epam.aidial.enrichment`: `adas/` (client + `dto/`), `sync/` (`RuleCache`,
  `RuleSyncService`), `scan/` (`RuleScanTask`, `ScheduleFactory`), `configuration/` (`AdasProperties`,
  `DbSchedulerProperties`, `AdasClientConfiguration`, `DbSchedulerConfiguration`, `SchedulerLifecycle`).
- No new database migration (the `scheduled_tasks` table exists from `bootstrap-runner-service`).
- Note: db-scheduler latest is `15.6.0` (a "16.x" does not exist yet).
