## RENAMED Requirements

- FROM: `### Requirement: Rule-scoped upsert of a chunk's rows`
- TO: `### Requirement: Pipeline-scoped upsert of a chunk's rows`
- FROM: `### Requirement: Stale-evaluator and rule-lifecycle guards`
- TO: `### Requirement: Stale-evaluator and pipeline-lifecycle guards`

## MODIFIED Requirements

### Requirement: Pipeline-scoped upsert of a chunk's rows

The runner SHALL write enriched rows in a **separate work item** from the one that computed them, so
retrying a failed write never re-calls the model. The compute phase SHALL end by enqueueing an upsert item
carrying the computed rows; that item SHALL upsert them through the pipeline-scoped ADAS endpoint
`POST /v1/pipelines/{name}/rows`, never naming a table, carrying the grain key and only the pipeline's declared
output and permitted provenance columns.

The enqueue SHALL commit in the same transaction as the compute phase's dead-letter writes, so a computed
result is never lost between the phases: either the upsert item exists or the compute is retried. The upsert
item's instance id SHALL derive from the compute item's, so a repeated compute cannot enqueue a second write
of the same result.

The write SHALL be idempotent by construction — the item carries fixed rows keyed by grain, so re-writing
them collapses on the target's `ReplacingMergeTree` read with `final=1`. The payload SHALL carry extracted
output columns only and never source rows, which is a privacy bound as much as a size one.

This applies to both grains. It does **not** apply to Class-1, where ADAS computes and writes inside one
call and the runner never holds an intermediate result.

#### Scenario: A chunk's rows are upserted through the pipeline

- **WHEN** a chunk produces one or more enriched rows
- **THEN** they are posted to `/v1/pipelines/{name}/rows`

#### Scenario: An upsert retry does not re-call the model

- **GIVEN** a chunk that computed successfully and whose write then fails transiently
- **WHEN** the write is retried
- **THEN** the rows are re-posted from the item's payload and no further model call is made

#### Scenario: A computed result is not lost at the phase boundary

- **WHEN** the compute phase commits
- **THEN** its upsert item and its dead-letter writes are persisted together, or neither is

#### Scenario: A repeated compute enqueues one write

- **GIVEN** a compute item that is executed twice
- **THEN** the deterministic upsert id dedups and exactly one upsert item exists

#### Scenario: The upsert payload carries no source rows

- **WHEN** an upsert item is serialized
- **THEN** it carries the computed output columns and grain keys only

### Requirement: Failure isolation taxonomy

Transient/infrastructure failures SHALL propagate so the failing **phase** retries — for compute: DIAL
429/5xx, DIAL connection/IO errors and rate-limit exhaustion; for the write: an ADAS upsert failure. A
transient write failure SHALL retry only the write, never the compute that produced its rows. Permanent
per-row failures (a non-retryable DIAL 4xx, schema-invalid output after repair, an output-mapping error)
SHALL be isolated to the offending row via the DLQ so one bad row never blocks its chunk; the chunk's other
rows are still enqueued for writing. Any other unexpected runtime error raised while processing a single row
(for example an empty or unparseable model reply, or a template-render/request-build failure) SHALL likewise
be isolated to that row's DLQ rather than failing the whole chunk.

A write the target rejects non-retryably — a column the pipeline no longer declares, after a mid-flight pipeline
change — SHALL dead-letter at stage `upsert` carrying its payload, so it is requeueable once the pipeline is
fixed rather than requiring the whole chunk to be recomputed to reach the same failure.

Provenance SHALL be stamped at compute time, so a row that took several write attempts to land reports when
its label was derived rather than when the write finally succeeded. The stale-evaluator guard SHALL be
re-checked at write time, so a pipeline re-pinned between the phases does not have a row written against the
superseded evaluator version.

#### Scenario: A non-retryable row error does not block its chunk

- **GIVEN** a chunk where one row hits a permanent error and the others succeed
- **WHEN** the chunk is processed
- **THEN** the failing row is dead-lettered and the successful rows are still upserted

#### Scenario: An unexpected per-row runtime error is isolated

- **GIVEN** a chunk where one row raises an unexpected runtime error (e.g. a template that cannot be
  rendered for that row's inputs) and the others succeed
- **WHEN** the chunk is processed
- **THEN** the failing row is dead-lettered and the successful rows are still upserted

#### Scenario: A rejected write dead-letters without recomputing

- **GIVEN** an upsert item whose rows the target rejects non-retryably
- **WHEN** the item executes
- **THEN** it dead-letters at stage `upsert` with its payload retained, and no model call is made

#### Scenario: A pipeline re-pinned between phases does not get a stale row

- **GIVEN** an upsert item computed against one evaluator version
- **WHEN** the pipeline is re-pinned to a newer version before the write executes
- **THEN** the write is dropped rather than writing a row stamped with the superseded version

### Requirement: Stale-evaluator and pipeline-lifecycle guards

Before writing, the runner SHALL drop a work item whose currently-pinned evaluator version differs from the
version it was built against, and SHALL drop a **pre-compute** item whose pipeline is no longer present in the
cache (disabled/deleted).

A work item whose pipeline has left the cache SHALL be handled by **what it holds**, not by the lookup alone.
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
