## MODIFIED Requirements

### Requirement: Rule admission gate

The runner SHALL refuse to cache or schedule a rule it provably cannot execute, logging a
human-readable reason and counting the refusal, so an unexecutable rule is visibly absent rather than
cached as healthy and failing on every fire. The gate SHALL be conservative: it rejects only
combinations the runner cannot execute — currently a `sql` evaluator carrying no output vars, a `sql`
evaluator on the `group` trigger, a group rule with no group key, and a group rule whose readiness the
runner cannot evaluate (a `ready_when` that declares `signal`, or that declares neither `idle` nor
`max_staleness`) — and SHALL NOT reject a rule merely for carrying a field the runner does not
recognise. A refused rule SHALL have no recurring task instance under any task name.

#### Scenario: An unexecutable rule is skipped

- **WHEN** a sync returns an enabled rule whose evaluator type and trigger the runner cannot execute
- **THEN** the rule is absent from the rule cache, no recurring task instance is created for it, and
  the reason is logged and counted

#### Scenario: An unrecognised field does not disable a rule

- **WHEN** a sync returns an otherwise executable rule carrying a field the runner does not model
- **THEN** the rule is cached and scheduled normally

#### Scenario: A group rule with supported readiness is admitted

- **WHEN** a sync returns a group rule declaring a group key and a `ready_when` with `idle` or
  `max_staleness` and no `signal`
- **THEN** the rule is cached and a `rule-scan` recurring task instance is created for it

#### Scenario: A group rule with unevaluable readiness is refused

- **WHEN** a sync returns a group rule whose `ready_when` declares `signal`, or declares neither `idle`
  nor `max_staleness`
- **THEN** the rule is refused with that reason, is absent from the rule cache, and has no recurring
  task instance
