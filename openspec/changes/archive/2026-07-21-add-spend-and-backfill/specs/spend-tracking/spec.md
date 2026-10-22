## ADDED Requirements

### Requirement: Best-effort spend accounting

The runner SHALL record each DIAL call's usage into `spend_counter`, keyed by rule, evaluator version
and UTC day: successful calls increment the call count and token usage; a failed call increments the
failure count. Increments SHALL be race-free (a single UPSERT).

#### Scenario: Calls and tokens aggregate per rule and day

- **WHEN** two successful calls (100 and 50 tokens) and one failed call are recorded for a rule today
- **THEN** the day's totals are 2 calls, 150 tokens, and 1 failure

### Requirement: Soft daily spend-pause gate

The runner SHALL skip a rule's scan enqueue when the spend gate is enabled and the rule's current-day
calls or tokens have reached the configured budget (0 = unlimited). The gate SHALL be soft: it is
re-evaluated each scan fire against the current UTC day, so a rule resumes automatically when the day
rolls over. When the gate is disabled the rule SHALL never be considered over budget.

#### Scenario: Over the daily budget skips enqueue

- **WHEN** the gate is enabled and a rule's day-to-date usage has reached the call or token budget
- **THEN** the rule is reported over budget and its scan fire is skipped

#### Scenario: Disabled gate never blocks

- **WHEN** the gate is disabled
- **THEN** the rule is never reported over budget regardless of usage
