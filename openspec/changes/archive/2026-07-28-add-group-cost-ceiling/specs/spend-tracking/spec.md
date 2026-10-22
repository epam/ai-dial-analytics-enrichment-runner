## MODIFIED Requirements

### Requirement: Soft daily spend-pause gate

The runner SHALL skip a rule's scan enqueue when the spend gate is enabled and the rule's current-day
calls or tokens have reached the configured budget (0 = unlimited). The gate SHALL be soft: it is
re-evaluated each scan fire against the current UTC day, so a rule resumes automatically when the day
rolls over. When the gate is disabled the rule SHALL never be considered over budget. The gate applies
only to model-calling rules: a Class-1 (`evaluator.type=sql`) rule SHALL be excluded from it rather
than evaluated against a zero counter, because it makes no DIAL calls and a budget expressed in calls
or tokens has no meaning for it.

This gate is **rule-scoped and configuration-driven**. A group rule additionally carries a finer,
**per-group-key** budget declared on the rule itself (`ready_when.cost_ceiling`) and enforced by the
readiness sweep, so the two are independent controls at different grains: this gate stops a whole rule from
enqueueing, the ceiling stops one group from being re-evaluated. Neither substitutes for the other, and a
reader looking for "what bounds a group rule's spend" SHALL find both.

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

#### Scenario: The rule-level gate and a group ceiling apply independently

- **GIVEN** a group rule within its rule-level budget but with one group at its `cost_ceiling`
- **WHEN** the readiness sweep runs
- **THEN** the rule is swept and its other groups are enqueued, while the group at its ceiling is not
