## MODIFIED Requirements

### Requirement: Rule-scoped upsert of a chunk's rows

The runner SHALL write enriched rows in a **separate work item** from the one that computed them, so
retrying a failed write never re-calls the model. The compute phase SHALL end by enqueueing an upsert item
carrying the computed rows; that item SHALL upsert them through the rule-scoped ADAS endpoint
`POST /v1/rules/{id}/rows`, never naming a table, carrying the grain key and only the rule's declared
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

#### Scenario: A chunk's rows are upserted through the rule

- **WHEN** a chunk produces one or more enriched rows
- **THEN** they are posted to `/v1/rules/{id}/rows`

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

A write the target rejects non-retryably — a column the rule no longer declares, after a mid-flight rule
change — SHALL dead-letter at stage `upsert` carrying its payload, so it is requeueable once the rule is
fixed rather than requiring the whole chunk to be recomputed to reach the same failure.

Provenance SHALL be stamped at compute time, so a row that took several write attempts to land reports when
its label was derived rather than when the write finally succeeded. The stale-evaluator guard SHALL be
re-checked at write time, so a rule re-pinned between the phases does not have a row written against the
superseded evaluator version.

#### Scenario: A non-retryable row error does not block its chunk

- **GIVEN** a chunk where one row hits a permanent error and the others succeed
- **WHEN** the chunk is processed
- **THEN** the failing row is dead-lettered and the successful rows are still upserted

#### Scenario: An unexpected per-row runtime error is isolated

- **GIVEN** a chunk where one row raises an unexpected runtime error (e.g. an empty/unparseable model
  reply with no response schema) and the others succeed
- **WHEN** the chunk is processed
- **THEN** the failing row is dead-lettered and the successful rows are still upserted

#### Scenario: A rejected write dead-letters without recomputing

- **GIVEN** an upsert item whose rows the target rejects non-retryably
- **WHEN** the item executes
- **THEN** it dead-letters at stage `upsert` with its payload retained, and no model call is made

#### Scenario: A rule re-pinned between phases does not get a stale row

- **GIVEN** an upsert item computed against one evaluator version
- **WHEN** the rule is re-pinned to a newer version before the write executes
- **THEN** the write is dropped rather than writing a row stamped with the superseded version
