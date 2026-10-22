## MODIFIED Requirements

### Requirement: Pipeline-cache introspection

The runner SHALL expose a read-only endpoint listing the pipelines it currently has cached, with each
pipeline's name, trigger kind, enabled flag, and generation. The entry carries **one** identifier: the name
is the pipeline's identity in the ADAS registry, in this runner's state tables and in its work-item ids
alike, so there is no second id to report.

The entry SHALL report **one** declaration revision as well, and that is a substantive removal rather than a
rename: the evaluator version it used to report alongside the generation no longer exists in the feed, and
the `generation` already present in this response is what now answers "which declaration is this pipeline
running?". The `evaluator_version` property SHALL therefore be dropped from the response rather than
re-populated from the generation, which would report the same number twice under two names.

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

### Requirement: Single-replica topology that tolerates a rolling update

The runner SHALL be deployed as a single active replica, and SHALL nonetheless tolerate the transient overlap
of two instances that a rolling update produces, so a deploy costs no restart gap.

Tolerance is a property of where the work is serialized, not of a lock the runner takes: every unit of work is
a db-scheduler execution claimed by exactly one node, the per-pipeline scan is a recurring task with one instance
per pipeline, and every enqueue SHALL be idempotent on a deterministic instance id (pipeline name, the
pipeline's `generation`, and the grain or group version), so two instances racing to enqueue the same work
collapse to one row. The loops that are not db-scheduler executions — pipeline sync, the group readiness sweep,
the DLQ and group-state sweeps, the metrics gauge refresh — SHALL likewise be idempotent under concurrent
execution.

Deriving the id from `generation` rather than an evaluator version narrows when that collapse holds, and the
narrowing is meaning rather than spelling: two overlapping instances collapse only while both hold the **same**
cached generation for a pipeline. Every deterministic id changes once at the fold's cut-over, and thereafter on
**any** declaration edit — prompt, expressions, `filter`, trigger, cadence, `rate_rpm` or `batch_scan_limit` —
where an evaluator version moved only on a prompt or expression change. An overlap that straddles a declaration
edit, with one instance's cache refreshed and the other's not, therefore enqueues the same work twice, once per
generation; it is bounded by the overlap being transient and by the enrichment target's ReplacingMergeTree, not
by `scheduleIfNotExists`.

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
