## MODIFIED Requirements

### Requirement: Draining incremental scan

When a rule's recurring task fires, the runner SHALL drain scan pages up to a per-fire budget
(`scan.max-pages-per-fire`). Each page SHALL be fetched from ADAS `POST /v1/rules/{id}/scan` with the
rule's persisted cursor, and the scan SHALL continue while `has_more` is true and the budget is not
exhausted. The rule's cursor SHALL be read from `rule_cursor` at the start of the drain; a rule with
no stored cursor SHALL scan from the source start. The scan HTTP call SHALL have no local side
effects (it precedes the commit and is safe to repeat). How a page is turned into committed work SHALL
depend on the rule's trigger kind: a row-grain rule's page becomes chunk work items, and a group rule's
page becomes per-group state — the drain loop, budget, gates and cursor handling are identical for both.
After each committed page the runner SHALL re-evaluate the pause, spend-budget, and backpressure gates
and stop the drain early when any trips (so a mid-fire trip stops within one page, not one whole fire).
The runner SHALL also stop the drain when a page reports `has_more` but does not advance the cursor (an
unchanged or null next cursor), rather than re-scanning the same range or restarting from the source.

#### Scenario: A fire drains a single available page

- **GIVEN** a cached rule and a scan page with `has_more=false`
- **WHEN** the rule's scan fires
- **THEN** exactly one page is scanned and its work is committed

#### Scenario: A mid-drain gate trip stops the fire

- **GIVEN** a multi-page drain where a page causes the rule to become paused, over budget, or backpressured
- **WHEN** the next page would be scanned
- **THEN** the drain stops without scanning or enqueuing further pages in that fire

#### Scenario: A non-advancing cursor halts the drain

- **GIVEN** a page that reports `has_more` but returns the same (or a null) cursor
- **WHEN** the drain evaluates the next page
- **THEN** the drain stops and logs, rather than re-scanning the same range or resetting to the start

#### Scenario: A group rule's page becomes group state

- **GIVEN** a cached group rule and a scan page carrying its group key and version column
- **WHEN** the rule's scan fires
- **THEN** the page's groups are marked in `group_state` and no chunk work items are enqueued

### Requirement: Deterministic, monotone sampling

The runner SHALL decide inclusion by `keep ⇔ hash64(key) mod 100 < threshold`, using a portable 64-bit
hash so the decision is stable across re-scans. The key SHALL be the grain key for a row-grain rule and
the group key for a group rule, so the decision is taken at the grain the work is keyed by. A null
`sampling` SHALL keep everything. Raising `sampling` SHALL only ever add work. Sampled-out rows SHALL
still be considered "seen" (the cursor advances past them).

#### Scenario: Sampling is stable and monotone

- **WHEN** a key is kept at a lower sampling fraction
- **THEN** it is also kept at any higher fraction, and the same key yields the same decision on every
  evaluation

#### Scenario: A group rule samples by group key

- **GIVEN** a group rule with a sampling fraction, and a page whose rows have differing grain keys but
  one shared group key
- **WHEN** the page is processed
- **THEN** the whole group is kept or dropped together, according to the group key's decision

### Requirement: Atomic enqueue and cursor advance

The runner SHALL commit a page's work, its dead-letter items, and the advance of `rule_cursor` to the
scan's `next_cursor` in a single transaction — all committing together or none. For a row-grain rule the
page's work is its chunk enqueues; for a group rule it is the page's `group_state` upserts. Chunk work
items SHALL use deterministic instance ids derived from the rule id, evaluator version and cursor-before,
so a repeated scan of the same range does not create duplicate chunks; group state upserts SHALL take the
maximum of the existing and incoming values, so a repeated scan leaves the state unchanged.

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

#### Scenario: A group page's state and cursor commit together

- **WHEN** a group rule's page commits successfully
- **THEN** the page's group state is persisted and the cursor is advanced, or on failure neither is
