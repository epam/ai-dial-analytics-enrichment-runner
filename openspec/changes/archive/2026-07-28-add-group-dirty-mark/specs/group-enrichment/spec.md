## ADDED Requirements

### Requirement: Per-group state owned by the runner

The runner SHALL maintain one `group_state` row per active group of a group rule, keyed
`(rule_id, group_key)`, holding the group's `group_version`, `last_activity_at`, and — once evaluated —
`computed_version`, `computed_at` and `computed_truncated`. This state SHALL be the runner's only record
of a group's progress: ADAS's write-back allow-list rejects `_`-prefixed and non-declared columns, so
group version and truncation SHALL NOT be sent as enrichment-row provenance.

#### Scenario: A newly seen group gets a state row

- **GIVEN** a group rule and a scanned page containing rows for a group key not yet in `group_state`
- **WHEN** the page is committed
- **THEN** a `group_state` row exists for that `(rule_id, group_key)` with a `group_version`, a
  `last_activity_at`, and a null `computed_version`

### Requirement: Dirty-mark rides the scan cursor

For a group rule the runner SHALL derive per-group state from the same rule-scoped scan page the row
path uses, so the cost of detecting change is proportional to the number of **new rows** and not to the
number of groups. Each scanned row SHALL contribute its `group_by` value as the group key and the
source's version column — projected under its own physical name — as both the version and the activity
timestamp. A group's `group_version` SHALL be the maximum version seen for it, expressed as epoch
milliseconds, and `last_activity_at` SHALL likewise only move forward, so replaying a page or
re-driving an older window SHALL NOT move a group backwards. A row missing its group key SHALL be
skipped, and a row whose version cannot be interpreted SHALL be dead-lettered at stage `input_map`
rather than silently dropped.

#### Scenario: A group's version is the maximum seen

- **GIVEN** a page with three rows for one group key at increasing versions
- **WHEN** the page is committed
- **THEN** the group's `group_version` is the largest of the three, as epoch milliseconds

#### Scenario: Re-driving an older window does not move a group backwards

- **GIVEN** a group whose `group_version` reflects a recent member
- **WHEN** a page carrying only older members of that group is committed
- **THEN** the group's `group_version` and `last_activity_at` are unchanged

#### Scenario: An uninterpretable version dead-letters only its row

- **GIVEN** a page in which one row's version column cannot be interpreted as a timestamp
- **WHEN** the page is committed
- **THEN** that row produces a `dlq_item` at stage `input_map`, the page's other rows still mark their
  groups, and the cursor advances

### Requirement: Atomic dirty-mark and cursor advance

The runner SHALL commit a group page's state upserts, its dead-letter items, and the advance of
`rule_cursor` to the scan's `next_cursor` in a single transaction — all committing together or none.
Because the upsert takes the maximum rather than the latest value, repeating a committed page SHALL
leave the state unchanged and SHALL NOT create duplicate rows.

#### Scenario: State and cursor commit together

- **WHEN** a group page commits successfully
- **THEN** the page's group state is persisted and the cursor is advanced to `next_cursor`

#### Scenario: A failed commit rolls back both

- **WHEN** the commit fails
- **THEN** neither the group state nor the cursor advance is persisted

#### Scenario: A repeated tick creates no duplicate state

- **GIVEN** a page already committed for a cursor-before
- **WHEN** the same range is scanned and committed again
- **THEN** the affected groups' state is identical and no additional `group_state` rows exist

### Requirement: Readiness sweep enqueues dirty and ready groups

The runner SHALL run a scheduled sweep, independent of the scan, that enqueues one work item per group
which is both **dirty** and **ready**. A group is dirty when its `group_version` exceeds its
`computed_version`, or it has never been computed. A group is ready when `now − last_activity_at` has
reached the rule's `ready_when.idle`, or `now − computed_at` has reached its `ready_when.max_staleness`.
Readiness thresholds SHALL be read from the rule as synced, not stored per group. Each pass SHALL be
bounded by a configured batch size so a large dirty backlog cannot monopolise one pass, and SHALL honour
the same rule-lifecycle, pause and spend gates as the scan. Time SHALL be read through an injected
clock.

#### Scenario: An idle group is enqueued once per version

- **GIVEN** a dirty group whose last activity is older than the rule's `idle`
- **WHEN** the sweep runs twice without the group changing
- **THEN** exactly one work item exists for that group and version

#### Scenario: A group still receiving members is not enqueued

- **GIVEN** a dirty group whose last activity is more recent than the rule's `idle`
- **WHEN** the sweep runs
- **THEN** no work item is enqueued for it

#### Scenario: A stale computed group is re-enqueued regardless of activity

- **GIVEN** a group whose `computed_at` is older than the rule's `max_staleness` and which is dirty
- **WHEN** the sweep runs
- **THEN** a work item is enqueued for it even though it has seen no recent activity

#### Scenario: A clean group is never enqueued

- **GIVEN** a group whose `computed_version` equals its `group_version`
- **WHEN** the sweep runs
- **THEN** no work item is enqueued for it

#### Scenario: A pass is bounded by its batch size

- **GIVEN** more dirty and ready groups than the configured per-pass batch size
- **WHEN** the sweep runs once
- **THEN** at most the batch size of work items is enqueued and the remainder is left for the next pass

### Requirement: Deterministic group work-item identity

A group work item's instance id SHALL be derived from the rule id, evaluator version, group key and
`group_version`, so re-sweeping an unchanged group produces the same id and is suppressed, while a
changed group yields a higher version and therefore a new item. The id SHALL retain the rule-id prefix
the backpressure count relies on, so a group rule's in-flight depth is countable without deserializing
payloads.

#### Scenario: An unchanged group is not enqueued twice

- **WHEN** the same rule, evaluator version, group key and group version are enqueued again
- **THEN** the deterministic id dedups and no additional work item is created

#### Scenario: A changed group re-fires

- **GIVEN** a group already enqueued at one version
- **WHEN** a new member raises its `group_version` and the sweep runs
- **THEN** a distinct work item is enqueued for the new version

### Requirement: Group-key sampling

For a group rule the runner SHALL apply the rule's `sampling` to the **group key** rather than to each
row, so the decision is deterministic and monotone at the grain the work is keyed by. A sampled-out
group SHALL NOT be marked and SHALL therefore hold no state, while the cursor still advances past its
rows.

#### Scenario: A sampled-out group holds no state

- **GIVEN** a group rule with a sampling fraction that excludes a given group key
- **WHEN** a page containing only that group's rows is committed
- **THEN** no `group_state` row exists for it and the cursor has advanced

### Requirement: Bounded group state through eviction

The runner SHALL run a scheduled eviction that deletes `group_state` rows for groups quiet longer than
the rule's `max(idle, max_staleness)` plus a configured margin, so the table stays bounded under
high group-key cardinality. A dirty group SHALL NOT be evicted, since that would discard pending work.
Evicting a clean group SHALL be safe: a later member re-creates the row with a `group_version` above the
discarded `computed_version`, so the group re-fires as a change.

#### Scenario: A long-quiet computed group is evicted

- **GIVEN** a clean group whose last activity is older than its rule's retention window plus the margin
- **WHEN** the eviction runs
- **THEN** its `group_state` row is deleted

#### Scenario: A dirty group is never evicted

- **GIVEN** a dirty group whose last activity is older than the retention window
- **WHEN** the eviction runs
- **THEN** its `group_state` row is retained

### Requirement: Supported readiness declarations

The runner SHALL support `ready_when.idle` and `ready_when.max_staleness`, and SHALL refuse a group rule
whose readiness depends on `ready_when.signal`. ADAS projects the raw columns that predicate references
rather than a computed result, so honouring it would require a second implementation of ADAS's bounded
predicate grammar, whose divergence would silently mark the wrong groups ready. The refusal SHALL name
`signal` as the reason. `ready_when.cost_ceiling` SHALL be accepted and not yet enforced.

#### Scenario: A rule declaring a signal is refused

- **WHEN** a sync returns a group rule whose `ready_when` includes `signal`
- **THEN** the rule is refused with `signal` named as the reason, is absent from the rule cache, and has
  no recurring task instance

#### Scenario: A rule declaring only idle and staleness is executed

- **WHEN** a sync returns a group rule whose `ready_when` declares `idle` and `max_staleness` but no
  `signal`
- **THEN** the rule is cached and its recurring task instance is created
