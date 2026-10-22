## RENAMED Requirements

- FROM: `### Requirement: Rule routing by evaluator type`
- TO: `### Requirement: Pipeline routing by evaluator type`

## MODIFIED Requirements

### Requirement: Pipeline routing by evaluator type

The runner SHALL route a pipeline to an execution lane by its resolved `evaluator.type`: a `sql` (Class-1)
pipeline to the materialize tick, and an `llm` (Class-2) pipeline to the row scan. The two lanes SHALL NOT be
interchangeable — ADAS rejects a `sql` pipeline on `POST /v1/pipelines/{name}/scan` and an `llm` pipeline on
`POST /v1/pipelines/{name}/materialize`, both with `422 pipeline_validation_failed`. A Class-1 pipeline SHALL be
recognised from the inlined evaluator alone, without a model, preset, params, request template or
response schema, and its output vars' SQL expressions SHALL NOT be interpreted by the runner.

#### Scenario: A sql pipeline is routed to the materialize tick

- **GIVEN** an enabled pipeline whose inlined evaluator has `type: sql`
- **WHEN** the pipeline sync reconciles
- **THEN** a `class1-materialize` recurring task instance keyed by the pipeline name exists, and no
  `pipeline-scan` instance exists for that pipeline

#### Scenario: An llm pipeline is unaffected

- **GIVEN** an enabled pipeline whose inlined evaluator has `type: llm`
- **WHEN** the pipeline sync reconciles
- **THEN** a `pipeline-scan` recurring task instance keyed by the pipeline name exists, and no
  `class1-materialize` instance exists for that pipeline

#### Scenario: A pipeline that changes type migrates lanes

- **GIVEN** a cached `llm` pipeline with an existing `pipeline-scan` instance
- **WHEN** a later sync returns the same pipeline name with `type: sql` and a higher `generation`
- **THEN** the `pipeline-scan` instance is cancelled, a `class1-materialize` instance is created, and the
  pipeline's stored cursor is left untouched

### Requirement: Draining materialize tick

When a Class-1 pipeline's recurring task fires, the runner SHALL drain materialize windows up to the
per-fire budget (`scan.max-pages-per-fire`). Each window SHALL be requested from ADAS
`POST /v1/pipelines/{name}/materialize` with the pipeline's persisted cursor and a resolved `limit`, and the
drain SHALL continue while `has_more` is true and the budget is not exhausted. The request MAY additionally
carry a `[from, to]` version window, which bounds the range materialized rather than replacing the cursor —
the live tick leaves it absent, and a bounded backfill supplies it. Driving this loop with a window is how a
Class-1 re-derivation is applied to history: bump the evaluator version, then backfill the affected range.

The pipeline's cursor SHALL be read from `pipeline_cursor` at the start of the drain; a pipeline with no stored cursor
SHALL materialize from the source start. A backfill SHALL drive the same loop against its own run cursor
rather than the live one. No row payload SHALL cross the wire in either direction, and the runner SHALL write
no enrichment rows on this path — ADAS stamps provenance itself.

#### Scenario: A fire drains a single available window

- **GIVEN** a cached Class-1 pipeline and a materialize response with `has_more=false`
- **WHEN** the pipeline's tick fires
- **THEN** exactly one materialize call is made and the cursor is advanced to its `next_cursor`

#### Scenario: A backlog drains multiple windows within the budget

- **GIVEN** a cached Class-1 pipeline and materialize responses reporting `has_more=true` until the last
- **WHEN** the pipeline's tick fires
- **THEN** successive windows are materialized until `has_more=false` or the per-fire budget is
  exhausted, each echoing the previous response's `next_cursor`

#### Scenario: A non-advancing cursor halts the drain

- **WHEN** ADAS reports `has_more=true` but returns a `next_cursor` that is absent or unchanged
- **THEN** the drain halts rather than re-materializing the same window

#### Scenario: A bounded run materializes only its window

- **GIVEN** a Class-1 backfill started with a `[from, to]` window
- **WHEN** its batches run
- **THEN** every materialize request carries that window and the run's own cursor advances within it

### Requirement: Cursor advances only on a successful response

The runner SHALL persist a Class-1 pipeline's `next_cursor` only after a successful (2xx) materialize
response. A failed, timed-out or rejected call SHALL leave `pipeline_cursor` unmoved so the identical
window is retried on the next fire. An empty window — `rows_written=0`, `has_more=false`, and a
`next_cursor` that is absent or equal to the cursor sent — SHALL NOT overwrite the stored cursor with
a null value. Because ADAS probes a window before it writes it, a replayed cursor re-executes an
identical range whose duplicate rows collapse on the target's `ReplacingMergeTree` grain key read with
`final=1`, giving exactly-once effect without a transaction around the call.

#### Scenario: A failed materialize leaves the cursor unmoved

- **GIVEN** a Class-1 pipeline with a stored cursor
- **WHEN** the materialize call returns a 5xx
- **THEN** the stored cursor is unchanged and the tick fails, to be retried on the next fire

#### Scenario: An empty window does not reset the cursor

- **GIVEN** a Class-1 pipeline with a stored cursor
- **WHEN** the materialize call returns `rows_written=0`, `has_more=false` and no `next_cursor`
- **THEN** the stored cursor is left as it was, not replaced with null

#### Scenario: A replayed window is a no-op in effect

- **GIVEN** a window that ADAS has already written but whose cursor the runner did not persist
- **WHEN** the tick fires again with the same cursor
- **THEN** the identical window is re-executed and the queried result read with `final=1` is unchanged

### Requirement: Class-1 runs outside the queue, spend and dead-letter machinery

The runner SHALL NOT enqueue work items, call DIAL, consume the per-pipeline rate limiter, record
`spend_counter` entries, or create `dlq_item` rows for a Class-1 pipeline. A Class-1 pipeline SHALL be
excluded from the soft daily spend-pause gate rather than evaluated against a zero counter, and SHALL
NOT be subject to queue-depth backpressure, which has no meaning where nothing is queued. Progress
SHALL be accounted from the response's `rows_written`, counted per pipeline. There SHALL be no dead-letter path:
the failure modes a DLQ exists for do not arise on a set-based deterministic path, so a pipeline that stops
progressing surfaces through this lane's three signals instead — its per-pipeline written-row count, its
tick-failure counter, and its cursor-stall counter. Because those are the whole of the lane's observability,
each SHALL carry its weight: lag SHALL decay to zero when the pipeline is caught up rather than latching at the
last backlog's value, and a drain halted by a non-advancing cursor SHALL be counted even though it raises no
error (see the `observability` capability).

#### Scenario: A Class-1 tick makes no DIAL calls and enqueues nothing

- **GIVEN** a cached Class-1 pipeline
- **WHEN** its tick fires and materializes a window successfully
- **THEN** no DIAL request is made, no work item is enqueued, no `spend_counter` row is written, and
  no `dlq_item` row is created

#### Scenario: A pipeline that stops progressing without failing is still visible

- **GIVEN** a Class-1 pipeline whose materialize response reports backlog but never advances the cursor
- **WHEN** its tick fires
- **THEN** the drain halts, its lane's cursor-stall counter increases, and the pipeline's written-row count
  stops climbing on its own series

#### Scenario: The spend gate never blocks a Class-1 pipeline

- **GIVEN** the spend gate is enabled and a daily budget is configured
- **WHEN** a Class-1 pipeline's tick fires
- **THEN** the pipeline is not gated, regardless of any recorded usage for its name

#### Scenario: A failed tick creates no dead-letter item

- **WHEN** a materialize call fails repeatedly across several fires
- **THEN** no `dlq_item` row is created and each failure is logged with the pipeline name
