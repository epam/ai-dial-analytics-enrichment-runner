## MODIFIED Requirements

### Requirement: Rule admission gate

The runner SHALL refuse to cache or schedule a rule it provably cannot execute, logging a
human-readable reason and counting the refusal, so an unexecutable rule is visibly absent rather than
cached as healthy and failing on every fire. The gate SHALL be conservative: it rejects only
combinations the runner cannot execute — currently a `sql` evaluator carrying no output vars, a `sql`
evaluator that also declares a model (a contradiction: the lane makes no model call), a `sql` evaluator on
the `group` trigger (the group lane's unit of work *is* a model call, so the two exclude each other), a
group rule with no group key, a group rule whose source declares no `version_column` (so it has neither a
version to compare nor an activity timestamp, and could never be dirty-marked), and a group rule whose
`ready_when` declares **none** of `signal`, `idle` or `max_staleness` (which would be dirty forever and never
ready, so the rule would silently do nothing) — and SHALL NOT reject a rule merely for carrying a field the
runner does not recognise. A refused rule SHALL have no recurring task instance under any task name.

A `ready_when` declaring `signal` SHALL NOT be a reason to refuse: ADAS computes the predicate inside the
scan and projects the verdict per row, so honouring it needs no predicate support in the runner.

#### Scenario: An unexecutable rule is skipped

- **WHEN** a sync returns an enabled rule whose evaluator type and trigger the runner cannot execute
- **THEN** the rule is absent from the rule cache, no recurring task instance is created for it, and
  the reason is logged and counted

#### Scenario: An unrecognised field does not disable a rule

- **WHEN** a sync returns an otherwise executable rule carrying a field the runner does not model
- **THEN** the rule is cached and scheduled normally

#### Scenario: A group rule with any readiness condition is admitted

- **WHEN** a sync returns a group rule declaring a group key and a `ready_when` with at least one of
  `signal`, `idle` or `max_staleness`
- **THEN** the rule is cached and a `rule-scan` recurring task instance is created for it

#### Scenario: A group rule with no readiness condition is refused

- **WHEN** a sync returns a group rule whose `ready_when` declares none of `signal`, `idle` or
  `max_staleness`
- **THEN** the rule is refused with that reason, is absent from the rule cache, and has no recurring
  task instance

#### Scenario: A group rule over a source without a version column is refused

- **WHEN** a sync returns a group rule carrying no `version_column`
- **THEN** the rule is refused naming `version_column`, is absent from the rule cache, and has no
  recurring task instance
