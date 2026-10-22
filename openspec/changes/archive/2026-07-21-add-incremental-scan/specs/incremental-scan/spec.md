## ADDED Requirements

### Requirement: Draining incremental scan

When a rule's recurring task fires, the runner SHALL drain scan pages up to a per-fire budget
(`scan.max-pages-per-fire`). Each page SHALL be fetched from ADAS `POST /v1/rules/{id}/scan` with the
rule's persisted cursor, and the scan SHALL continue while `has_more` is true and the budget is not
exhausted. The rule's cursor SHALL be read from `rule_cursor` at the start of the drain; a rule with
no stored cursor SHALL scan from the source start. The scan HTTP call SHALL have no local side
effects (it precedes the commit and is safe to repeat).

#### Scenario: A fire drains a single available page

- **GIVEN** a cached rule and a scan page with `has_more=false`
- **WHEN** the rule's scan fires
- **THEN** exactly one page is scanned and its work is committed

### Requirement: Deterministic, monotone sampling

The runner SHALL decide row inclusion by `keep ⇔ hash64(grainKey) mod 100 < threshold`, using a
portable 64-bit hash so the decision is stable across re-scans. A null `sampling` SHALL keep every
row. Raising `sampling` SHALL only ever add rows. Sampled-out rows SHALL still be considered "seen"
(the cursor advances past them).

#### Scenario: Sampling is stable and monotone

- **WHEN** a grain key is kept at a lower sampling fraction
- **THEN** it is also kept at any higher fraction, and the same key yields the same decision on every
  evaluation

### Requirement: Input mapping with per-row dead-lettering

For each kept row the runner SHALL extract the evaluator input variables from the rule's input
bindings — a bound column value or a JSONata expression over the row. A JSONata evaluation failure
SHALL dead-letter that row at stage `input_map` (never a silent drop) while the rest of the page
proceeds; a binding that resolves to nothing SHALL yield a null value passed through.

#### Scenario: An un-evaluable binding dead-letters only its row

- **GIVEN** a page of rows and a rule whose input binding fails to evaluate
- **WHEN** the page is processed
- **THEN** each failing row produces a `dlq_item` at stage `input_map` and no chunk is enqueued for
  it, while mappable rows are still chunked, and the cursor advances

### Requirement: Atomic enqueue and cursor advance

The runner SHALL commit a page's chunk enqueues, its input-map DLQ items, and the advance of
`rule_cursor` to the scan's `next_cursor` in a single transaction — all committing together or none.
Chunk work items SHALL use deterministic instance ids derived from the rule id, evaluator version and
cursor-before, so a repeated scan of the same range does not create duplicate chunks.

#### Scenario: Enqueue and cursor advance commit together

- **WHEN** a page commits successfully
- **THEN** the page's chunks are enqueued and the cursor is advanced to `next_cursor`

#### Scenario: A failed commit rolls back both

- **WHEN** the commit fails (e.g. a DLQ write is rejected)
- **THEN** neither the chunks nor the cursor advance are persisted

#### Scenario: Repeated scan of the same range does not duplicate chunks

- **GIVEN** a page already committed for a cursor-before
- **WHEN** the same range is scanned again at the same cursor-before
- **THEN** the deterministic instance ids dedup and no additional chunks are enqueued

### Requirement: Backpressure and rule-lifecycle guard

The runner SHALL skip a scan fire entirely (no scan, no enqueue, no cursor advance) when the rule is
no longer present in the rule cache, or when the rule's in-flight (pending + delayed) chunk depth
exceeds `scan.max-inflight`.

#### Scenario: A hot rule is throttled

- **WHEN** a rule's in-flight chunk depth exceeds the configured maximum
- **THEN** the scan fire is skipped without scanning or advancing the cursor

#### Scenario: A removed rule does not scan

- **WHEN** a scan fires for a rule absent from the cache
- **THEN** nothing is scanned, enqueued, or advanced
