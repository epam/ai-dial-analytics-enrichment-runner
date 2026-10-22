## MODIFIED Requirements

### Requirement: Best-effort spend accounting

The runner SHALL record each DIAL call's usage into `spend_counter`, keyed by pipeline, evaluator version
and UTC day: successful calls increment the call count and token usage; a failed call increments the
failure count. Increments SHALL be race-free (a single UPSERT).

#### Scenario: Calls and tokens aggregate per pipeline and day

- **WHEN** two successful calls (100 and 50 tokens) and one failed call are recorded for a pipeline today
- **THEN** the day's totals are 2 calls, 150 tokens, and 1 failure

### Requirement: Soft daily spend-pause gate

The runner SHALL skip a pipeline's scan enqueue when the spend gate is enabled and the pipeline's current-day
calls or tokens have reached the configured budget (0 = unlimited). The gate SHALL be soft: it is
re-evaluated each scan fire against the current UTC day, so a pipeline resumes automatically when the day
rolls over. When the gate is disabled the pipeline SHALL never be considered over budget. The gate applies
only to model-calling pipelines: a Class-1 (`evaluator.type=sql`) pipeline SHALL be excluded from it rather
than evaluated against a zero counter, because it makes no DIAL calls and a budget expressed in calls
or tokens has no meaning for it.

This gate is **pipeline-scoped and configuration-driven**. A group pipeline additionally carries a finer,
**per-group-key** budget declared on the pipeline itself (`ready_when.cost_ceiling`) and enforced by the
readiness sweep, so the two are independent controls at different grains: this gate stops a whole pipeline from
enqueueing, the ceiling stops one group from being re-evaluated. Neither substitutes for the other, and a
reader looking for "what bounds a group pipeline's spend" SHALL find both.

#### Scenario: Over the daily budget skips enqueue

- **WHEN** the gate is enabled and a pipeline's day-to-date usage has reached the call or token budget
- **THEN** the pipeline is reported over budget and its scan fire is skipped

#### Scenario: Disabled gate never blocks

- **WHEN** the gate is disabled
- **THEN** the pipeline is never reported over budget regardless of usage

#### Scenario: A Class-1 pipeline is excluded from the gate

- **GIVEN** the gate is enabled with a daily budget configured
- **WHEN** a Class-1 pipeline's materialize tick fires
- **THEN** the pipeline is not reported over budget and its tick proceeds

#### Scenario: The pipeline-level gate and a group ceiling apply independently

- **GIVEN** a group pipeline within its pipeline-level budget but with one group at its `cost_ceiling`
- **WHEN** the readiness sweep runs
- **THEN** the pipeline is swept and its other groups are enqueued, while the group at its ceiling is not
