## RENAMED Requirements

- FROM: `### Requirement: Readiness gates on a successful rule sync`
- TO: `### Requirement: Readiness gates on a successful pipeline sync`
- FROM: `### Requirement: Rule-cache introspection`
- TO: `### Requirement: Pipeline-cache introspection`

## MODIFIED Requirements

### Requirement: Runner metrics exposed via Prometheus

The runner SHALL expose its own operational metrics on the Prometheus scrape endpoint, covering every lane it
executes rather than only the row path:

- gauges for pipelines cached, pipelines refused as unexecutable, **pipelines paused**, DLQ size, and **queue depth per
  task name** — a single summed depth cannot answer which lane is backed up, which is the only question depth
  is asked for, and an auto-pause otherwise exists only as one log line while the condition it announces
  lasts until an operator resumes the pipeline;
- per-pipeline gauges for scan lag and **current-day spend**, so an approaching budget is visible before it pauses
  a pipeline;
- group gauges for active-group count, dirty backlog, and groups declined on their cost ceiling — the signals
  that distinguish a group path that is stuck from one that is quiet;
- a timer for DIAL call latency with success and error counters, plus a **distinct rate-limit (429) counter**;
  a 429 SHALL also remain in the error total, since it is a failed call and removing it would make the error
  rate under-report;
- timers for the readiness sweep's duration and counters for its enqueue rate and for group-worker throughput;
- Class-1 counters for **rows written per pipeline** and tick failures, which are that lane's only throughput and
  failure signals since it has no queue and no dead-letter queue — a summed total cannot say which pipeline stopped
  writing;
- a **cursor-stall counter per lane**, for a drain that halted because the cursor did not advance: that halt
  raises no error, so without it a pipeline can stop making progress with every other signal reading healthy.

#### Scenario: Runner gauges are scrapeable

- **WHEN** `GET /actuator/prometheus` is requested
- **THEN** the response includes the runner's own metrics (e.g. `runner_pipelines_cached`)

#### Scenario: Queue depth distinguishes the lanes

- **GIVEN** work items pending for more than one task
- **WHEN** the Prometheus endpoint is scraped
- **THEN** depth is reported per task name rather than as a single total

#### Scenario: Rate-limit pressure is distinguishable from model failure

- **WHEN** a DIAL call is rejected with 429
- **THEN** a rate-limit counter increments and the call is also counted as an error

### Requirement: Readiness gates on a successful pipeline sync

The runner's readiness SHALL be UP only after at least one successful ADAS pipeline sync has completed
(in addition to PostgreSQL reachability); liveness SHALL reflect process health independently. The
readiness contributor SHALL report DOWN until the first successful sync.

#### Scenario: Readiness turns UP after the first sync

- **GIVEN** the runner has completed a successful pipeline sync
- **WHEN** `GET /actuator/health/readiness` is requested
- **THEN** the status is UP

### Requirement: Pipeline-cache introspection

The runner SHALL expose a read-only endpoint listing the pipelines it currently has cached, with each
pipeline's name, evaluator version, trigger kind, enabled flag, and generation. The entry carries **one**
identifier: the name is the pipeline's identity in the ADAS registry, in this runner's state tables and in
its work-item ids alike, so there is no second id to report.

For a group pipeline the runner SHALL additionally expose its **group state** — per group: the group version, the
computed version, whether the last evaluation's input was truncated, and the evaluations charged against the
pipeline's daily ceiling — so "why has this group not been evaluated?" is answerable without database access. The
response SHALL carry identifiers, versions, timestamps and counts only, never member rows or labels.

The runner SHALL also let an operator **re-drive a single group**, re-enqueueing it through the same
deterministic identity the sweep uses, so the override cannot duplicate an item already queued for that
version. This is an explicit operator action and deliberately bypasses the readiness windows; it SHALL NOT
bypass the cost ceiling in effect, because the evaluation it triggers is charged like any other.

Because the re-drive is built at the group's *current* version, re-driving a **clean** group evaluates it at
the version already stamped. That SHALL be treated as an ordinary evaluation rather than as a losing race: its
`computed_at` and `computed_truncated` replace the previous evaluation's, and it SHALL NOT be reported as
superseded.

#### Scenario: Cached pipelines are listed

- **GIVEN** a pipeline is present in the runner's cache
- **WHEN** `GET /v1/pipelines/cache` is requested
- **THEN** the response lists that pipeline with its name, trigger kind, and generation

#### Scenario: A group pipeline's state is inspectable

- **GIVEN** a group pipeline with marked groups
- **WHEN** an operator requests that pipeline's group state
- **THEN** each group is reported with its version, computed version, truncation flag and evaluation count,
  and no member content

#### Scenario: An operator re-drives one group

- **GIVEN** a group that the sweep is not currently enqueueing
- **WHEN** an operator requeues that group
- **THEN** one group work item exists for its current version, and requeueing again adds none

### Requirement: Structured chunk-boundary logging

The runner SHALL log at work-item boundaries with correlation context in the MDC — pipeline name and item id for
every grain, plus the group key for a group item — and SHALL NOT log full request/response payloads at INFO.

The logging contract SHALL also state what must never be logged **at any level**: assembled group input,
member rows, and rendered prompts. Unlike the INFO restriction, this is not a verbosity choice but a data
boundary, so it holds for `debug` and `trace` too — which is where a careless future line would otherwise
land. It SHALL be verified by a test rather than left to review.

#### Scenario: A chunk logs a correlated boundary line

- **WHEN** the worker begins processing a chunk
- **THEN** it logs a boundary line carrying the pipeline name and chunk id, without full payloads

#### Scenario: A group item logs its group key as correlation

- **WHEN** the group worker begins processing a group item
- **THEN** its log records carry the pipeline name, item id and group key in the MDC

#### Scenario: Source content is absent from logs at every level

- **GIVEN** logging configured down to its most verbose level
- **WHEN** a group is evaluated
- **THEN** no record contains the members' content

### Requirement: Single-replica topology that tolerates a rolling update

The runner SHALL be deployed as a single active replica, and SHALL nonetheless tolerate the transient overlap
of two instances that a rolling update produces, so a deploy costs no restart gap.

Tolerance is a property of where the work is serialized, not of a lock the runner takes: every unit of work is
a db-scheduler execution claimed by exactly one node, the per-pipeline scan is a recurring task with one instance
per pipeline, and every enqueue SHALL be idempotent on a deterministic instance id (pipeline name, evaluator version,
and the grain or group version), so two instances racing to enqueue the same work collapse to one row. The
loops that are not db-scheduler executions — pipeline sync, the group readiness sweep, the DLQ and group-state
sweeps, the metrics gauge refresh — SHALL likewise be idempotent under concurrent execution.

What is **not** shared SHALL be documented rather than implied, because it is what bounds the overlap to a
transient one: the DIAL rate limiter, the pipeline-pause registry (operator and breaker-driven) and the DLQ
breaker counters are per-process, so a second instance splits or doubles them. Sustained N>1 is therefore
unsupported until pause is durable and the rate limiter is shared.

#### Scenario: Two instances overlap during a rolling update

- **GIVEN** an outgoing and an incoming instance running concurrently against the same PostgreSQL state
- **WHEN** both reconcile pipelines and sweep groups in the same interval
- **THEN** each work item is executed once, duplicate enqueues collapse on their deterministic instance ids,
  and no scan cursor is advanced past unprocessed rows

#### Scenario: A paused pipeline does not survive a replacement instance

- **GIVEN** a pipeline paused by an operator or auto-paused by the DLQ circuit breaker
- **WHEN** the instance holding that pause is replaced
- **THEN** the replacement starts with the pipeline unpaused, since pause state is in-memory — documented as a
  consequence of the topology rather than treated as durable state
