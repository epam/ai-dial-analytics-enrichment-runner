## MODIFIED Requirements

### Requirement: Draining materialize tick

When a Class-1 rule's recurring task fires, the runner SHALL drain materialize windows up to the
per-fire budget (`scan.max-pages-per-fire`). Each window SHALL be requested from ADAS
`POST /v1/rules/{id}/materialize` with the rule's persisted cursor and a resolved `limit`, and the
drain SHALL continue while `has_more` is true and the budget is not exhausted. The request MAY additionally
carry a `[from, to]` version window, which bounds the range materialized rather than replacing the cursor —
the live tick leaves it absent, and a bounded backfill supplies it. Driving this loop with a window is how a
Class-1 re-derivation is applied to history: bump the evaluator version, then backfill the affected range.

The rule's cursor SHALL be read from `rule_cursor` at the start of the drain; a rule with no stored cursor
SHALL materialize from the source start. A backfill SHALL drive the same loop against its own run cursor
rather than the live one. No row payload SHALL cross the wire in either direction, and the runner SHALL write
no enrichment rows on this path — ADAS stamps provenance itself.

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

#### Scenario: A bounded run materializes only its window

- **GIVEN** a Class-1 backfill started with a `[from, to]` window
- **WHEN** its batches run
- **THEN** every materialize request carries that window and the run's own cursor advances within it
