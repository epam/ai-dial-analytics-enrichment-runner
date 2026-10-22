## Context

This change introduces the runner's first ADAS integration and its first use of db-scheduler. The ADAS
`GET /v1/rules` feed returns a bare `List<RuleDto>` with no ETag/304, so change-detection is
client-side via each rule's `generation`. Each enabled rule must run a recurring scan on its own
cadence; this change sets up the recurring tasks but leaves the scan itself for `add-incremental-scan`.

## Goals / Non-Goals

**Goals:**
- Poll ADAS, cache enabled rules, and reconcile one recurring `rule-scan` task per rule.
- Wire db-scheduler the way `add-incremental-scan` needs it (tx-aware datasource + Jackson serializer).

**Non-Goals:**
- The scan/enqueue logic (`add-incremental-scan`), worker (`add-enrichment-worker`), and `updated_since` incremental sync optimization.

## Decisions

- **db-scheduler core, wired manually (not the Spring Boot starter).** The starter's
  auto-configuration targets Boot 3; wiring the `Scheduler` bean directly gives full control over the
  `TransactionAwareDataSourceProxy` (required for `add-incremental-scan`'s atomic enqueue+cursor commit) and
  `JacksonSerializer`, with zero Boot-4 autoconfig risk.
- **Per-rule recurring tasks via `recurringWithPersistentSchedule`.** One task type (`rule-scan`)
  with one instance per rule (instance id = rule id), each carrying its own persisted schedule via
  `PlainScheduleAndData`. This is db-scheduler's idiom for per-entity, runtime-changeable schedules —
  cleaner than registering a distinct task per rule. Alternative (a single global recurring task that
  scans all rules) was rejected: it loses per-rule cadence and backpressure.
- **Full-list poll, `generation` diff.** The feed has no removal signal under `updated_since`, so the
  runner polls the full list and treats "absent from feed" as disabled/deleted. `updated_since` is
  deferred.
- **Cron pinned to `SPRING53` style, UTC.** Matches ADAS's Spring 6-field cron format so a rule that
  validates in ADAS schedules here without a dialect mismatch.
- **Configurable start + initial delay.** `SchedulerLifecycle` (a `SmartLifecycle`) starts/stops the
  scheduler; auto-start and the first-poll delay are configurable so tests can drive reconciliation
  deterministically without the background poll racing.

## Risks / Trade-offs

- **Full-list poll cost** → Acceptable at expected rule counts; `updated_since` optimization deferred.
- **db-scheduler × Jackson 2 vs the app's Jackson 3** → db-scheduler serializes task data with its
  own default Jackson 2 mapper (which registers the schedule module); the app's Jackson 3 mapper is
  used only for HTTP. Jackson 2 databind is added explicitly so the serializer has it.
- **Reschedule on every generation change** → Only when `generation` actually changes (diffed against
  the cache), so steady-state polls do no scheduler writes.
