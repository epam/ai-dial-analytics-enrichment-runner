## ADDED Requirements

### Requirement: Rule routing by evaluator type

The runner SHALL route a rule to an execution lane by its resolved `evaluator.type`: a `sql` (Class-1)
rule to the materialize tick, and an `llm` (Class-2) rule to the row scan. The two lanes SHALL NOT be
interchangeable — ADAS rejects a `sql` rule on `POST /v1/rules/{id}/scan` and an `llm` rule on
`POST /v1/rules/{id}/materialize`, both with `422 rule_validation_failed`. A Class-1 rule SHALL be
recognised from the inlined evaluator alone, without a model, preset, params, request template or
response schema, and its output vars' SQL expressions SHALL NOT be interpreted by the runner.

#### Scenario: A sql rule is routed to the materialize tick

- **GIVEN** an enabled rule whose inlined evaluator has `type: sql`
- **WHEN** the rule sync reconciles
- **THEN** a `class1-materialize` recurring task instance keyed by the rule id exists, and no
  `rule-scan` instance exists for that rule

#### Scenario: An llm rule is unaffected

- **GIVEN** an enabled rule whose inlined evaluator has `type: llm`
- **WHEN** the rule sync reconciles
- **THEN** a `rule-scan` recurring task instance keyed by the rule id exists, and no
  `class1-materialize` instance exists for that rule

#### Scenario: A rule that changes type migrates lanes

- **GIVEN** a cached `llm` rule with an existing `rule-scan` instance
- **WHEN** a later sync returns the same rule id with `type: sql` and a higher `generation`
- **THEN** the `rule-scan` instance is cancelled, a `class1-materialize` instance is created, and the
  rule's stored cursor is left untouched

### Requirement: Draining materialize tick

When a Class-1 rule's recurring task fires, the runner SHALL drain materialize windows up to the
per-fire budget (`scan.max-pages-per-fire`). Each window SHALL be requested from ADAS
`POST /v1/rules/{id}/materialize` with the rule's persisted cursor and a resolved `limit`, and the
drain SHALL continue while `has_more` is true and the budget is not exhausted. The rule's cursor SHALL
be read from `rule_cursor` at the start of the drain; a rule with no stored cursor SHALL materialize
from the source start. No row payload SHALL cross the wire in either direction, and the runner SHALL
write no enrichment rows on this path — ADAS stamps provenance itself.

#### Scenario: A fire drains a single available window

- **GIVEN** a cached Class-1 rule and a materialize response with `has_more=false`
- **WHEN** the rule's tick fires
- **THEN** exactly one materialize call is made and the cursor is advanced to its `next_cursor`

#### Scenario: A backlog drains multiple windows within the budget

- **GIVEN** a cached Class-1 rule and materialize responses reporting `has_more=true` until the last
- **WHEN** the rule's tick fires
- **THEN** successive windows are materialized until `has_more=false` or the per-fire budget is
  exhausted, each echoing the previous response's `next_cursor`

#### Scenario: A non-advancing cursor halts the drain

- **WHEN** ADAS reports `has_more=true` but returns a `next_cursor` that is absent or unchanged
- **THEN** the drain halts rather than re-materializing the same window

### Requirement: Cursor advances only on a successful response

The runner SHALL persist a Class-1 rule's `next_cursor` only after a successful (2xx) materialize
response. A failed, timed-out or rejected call SHALL leave `rule_cursor` unmoved so the identical
window is retried on the next fire. An empty window — `rows_written=0`, `has_more=false`, and a
`next_cursor` that is absent or equal to the cursor sent — SHALL NOT overwrite the stored cursor with
a null value. Because ADAS probes a window before it writes it, a replayed cursor re-executes an
identical range whose duplicate rows collapse on the target's `ReplacingMergeTree` grain key read with
`final=1`, giving exactly-once effect without a transaction around the call.

#### Scenario: A failed materialize leaves the cursor unmoved

- **GIVEN** a Class-1 rule with a stored cursor
- **WHEN** the materialize call returns a 5xx
- **THEN** the stored cursor is unchanged and the tick fails, to be retried on the next fire

#### Scenario: An empty window does not reset the cursor

- **GIVEN** a Class-1 rule with a stored cursor
- **WHEN** the materialize call returns `rows_written=0`, `has_more=false` and no `next_cursor`
- **THEN** the stored cursor is left as it was, not replaced with null

#### Scenario: A replayed window is a no-op in effect

- **GIVEN** a window that ADAS has already written but whose cursor the runner did not persist
- **WHEN** the tick fires again with the same cursor
- **THEN** the identical window is re-executed and the queried result read with `final=1` is unchanged

### Requirement: Class-1 runs outside the queue, spend and dead-letter machinery

The runner SHALL NOT enqueue work items, call DIAL, consume the per-rule rate limiter, record
`spend_counter` entries, or create `dlq_item` rows for a Class-1 rule. A Class-1 rule SHALL be
excluded from the soft daily spend-pause gate rather than evaluated against a zero counter, and SHALL
NOT be subject to queue-depth backpressure, which has no meaning where nothing is queued. Progress
SHALL be accounted from the response's `rows_written`. There SHALL be no dead-letter path: the failure
modes a DLQ exists for do not arise on a set-based deterministic path, so a persistently failing rule
surfaces through its lag and tick-failure signals instead.

#### Scenario: A Class-1 tick makes no DIAL calls and enqueues nothing

- **GIVEN** a cached Class-1 rule
- **WHEN** its tick fires and materializes a window successfully
- **THEN** no DIAL request is made, no work item is enqueued, no `spend_counter` row is written, and
  no `dlq_item` row is created

#### Scenario: The spend gate never blocks a Class-1 rule

- **GIVEN** the spend gate is enabled and a daily budget is configured
- **WHEN** a Class-1 rule's tick fires
- **THEN** the rule is not gated, regardless of any recorded usage for its id

#### Scenario: A failed tick creates no dead-letter item

- **WHEN** a materialize call fails repeatedly across several fires
- **THEN** no `dlq_item` row is created and each failure is logged with the rule id
