## ADDED Requirements

### Requirement: Rule registry sync

The runner SHALL periodically poll the ADAS rule registry (`GET /v1/rules`) and maintain an
in-memory cache of the enabled rules keyed by rule id. Because the feed carries no ETag/304,
change-detection SHALL be done client-side using each rule's `generation`. A failed poll SHALL leave
the current cache and recurring tasks unchanged (no partial teardown).

#### Scenario: Enabled rules are cached

- **WHEN** ADAS returns two enabled rules
- **THEN** the rule cache contains both, keyed by id, with their resolved (inlined) evaluators

#### Scenario: A failed poll is non-destructive

- **WHEN** a poll fails because ADAS is unreachable
- **THEN** the previously cached rules and their recurring tasks remain unchanged

#### Scenario: ADAS enum wire values bind case-insensitively

- **WHEN** ADAS returns a rule with lowercase enum values (`trigger_kind: on_ingest`,
  `priority: live`, evaluator `type: llm`, `preset: chat_completion`)
- **THEN** they bind to the corresponding runner enums

### Requirement: Recurring task reconciliation

For each enabled rule the runner SHALL maintain exactly one db-scheduler recurring `rule-scan` task
instance (instance id = rule id) whose schedule is derived from the rule's trigger: a fixed delay
from `cadence` (or the configured default) for `on_ingest`, and the rule's cron expression for
`schedule`. When a rule's `generation` changes, the runner SHALL reschedule its instance. When a rule
is no longer present in the enabled feed (disabled or deleted), the runner SHALL cancel its instance.

#### Scenario: Enabled rule gets a recurring task

- **WHEN** an enabled rule is synced for the first time
- **THEN** a `rule-scan` recurring task instance keyed by the rule id exists in the scheduler

#### Scenario: Removed rule is cancelled

- **GIVEN** a rule with an existing `rule-scan` instance
- **WHEN** a later sync no longer includes that rule
- **THEN** its `rule-scan` instance is cancelled and the rule is dropped from the cache

#### Scenario: Changed rule is rescheduled

- **GIVEN** a cached rule with an existing `rule-scan` instance
- **WHEN** a later sync returns the same rule id with a higher `generation`
- **THEN** its `rule-scan` instance is rescheduled to reflect the new schedule
