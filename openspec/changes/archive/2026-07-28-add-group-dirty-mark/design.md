## Context

The row lane's scan tick reads a page, turns every row into work, and commits the work plus the cursor
advance in one transaction. That shape assumes the unit of work is the row. A group rule breaks the
assumption: a page's rows are *evidence about groups*, and the decision to evaluate a group depends on
time and on rows that have not arrived yet.

The completeness argument below is the crux, and it is why this change exists separately from the
worker. "Group changed" and "group ready" must be decoupled:

- **Dirty-mark** answers "changed". It rides the scan cursor, so its cost is proportional to **new
  rows** — the same cost the row path already pays.
- **Readiness sweep** answers "ready". It runs on its own schedule against runner-owned state, so it
  never re-reads the source and never asks ADAS about groups it is not going to evaluate.

Coupling them (deciding readiness while scanning) would either miss groups whose last member already
arrived — nothing later dirties them, so nothing re-examines them — or force a periodic full re-read
costing work proportional to the number of groups.

As-built ADAS facts this design depends on, read from the running instance:

- The group projection comes from the **same** `POST /v1/rules/{id}/scan`. For a group rule the page
  additionally carries `group_by`, the columns `ready_when.signal` references, and the source's version
  column **under its own physical name** — `_ingested_at` for `dial_usage_log`, an ISO-8601 string.
  There is no separate activity field, and no computed readiness flag.
- `ready_when` is `{signal, idle, max_staleness, cost_ceiling}`. ADAS requires at least one of `signal`,
  `idle`, `max_staleness`, validates `signal` under its bounded Calcite grammar, and accepts `idle` /
  `max_staleness` in both Spring short form (`10m`) and ISO-8601 (`PT24H`).
- `group_by` is validated to equal the target enrichment's grain key, so for a group rule
  `group_by == grain_key`.
- ADAS's write-back allow-list rejects any `_`-prefixed column and anything outside the rule's declared
  outputs plus `{evaluator_version, model, enriched_at}`. **`_group_version` and `truncated` cannot be
  written**, which is why group provenance lives in `group_state` and nowhere else.

## Goals / Non-Goals

**Goals:** make a group rule executable end-to-end up to the point of enqueue; keep dirty-mark cost
proportional to new rows; preserve the row lane's atomicity guarantee (state and cursor commit together, a
repeated tick creates neither duplicate state nor duplicate work); give a group work item an identity
that makes a re-swept unchanged group a no-op; bound `group_state` under high group-key cardinality; and
be explicit rather than silent about the `ready_when` subset the runner supports.

**Non-Goals:** `ready_when.signal` (see below); fetching members, calling the model or writing rows
(`add-group-worker`); enforcing `cost_ceiling` (`add-group-cost-ceiling`); multi-replica sweeping — the sweep assumes the single active
replica the runner already assumes; and 1:N group values or map-reduce over long groups, both deferred.

## Decisions

- **`ready_when.signal` is refused, not approximated.** ADAS projects the raw columns the predicate
  references, so evaluating it runner-side means implementing a second parser for ADAS's bounded grammar
  — comparisons, `AND`/`OR`/`NOT`, `IN`, `IS NULL`, literals — and keeping the two semantically identical
  as that grammar widens. A divergence would not fail loudly; it would silently mark the wrong groups
  ready. *Rejected:* a partial evaluator handling "the easy predicates" (worse — it decides which rules
  are silently wrong); ignoring `signal` while honouring `idle` (worst — the rule looks supported and
  fires late). The named follow-up is an ADAS-side synthetic boolean in the scan projection, which keeps
  one grammar implementation and reduces the runner to reading a flag.

- **`group_version = max(_ingested_at)` as epoch millis.** The reference plan said `max(_ingest_seq)`;
  that column does not exist. `_ingested_at` is the catalog's version column, it is what the opaque
  cursor encodes, and it is already projected on every page — so the version the runner derives and the
  order ADAS scans in cannot disagree. Epoch millis keeps it a `BIGINT`, monotone and directly
  comparable. *Rejected:* a composite `count + max`, which is immune to two members sharing a
  millisecond across two ticks but makes every version comparison two-field; and a runner-assigned
  counter, which is collision-free but decoupled from the data, so a backfill re-scan would inflate it
  and re-fire groups that never changed. The `count + max` fallback stays documented for when a
  same-millisecond split across ticks is actually observed.

- **The version column is named by configuration, pending an ADAS field.** The projected row carries the
  source's version column under its own physical name, but the sync feed does not say what that name is:
  `RuleDto` exposes `source` and `grain_key`, not `version_column`, even though ADAS's S1 made version
  columns catalog-driven *per source*. So `group.version-column` names it, defaulting to `_ingested_at`.
  This is a real single-source limitation and is documented as one. *Rejected:* inferring it as the row's
  sole `_`-prefixed key — it happens to hold today (ADAS strips its internal `__cursor_*` keys before
  responding) but would break silently, not loudly, on a source whose version column is named otherwise.
  The follow-up is a one-field addition of `version_column` to ADAS's `RuleDto`, after which this config
  key is redundant and should be removed rather than kept as a override.

- **The dirty-mark reuses the `rule-scan` tick; it is a page-disposition branch, not a new task.** A
  group rule's dirty-mark read is the same endpoint, the same `rule_cursor`, the same drain loop, the
  same per-fire budget, the same cursor-stall guard and the same gates. Only what the page becomes
  differs. A second recurring task would duplicate `RuleScanExecutor` wholesale for one changed line.
  This is the opposite call from Class-1 (`add-class1-materialize`), which got its own task precisely because it shares only
  the cursor — different endpoint, different response, nothing to chunk.

- **State upsert and cursor advance commit together**, reusing `ScanPageCommitter`'s pattern: a
  `@Transactional` committer whose `scheduleIfNotExists` calls join the Spring transaction through the
  `TransactionAwareDataSourceProxy`. A crash mid-page therefore re-scans the same range, and the upsert
  is written as `GREATEST(existing, incoming)` so replaying it is idempotent rather than merely
  harmless.

- **The upsert takes the maximum, never the latest.** `group_version` and `last_activity_at` both move
  only forward (`ON CONFLICT … DO UPDATE SET group_version = GREATEST(...)`). Ordering within a page is
  ADAS's, but a *backfill* may re-drive an older window over a group that has since advanced, and
  last-write-wins there would move a group backwards and re-fire it.

- **Two-phase sweep** (select dirty, then filter ready) with an indexed dirty predicate on
  `(rule_id, group_version, computed_version)`. The readiness
  thresholds are per-rule and live in `RuleCache`, not in `group_state`, so the sweep iterates the cached
  group rules and queries state per rule — which is also what keeps the SQL free of per-rule literals.
  Single-pass remains the fallback if measurement at many active groups says otherwise.

- **Group work-item identity is `(rule, evaluator_version, group_key, group_version)`**, hashed behind
  the existing `ruleId#` prefix so the backpressure query — which counts a rule's in-flight items by
  instance-id prefix — keeps working unchanged. `scheduleIfNotExists` then makes a re-sweep of an
  unchanged group a no-op, and a changed group gets a higher version and therefore a new id.

- **Sampling is applied to the group key, not the row key.** The work is keyed by group, so sampling by
  group is what keeps the decision deterministic and monotone at the grain that matters; sampling rows
  would admit a group on one tick and not the next. A sampled-out group is never marked, so it costs no
  state.

- **Eviction is time-based and dirty-aware**: delete rows whose `last_activity_at` is older than
  `max(idle, max_staleness)` plus `group.eviction-margin`, **and** which are not dirty. Evicting a dirty
  group would silently drop pending work; evicting a clean, long-quiet group is safe because a later
  member re-creates the row, and its `group_version` will exceed the `computed_version` that was
  discarded — so re-creation re-fires it exactly as a change should.

- **Group provenance stays runner-side.** `computed_version`, `computed_at` and `computed_truncated` are
  columns on `group_state`, written by `add-group-worker`. ADAS rejects them on write-back, so there is no way to make a
  downstream ClickHouse query distinguish a truncated group's label from a complete one; `add-phase2-ops`'s admin
  endpoint is the only place it becomes visible. The named follow-up is extending ADAS's
  `ProvenanceColumns` with non-underscore `group_version` / `truncated`.

- **`V3` carries only what this change uses.** The plan sketched the `add-group-cost-ceiling` cost-ceiling counter columns into this
  migration; they are left out. Shipping columns nothing reads invites the reviewer's question, and if
  `add-group-cost-ceiling` were reordered or dropped the dead schema would remain. `add-group-cost-ceiling` adds its own migration.

- **`Clock` is injected rather than `Instant.now()` inline** in the sweep and the evictor. There is no
  test clock in the suite today, and `idle` / `max_staleness` are not testable without one — the
  alternative is sleeping in tests, which is slow and flaky.

## Risks / Trade-offs

- **A group rule whose readiness is `signal` alone is registerable in ADAS but not runnable here** → the
  refusal is logged with its reason, published on the `runner.rules.unsupported` gauge, and the rule's
  absence from `GET /v1/rules/cache` is itself the signal. The alternative — a second grammar
  implementation — trades a visible refusal for silent wrongness.

- **`group_state` growth at high group-key cardinality** → eviction tied to
  `max(idle, max_staleness)` plus a configurable margin, with the active-group count exposed as a gauge
  in `add-phase2-ops`. The row is small and the table is indexed for the sweep's predicate.

- **Sweep scan cost at many active groups** → indexed dirty predicate, per-pass batch bound, and
  a per-rule query rather than one global scan. Measure before optimising; single-pass is the fallback.

- **`group_version` collisions within a millisecond** → only matters when two members of one
  group share a millisecond *and* land in different ticks, which loses the later member until something
  else dirties the group. `count + max` is the documented fallback.

- **A late-arriving member after a group was computed** re-dirties it and the group is evaluated again,
  producing a second model call and overwriting the row → intended, and the reason the enrichment table
  is an RMT keyed by group key. It does mean a chatty group can be evaluated repeatedly, which is exactly
  what `add-group-cost-ceiling`'s `cost_ceiling` exists to bound.

- **`_ingested_at` is parsed from a projected value the runner does not control** → handled defensively
  (ISO-8601 string or epoch-millis number), and a row whose version cannot be parsed is dead-lettered at
  `input_map` rather than silently skipped, so a source whose version column is shaped differently
  surfaces as DLQ items instead of a group that never fires.

- **A group rule over a source whose version column is not `group.version-column` fails visibly, not
  silently** → every row misses the configured key, so every row dead-letters at `input_map` and the
  group never fires. That is loud (DLQ items accumulate, the circuit breaker eventually pauses the rule)
  and is the intended failure mode until ADAS surfaces `version_column`; a second source in the meantime
  needs the key set, and cannot differ per source.

- **The sweep is single-replica by construction** → consistent with the runner's stated single-replica posture; two
  replicas sweeping concurrently would both enqueue, and `scheduleIfNotExists` on a deterministic id
  makes that a duplicate-suppressed no-op rather than duplicate work, but the cost accounting in `add-group-cost-ceiling`
  would double-count. Multi-replica stays out of scope.
