## ADDED Requirements

### Requirement: Write-back outranks compute in the work queue

A pending write-back SHALL be scheduled at a strictly higher queue priority than any compute item, so that
a free worker thread takes the write before it starts new derivation.

Without this the ordering is decided by age, and a write item is younger than the compute that created it
and younger than every compute enqueued alongside it — so it can never reach the front while compute
remains. That is not a race the write occasionally loses; it is an ordering it cannot win, and a trigger
that replenishes the compute backlog faster than the pool drains it turns "cannot win" into "never runs".
The outcome is a pipeline that calls the model, charges for every call, and writes nothing.

The rule is *finish before you start*. A write-back is bounded work — one request over rows already held,
no model call — it is idempotent, and it is the only step that turns spend into a stored label. It cannot
starve what sits below it, because the set of pending writes is bounded by the set of computes that have
already completed: write-back generates no write-back. The converse does not hold, which is the whole
asymmetry.

Every lane's write-back SHALL share that one priority, the backfill's included. A backfill's *compute* is
deliberately ranked below live compute; its result, once paid for, is worth what a live result is worth
and carries the same exposure while it sits queued. Ranking the two write lanes against each other would
reproduce this defect one level down.

A **requeued** item SHALL be scheduled in the lane its task belongs to, not at the default priority. A
requeue is work an operator asked for by hand; leaving it unranked places it below every automatically
enqueued item, which is the opposite of what the request meant.

The priorities SHALL be defined in one place that every enqueue site reads. Three tasks and five call
sites share them, and the value that was wrong was wrong because it was borrowed from the task next to it.

#### Scenario: A computed result is written before the next group is evaluated

- **GIVEN** a compute backlog deeper than the worker pool and one pending write-back
- **WHEN** a worker thread frees
- **THEN** it takes the write-back, and the computed row is written rather than queued behind the backlog

#### Scenario: A sustained backlog does not stop write-back

- **GIVEN** a trigger that replenishes the compute backlog on every pass
- **WHEN** the pipeline runs for longer than the pool would need to drain the backlog
- **THEN** results are written as they are computed, and the count of pending write-backs does not grow
  without bound

#### Scenario: A backfill's result is not ranked below live compute

- **GIVEN** a backfill compute item and a backfill write-back item pending alongside live compute
- **WHEN** a worker thread frees
- **THEN** the write-back is taken before the live compute, and the backfill compute after it

#### Scenario: A requeued write-back is not the lowest-priority item in the queue

- **GIVEN** an operator requeues an archived write-back while compute work is pending
- **WHEN** a worker thread frees
- **THEN** the requeued write is taken ahead of the pending compute

## MODIFIED Requirements

### Requirement: Stale-evaluator and pipeline-lifecycle guards

Before writing, the runner SHALL drop a work item whose currently-pinned evaluator version differs from the
version it was built against, and SHALL drop a **pre-compute** item whose pipeline is no longer present in the
cache (disabled/deleted).

A work item SHALL distinguish a pipeline cache that is **populated and does not hold this pipeline** from one
that has **never been populated**. Only the first is an answer. The second means the process has not yet
synced the registry — the cache is empty at startup and the first sync is a scheduled poll behind it, while
the job scheduler begins picking due work as soon as the context starts — and reading it as "disabled or
deleted" acts on a fact the runner does not have. A work item that becomes due while the cache has never
been populated SHALL be **held**: rescheduled without consuming an attempt and without being archived, and
SHALL execute normally once the first sync lands. "Held" SHALL be defined in one place that all three lanes
use, so they cannot disagree about what it means — and SHALL NOT be expressed as an ordinary retry, which
consumes the item's attempt budget and archives it at the maximum, so a registry that stays unreachable for
longer than the backoff ladder would dead-letter exactly the work this rule exists to keep. The cache SHALL count a reconcile to **empty** as populated, because an empty registry is a real
answer about it.

This matters most on the row lane, where the drop is irreversible: the scan cursor advances in the same
transaction that enqueues the chunk, so a chunk dropped on a cold cache is a set of rows nothing revisits.
On the group lane the same drop costs a re-derivation rather than the data. On the write lane it archives
a paid-for result for an operator to requeue by hand. None of the three is the right response to "ask me
again in a moment".

A work item whose pipeline has left a **populated** cache SHALL be handled by **what it holds**, not by the lookup alone.
A **pre-compute** item — a scan, a chunk, a group item — SHALL be dropped: nothing has been spent, and the
work is re-derived when the pipeline returns. A **post-compute upsert item** SHALL instead be **archived to the
DLQ with its payload and its task name**, because it carries a result the model was already paid for whose
only copy is that payload; dropping it destroys the work rather than deferring it, and no later sweep
recreates it. A requeue then re-attempts the write once the pipeline is back, without recomputing anything.

The runner SHALL NOT distinguish a disabled pipeline from a deleted one at this point, because ADAS's pipeline feed
omits both identically — which is precisely why the post-compute item must be preserved: the far more common
cause is a pause, and a pause SHALL NOT destroy computed work.

Archiving for this cause SHALL NOT stamp the group and SHALL NOT count against the DLQ circuit breaker,
unlike a write ADAS permanently rejects. The write never reached ADAS, so nothing about the group's
evaluation state changed; and pausing a pipeline that is already absent would leave a pause entry that outlives
its return and silently holds it back.

A stale-evaluator upsert item SHALL still be dropped rather than archived: its rows were computed against a
superseded evaluator version and must never be written, so preserving them would preserve a result that is
no longer valid.

#### Scenario: A stale chunk is dropped without side effects

- **GIVEN** a chunk built against evaluator version N and a pipeline now pinned to a different version
- **WHEN** the chunk is processed
- **THEN** it is dropped without calling DIAL or upserting

#### Scenario: A computed write survives its pipeline being disabled mid-flight

- **GIVEN** an upsert item holding a computed label, and its pipeline disabled in ADAS before the write executes
  (so the runner has evicted it from the cache)
- **WHEN** the write item runs
- **THEN** the item is archived to the DLQ with its payload and `upsert-rows` task name, nothing is written to
  ADAS, the group's `computed_version` is unchanged, and the pipeline is not paused

#### Scenario: The archived write is recoverable without a model call

- **GIVEN** such an archived item and the pipeline enabled again
- **WHEN** the item is requeued
- **THEN** the stored rows are re-posted to ADAS and no model call is made

#### Scenario: Work due before the first sync is held, not dropped

- **GIVEN** a freshly started process whose pipeline cache has never been populated, and chunk, group and
  write items already queued
- **WHEN** each becomes due
- **THEN** none is dropped, archived, or counted as a failed attempt, and each remains queued

#### Scenario: Held work runs once the first sync lands

- **GIVEN** such held items
- **WHEN** the first pipeline sync populates the cache
- **THEN** each item executes normally against its pipeline, with its full attempt budget intact

#### Scenario: An empty registry is an answer, not a cold cache

- **GIVEN** a process whose first sync reconciles to an empty pipeline set
- **WHEN** a queued work item becomes due
- **THEN** the cache counts as populated and the item takes the ordinary absent-pipeline path for its phase

#### Scenario: A restart does not silently discard scanned rows

- **GIVEN** chunks enqueued by a scan whose cursor has already advanced past their rows
- **WHEN** the process restarts and those chunks become due before the first sync
- **THEN** they are held and later enriched, rather than dropped for rows no cursor will read again
