## MODIFIED Requirements

### Requirement: Runner metrics exposed via Prometheus

The runner SHALL expose its own operational metrics on the Prometheus scrape endpoint, covering every lane it
executes rather than only the row path:

- gauges for rules cached, rules refused as unexecutable, DLQ size, and **queue depth per task name** — a
  single summed depth cannot answer which lane is backed up, which is the only question depth is asked for;
- per-rule gauges for scan lag and **current-day spend**, so an approaching budget is visible before it pauses
  a rule;
- group gauges for active-group count, dirty backlog, and groups declined on their cost ceiling — the signals
  that distinguish a group path that is stuck from one that is quiet;
- a timer for DIAL call latency with success and error counters, plus a **distinct rate-limit (429) counter**;
  a 429 SHALL also remain in the error total, since it is a failed call and removing it would make the error
  rate under-report;
- timers for the readiness sweep's duration and counters for its enqueue rate and for group-worker throughput;
- Class-1 counters for rows written and tick failures, which are that lane's only throughput and failure
  signals since it has no queue and no dead-letter queue.

#### Scenario: Runner gauges are scrapeable

- **WHEN** `GET /actuator/prometheus` is requested
- **THEN** the response includes the runner's own metrics (e.g. `runner_rules_cached`)

#### Scenario: Queue depth distinguishes the lanes

- **GIVEN** work items pending for more than one task
- **WHEN** the Prometheus endpoint is scraped
- **THEN** depth is reported per task name rather than as a single total

#### Scenario: Rate-limit pressure is distinguishable from model failure

- **WHEN** a DIAL call is rejected with 429
- **THEN** a rate-limit counter increments and the call is also counted as an error

### Requirement: Rule-cache introspection

The runner SHALL expose a read-only endpoint listing the rules it currently has cached, with each
rule's id, name, evaluator version, trigger kind, enabled flag, and generation.

For a group rule the runner SHALL additionally expose its **group state** — per group: the group version, the
computed version, whether the last evaluation's input was truncated, and the evaluations charged against the
rule's daily ceiling — so "why has this group not been evaluated?" is answerable without database access. The
response SHALL carry identifiers, versions, timestamps and counts only, never member rows or labels.

The runner SHALL also let an operator **re-drive a single group**, re-enqueueing it through the same
deterministic identity the sweep uses, so the override cannot duplicate an item already queued for that
version. This is an explicit operator action and deliberately bypasses the readiness windows; it SHALL NOT
bypass the cost ceiling in effect, because the evaluation it triggers is charged like any other.

#### Scenario: Cached rules are listed

- **GIVEN** a rule is present in the runner's cache
- **WHEN** `GET /v1/rules/cache` is requested
- **THEN** the response lists that rule with its id, trigger kind, and generation

#### Scenario: A group rule's state is inspectable

- **GIVEN** a group rule with marked groups
- **WHEN** an operator requests that rule's group state
- **THEN** each group is reported with its version, computed version, truncation flag and evaluation count,
  and no member content

#### Scenario: An operator re-drives one group

- **GIVEN** a group that the sweep is not currently enqueueing
- **WHEN** an operator requeues that group
- **THEN** one group work item exists for its current version, and requeueing again adds none
