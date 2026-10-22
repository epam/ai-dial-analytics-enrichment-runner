## ADDED Requirements

### Requirement: Group input assembly

When a group work item executes, the runner SHALL fetch the group's members from ADAS
`POST /v1/rules/{id}/groups:fetch`, sending only the group key and a row limit — ADAS resolves the source,
applies the membership filter, orders members by the source's total order, and enforces its row and
response-byte caps. The runner SHALL then apply the rule's `input_bindings` to **each** member row,
yielding one object per member, and SHALL expose that collection to the evaluator's request template as a
single variable rendered as a JSON array. Member field correlation SHALL be preserved, so a member's
bound fields stay together rather than being split across parallel per-variable collections. The runner
SHALL NOT name a table, a column set or an ordering in the request.

#### Scenario: Members become one object each under a collection variable

- **GIVEN** a group rule binding two input variables, and a group of three members
- **WHEN** the work item executes
- **THEN** the rendered prompt interpolates a JSON array of three objects, each carrying that member's two
  bound fields

#### Scenario: One prompt, one model call per group

- **WHEN** a group of many members is evaluated
- **THEN** exactly one prompt is rendered and exactly one model call is made for the whole group

### Requirement: Bounded group input

The runner SHALL apply its own token budget to the assembled input on top of ADAS's caps, dropping the
**oldest** members first and truncating per member only when dropping whole members still does not fit —
members arrive in ascending source order, so the newest are the most likely to bear on the label. The row
SHALL be marked truncated when the input was trimmed by **either** the runner's budget or ADAS's caps; the
two SHALL NOT be distinguished on the row, because what a reader needs to know is that the judgement was
partial. A truncated group SHALL still be evaluated: refusing to label it would leave it permanently dirty
and re-swept forever, converting a degraded label into an unbounded retry loop.

#### Scenario: An over-budget group is trimmed oldest-first and marked

- **GIVEN** a group whose assembled input exceeds the configured token budget
- **WHEN** the work item executes
- **THEN** the oldest members are dropped until it fits, the group is still evaluated, and the result is
  recorded as truncated

#### Scenario: ADAS-reported truncation is propagated

- **GIVEN** a `groups:fetch` response reporting `truncated=true`
- **WHEN** the work item executes
- **THEN** the result is recorded as truncated even though the runner's own budget did not trim it

### Requirement: One enrichment row per group with group provenance

The runner SHALL write exactly one enrichment row per evaluated group, keyed by the rule's `group_by`
value, carrying the mapped output columns and the provenance columns `evaluator_version`, `model`,
`enriched_at`, `group_version` and `truncated`. `group_version` SHALL be the version the input was judged
at and `truncated` whether that input was trimmed, so a partial label is distinguishable from a complete
one downstream and a label's input version is identifiable after the fact. Provenance columns the target
enrichment does not physically carry SHALL be tolerated (ADAS drops them silently), so the write SHALL NOT
be conditioned on the target's provenance columns.

#### Scenario: A group's label is written keyed by its group key

- **WHEN** a group is evaluated successfully
- **THEN** one row is written whose grain value is the group key, carrying the mapped outputs and the five
  provenance columns

#### Scenario: A target without group provenance columns still enriches

- **GIVEN** a target enrichment carrying neither `group_version` nor `truncated`
- **WHEN** a group is evaluated successfully
- **THEN** the write succeeds with those columns dropped by ADAS and no dead-letter is produced

### Requirement: Evaluated groups are stamped clean

After a successful write the runner SHALL stamp the group's `computed_version` to the version it evaluated,
`computed_at` to the evaluation time, and `computed_truncated` to whether the input was trimmed — which is
what makes the group clean and stops the readiness sweep re-enqueuing it. `group_state` SHALL remain the
authority for readiness even though the row now also carries provenance, because deriving dirtiness from
the enrichment table would put the sweep behind a ClickHouse read the runner does not have.

An empty group SHALL be treated as a successful no-op: nothing is written and the group is still stamped,
so a group whose members the membership filter excludes does not re-fire on every sweep.

The write SHALL precede the stamp and SHALL NOT share a transaction with it. A crash between them leaves
the group dirty, so it is re-swept and re-evaluated, and the identical row collapses on the target's
`ReplacingMergeTree` — at-least-once execution, exactly-once effect.

#### Scenario: A successful evaluation makes the group clean

- **GIVEN** a dirty group that is evaluated successfully
- **WHEN** the sweep next runs
- **THEN** no further work item is enqueued for that version

#### Scenario: An empty group is stamped without a write

- **GIVEN** a group whose `groups:fetch` returns no members
- **WHEN** the work item executes
- **THEN** no enrichment row is written, no dead-letter is produced, and the group is stamped clean

#### Scenario: A crash before the stamp re-evaluates rather than losing the group

- **GIVEN** a group whose row was written but whose stamp did not persist
- **WHEN** the sweep next runs
- **THEN** the group is still dirty and is re-evaluated, and the re-written row leaves the `final=1` result
  unchanged

### Requirement: Group failure isolation at group granularity

Because a group is 1:0..1 there SHALL be no partial-row outcome within a group: the group either produces a
label or fails as a whole. A retryable `groups:fetch` or DIAL failure (5xx, timeout, 429, or rate-limiter
exhaustion) SHALL fail the work item and be retried with the group's state untouched, so the group stays
dirty. A deterministic assembly or output-mapping failure SHALL dead-letter the group at stage `group_map`,
and a non-retryable `groups:fetch` rejection at stage `group_fetch`. Retry exhaustion SHALL archive the
item for requeue. The stale-evaluator and rule-lifecycle guards SHALL apply as they do to a row chunk.

#### Scenario: A transient fetch failure retries without touching state

- **GIVEN** a group work item whose `groups:fetch` returns a 5xx
- **WHEN** the item fails
- **THEN** it is retried later, no dead-letter is produced, and the group's `computed_version` is unchanged

#### Scenario: A mapping failure dead-letters the group

- **GIVEN** a group whose model response cannot be output-mapped
- **WHEN** the item executes
- **THEN** a `dlq_item` at stage `group_map` carrying the group key is written

#### Scenario: A stale group item is dropped without calling the model

- **GIVEN** a group work item whose evaluator version no longer matches the cached rule
- **WHEN** the item executes
- **THEN** no model call is made and nothing is written

## MODIFIED Requirements

### Requirement: Per-group state owned by the runner

The runner SHALL maintain one `group_state` row per active group of a group rule, keyed
`(rule_id, group_key)`, holding the group's `group_version`, `last_activity_at`, and — once evaluated —
`computed_version`, `computed_at` and `computed_truncated`. This state SHALL be what the readiness sweep
reads to decide dirtiness, and SHALL remain authoritative for a group's progress independently of the
lineage an enrichment row records. The enrichment row's `group_version` and `truncated` provenance
duplicate two of these facts deliberately: the row's copy serves downstream readers, while this state
serves the sweep, and the sweep SHALL NOT depend on reading the enrichment table.

#### Scenario: A newly seen group gets a state row

- **GIVEN** a group rule and a scanned page containing rows for a group key not yet in `group_state`
- **WHEN** the page is committed
- **THEN** a `group_state` row exists for that `(rule_id, group_key)` with a `group_version`, a
  `last_activity_at`, and a null `computed_version`
