## ADDED Requirements

### Requirement: Rule admission gate

The runner SHALL refuse to cache or schedule a rule it provably cannot execute, logging a
human-readable reason and counting the refusal, so an unexecutable rule is visibly absent rather than
cached as healthy and failing on every fire. The gate SHALL be conservative: it rejects only
combinations the runner cannot execute — currently a `sql` evaluator carrying no output vars, and a
`sql` evaluator on the `group` trigger — and SHALL NOT reject a rule merely for carrying a field the
runner does not recognise. A refused rule SHALL have no recurring task instance under any task name.

#### Scenario: An unexecutable rule is skipped

- **WHEN** a sync returns an enabled rule whose evaluator type and trigger the runner cannot execute
- **THEN** the rule is absent from the rule cache, no recurring task instance is created for it, and
  the reason is logged and counted

#### Scenario: An unrecognised field does not disable a rule

- **WHEN** a sync returns an otherwise executable rule carrying a field the runner does not model
- **THEN** the rule is cached and scheduled normally

## MODIFIED Requirements

### Requirement: Rule registry sync

The runner SHALL periodically poll the ADAS rule registry (`GET /v1/rules`) and maintain an
in-memory cache of the enabled rules keyed by rule id. Because the feed carries no ETag/304,
change-detection SHALL be done client-side using each rule's `generation`. A failed poll SHALL leave
the current cache and recurring tasks unchanged (no partial teardown). The cache SHALL be published as
a single atomic snapshot so a concurrent reader never observes a half-applied reconcile. An empty feed
received while rules are already cached SHALL be treated as suspect and SHALL NOT tear down the cache
or its recurring tasks.

#### Scenario: Enabled rules are cached

- **WHEN** ADAS returns two enabled rules
- **THEN** the rule cache contains both, keyed by id, with their resolved (inlined) evaluators

#### Scenario: A failed poll is non-destructive

- **WHEN** a poll fails because ADAS is unreachable
- **THEN** the previously cached rules and their recurring tasks remain unchanged

#### Scenario: A suspect empty feed does not wipe the cache

- **GIVEN** rules are currently cached
- **WHEN** a poll returns an empty rule list
- **THEN** the cache and recurring tasks are left unchanged

#### Scenario: ADAS enum wire values bind case-insensitively

- **WHEN** ADAS returns a rule with lowercase enum values (`trigger_kind: on_ingest`,
  `priority: live`, evaluator `type: llm`, `preset: chat_completion`)
- **THEN** they bind to the corresponding runner enums

#### Scenario: A sql evaluator type binds and needs no model fields

- **WHEN** ADAS returns a rule whose inlined evaluator has `type: sql`, output vars carrying `sql`
  expressions, and no `model`, `preset`, `params`, `request_template` or `response_schema`
- **THEN** the evaluator type binds to the corresponding runner enum and the rule is cached without
  the absent model fields being treated as a defect

### Requirement: Recurring task reconciliation

For each enabled rule the runner SHALL maintain exactly one db-scheduler recurring task instance
(instance id = rule id) under the task name selected by the rule's evaluator type: `class1-materialize`
for a `sql` evaluator and `rule-scan` otherwise. The instance's schedule SHALL be derived from the
rule's trigger: a fixed delay from `cadence` (or the configured default) for `on_ingest`, and the
rule's cron expression for `schedule`. When a rule's `generation` changes, the runner SHALL reschedule
its instance. When a rule is no longer present in the enabled feed (disabled or deleted), or is no
longer routed to a given task name, the runner SHALL cancel that instance and — when the rule left the
feed entirely — release its per-rule resources (DIAL rate limiter, per-rule metrics). Reconciliation
SHALL therefore consider every task name the runner schedules rules onto, so a rule whose evaluator
type changed does not leave a stale instance under its former lane. A single rule's reconcile failure
SHALL be isolated (logged and skipped) so it cannot abort the whole reconcile and leave orphan tasks or
a divergent cache.

#### Scenario: A single rule's failure does not abort reconcile

- **GIVEN** a batch of enabled rules where reconciling one rule fails (e.g. an invalid schedule)
- **WHEN** the sync reconciles
- **THEN** the failing rule is skipped and the remaining rules are still reconciled

#### Scenario: Enabled rule gets a recurring task

- **WHEN** an enabled rule is synced for the first time
- **THEN** a recurring task instance keyed by the rule id exists in the scheduler, under the task name
  its evaluator type selects

#### Scenario: Removed rule is cancelled

- **GIVEN** a rule with an existing recurring task instance
- **WHEN** a later sync no longer includes that rule
- **THEN** its instance is cancelled and the rule is dropped from the cache

#### Scenario: Changed rule is rescheduled

- **GIVEN** a cached rule with an existing recurring task instance
- **WHEN** a later sync returns the same rule id with a higher `generation`
- **THEN** its instance is rescheduled to reflect the new schedule

#### Scenario: A stale instance under the former lane is cancelled

- **GIVEN** a cached rule with an existing instance under one task name
- **WHEN** a later sync routes that rule to a different task name
- **THEN** the instance under the former task name is cancelled and exactly one instance exists for
  the rule
