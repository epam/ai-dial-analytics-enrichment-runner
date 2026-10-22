## MODIFIED Requirements

### Requirement: Soft daily spend-pause gate

The runner SHALL skip a rule's scan enqueue when the spend gate is enabled and the rule's current-day
calls or tokens have reached the configured budget (0 = unlimited). The gate SHALL be soft: it is
re-evaluated each scan fire against the current UTC day, so a rule resumes automatically when the day
rolls over. When the gate is disabled the rule SHALL never be considered over budget. The gate applies
only to model-calling rules: a Class-1 (`evaluator.type=sql`) rule SHALL be excluded from it rather
than evaluated against a zero counter, because it makes no DIAL calls and a budget expressed in calls
or tokens has no meaning for it.

#### Scenario: Over the daily budget skips enqueue

- **WHEN** the gate is enabled and a rule's day-to-date usage has reached the call or token budget
- **THEN** the rule is reported over budget and its scan fire is skipped

#### Scenario: Disabled gate never blocks

- **WHEN** the gate is disabled
- **THEN** the rule is never reported over budget regardless of usage

#### Scenario: A Class-1 rule is excluded from the gate

- **GIVEN** the gate is enabled with a daily budget configured
- **WHEN** a Class-1 rule's materialize tick fires
- **THEN** the rule is not reported over budget and its tick proceeds
