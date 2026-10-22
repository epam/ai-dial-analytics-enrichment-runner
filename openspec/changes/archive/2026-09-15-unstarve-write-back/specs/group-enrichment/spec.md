## MODIFIED Requirements

### Requirement: Readiness sweep enqueues dirty and ready groups

The runner SHALL run a scheduled sweep, independent of the scan, that enqueues one work item per group
which is **dirty**, **ready**, and **within its budget**. A group is dirty when its `group_version` exceeds
its `computed_version`, or it has never been computed. A group is ready when **any** readiness condition its
pipeline declares holds: `now − last_activity_at` has reached the pipeline's `ready_when.idle`, `now − computed_at`
has reached its `ready_when.max_staleness`, or the group has been recorded as signalled for a pipeline declaring
`ready_when.signal`. The signalled branch SHALL carry **no time bound** — a group declared complete is
evaluated on the next pass rather than waiting out an idle window. A group is within budget when its
evaluations for the current UTC day have not reached the pipeline's `ready_when.cost_ceiling`, which a pipeline
declaring no ceiling always satisfies.

Readiness thresholds, the declared conditions and the ceiling SHALL be read from the pipeline as synced, not
stored per group. Every condition SHALL be applied in the selection itself rather than by filtering
afterwards, so an ineligible group cannot consume the pass's budget and crowd out an eligible one. Readiness
SHALL NOT override dirtiness or the budget: a clean group is never enqueued however it signalled, and a
signalled group at its ceiling stays blocked. Each pass SHALL be bounded by a configured batch size so a
large dirty backlog cannot monopolise one pass, and SHALL honour the same pipeline-lifecycle, pause and spend
gates as the scan. Time SHALL be read through an injected clock.

A pass SHALL be bounded a second time, by a **depth ceiling** on how much work the pipeline may have
pending at once. The sweep SHALL enqueue at most the difference between that ceiling and the pipeline's
current in-flight depth, and SHALL defer the remainder to a later pass rather than enqueueing it. The
ceiling SHALL be derived from the scheduler's worker-thread count unless configured explicitly, because
the depth that is safe is a function of how many threads drain it and a constant chosen independently of
the pool size stops being right the moment the pool is resized.

The ceiling is about **where a backlog waits**, not about throughput; the pool drains at the same rate
either way. A group waiting in `group_state` is one durable row that costs nothing, is re-selected for
free on the next pass, and is derived from the state of the world when it finally runs. The same group
waiting in `scheduled_tasks` is a queued execution that pins a `group_version` snapshot, that a restart
may have to hold or drop, and that — at any batch size materially larger than the pool — will not run for
many minutes. The per-pass batch bound alone cannot express this, because it limits how fast the queue
fills and not how deep it gets.

The deferral SHALL be reported: counted, and logged with the pass's other outcomes, so a pipeline held at
its ceiling is distinguishable from one that has run out of ready groups. The two look identical
otherwise, and they call for opposite responses.

#### Scenario: An idle group is enqueued once per version

- **GIVEN** a dirty group whose last activity is older than the pipeline's `idle`
- **WHEN** the sweep runs twice without the group changing
- **THEN** exactly one work item exists for that group and version

#### Scenario: A group still receiving members is not enqueued

- **GIVEN** a dirty group whose last activity is more recent than the pipeline's `idle`
- **WHEN** the sweep runs
- **THEN** no work item is enqueued for it

#### Scenario: A stale computed group is re-enqueued regardless of activity

- **GIVEN** a group whose `computed_at` is older than the pipeline's `max_staleness` and which is dirty
- **WHEN** the sweep runs
- **THEN** a work item is enqueued for it even though it has seen no recent activity

#### Scenario: A signalled group is enqueued before its idle window elapses

- **GIVEN** a dirty group recorded as signalled, whose last activity is far more recent than its pipeline's
  `idle`
- **WHEN** the sweep runs
- **THEN** a work item is enqueued for it

#### Scenario: The verdict is ignored for a pipeline that declares no signal

- **GIVEN** a dirty group recorded as signalled, whose pipeline's `ready_when` declares no `signal`
- **WHEN** the sweep runs
- **THEN** no work item is enqueued until a condition the pipeline does declare holds

#### Scenario: A clean group is never enqueued

- **GIVEN** a group whose `computed_version` equals its `group_version`
- **WHEN** the sweep runs
- **THEN** no work item is enqueued for it, even if it is recorded as signalled

#### Scenario: A pass is bounded by its batch size

- **GIVEN** more dirty and ready groups than the configured per-pass batch size
- **WHEN** the sweep runs once
- **THEN** at most the batch size of work items is enqueued and the remainder is left for the next pass

#### Scenario: A group over its budget does not consume the pass

- **GIVEN** groups at their ceiling ordered ahead of eligible groups, and a per-pass batch size smaller than
  their combined count
- **WHEN** the sweep runs
- **THEN** the eligible groups are enqueued rather than being crowded out by the blocked ones

#### Scenario: The queue is not filled past the depth ceiling

- **GIVEN** a depth ceiling smaller than the pipeline's dirty and ready backlog, and a per-pass batch size
  larger than both
- **WHEN** the sweep runs
- **THEN** the pipeline's pending work reaches the ceiling and no further item is enqueued in that pass

#### Scenario: Deferred groups are picked up as capacity frees

- **GIVEN** a pipeline held at its depth ceiling with groups deferred
- **WHEN** enough of its pending work completes and the sweep runs again
- **THEN** the deferred groups are enqueued, oldest activity first, without any of them having been lost
  or stamped

#### Scenario: A pipeline held at its ceiling is distinguishable from an idle one

- **GIVEN** a pipeline whose sweep defers groups because it is at its depth ceiling
- **WHEN** the pass completes
- **THEN** the deferral is counted and named in the pass's log line, rather than the pass reporting only
  that it enqueued nothing

#### Scenario: Pending write-back counts toward the ceiling

- **GIVEN** a pipeline whose pending work is mostly computed results awaiting write-back
- **WHEN** the sweep runs
- **THEN** those items count toward the depth ceiling, so the sweep does not enqueue fresh compute on top
  of a write backlog
