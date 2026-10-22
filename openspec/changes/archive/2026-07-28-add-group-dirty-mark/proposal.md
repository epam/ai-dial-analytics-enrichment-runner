## Why

ADAS now registers **group rules** (`trigger_kind=group`): a rule that fires once per group key —
one evaluation, one enrichment row — rather than once per source row. The runner currently refuses
every such rule at sync ("group trigger is not supported yet"), so a registered group rule never runs.

Executing one takes a piece of state the row lane has no equivalent of. A group's members arrive over time,
so "the group changed" and "the group is ready to evaluate" are different questions, and answering the
second by re-reading groups would cost work proportional to the **number of groups** on every tick. This
change lands the state and the two loops that keep that cost proportional
to **new rows** instead: a dirty-mark that rides the existing scan cursor, and a scheduled readiness
sweep that enqueues only groups that are both dirty and ready.

This change is the state layer. The worker that consumes what the sweep enqueues is `add-group-worker`, so the group
work item is declared here with a placeholder handler — the same way `add-incremental-scan` declared `enrich-chunk` before
`add-enrichment-worker` implemented it.

## What Changes

- **Group rules become executable**, replacing the blanket refusal. `RuleDto` gains `group_by` and
  `ready_when {signal, idle, max_staleness, cost_ceiling}`; the admission gate narrows to refusing only
  a group rule the runner still cannot execute.
- **`ready_when.signal` is deliberately not supported.** ADAS projects the *raw* columns its predicate
  references, not a computed boolean, so honouring `signal` would mean reimplementing ADAS's bounded
  Calcite grammar in a second codebase. This change supports `idle` and `max_staleness`; a rule declaring
  `signal` is refused at sync with that reason. **BREAKING** relative to what ADAS permits: ADAS accepts
  a group rule whose readiness is `signal` alone, and the runner will not run it.
- **New runner-owned table `group_state`** (Flyway `V3__group_state.sql`), one row per active group:
  `group_version`, `last_activity_at`, `computed_version`, `computed_at`, `computed_truncated`.
- **Dirty-mark tick**: for a group rule the existing `rule-scan` tick reinterprets its page — instead of
  building chunks it upserts per-group state, taking `group_version = max(_ingested_at)` as epoch millis
  over the rows seen, in the **same transaction** as the cursor advance. Cost is proportional to new
  rows, not to the number of groups.
- **Readiness sweep**: a scheduled pass selects groups that are **dirty** (`group_version >
  computed_version`, or never computed) **and ready** (`now − last_activity_at ≥ idle`, or
  `now − computed_at ≥ max_staleness`), bounded by a per-pass budget, and enqueues one work item per
  group with a deterministic id keyed `(rule, evaluator_version, group_key, group_version)`.
- **`group_state` eviction**: a scheduled sweep deletes non-dirty rows for groups quiet longer than
  `max(idle, max_staleness)` plus a margin, bounding the table at high group-key cardinality.
- **Sampling moves to the group key** on this path — a sampled-out group is never marked, keeping the
  decision deterministic and monotone at the grain the work is keyed by.
- **First injectable clock.** `idle` and `max_staleness` are time thresholds with no existing test
  fixture to lean on, so a `Clock` bean is introduced and the sweep reads time through it.

## Capabilities

### New Capabilities

- `group-enrichment`: the group grain — per-group state, the dirty-mark tick and its atomicity, the
  readiness predicate and its per-pass budget, deterministic group work-item identity, state eviction,
  and the runner's `ready_when` support boundary.

### Modified Capabilities

- `rule-sync`: "Rule admission gate" — a group rule is no longer refused outright; the gate now refuses
  only a group rule whose readiness the runner cannot evaluate (`signal`) or whose declaration is
  incomplete.
- `incremental-scan`: "Draining incremental scan" and "Atomic enqueue and cursor advance" — a page's
  disposition now depends on the rule's trigger kind, and for a group rule the transaction commits
  per-group state instead of chunk work items. "Deterministic, monotone sampling" — sampling is applied
  to the group key for a group rule.
- `service-runtime`: "PostgreSQL runtime-state schema" — adds `group_state`.

## Impact

- **New code**: `scan/group/` (`GroupMarkBuilder`, `GroupMarkBatch`, `GroupDirtyMarkCommitter`),
  `sweep/` (`GroupReadinessSweep`, `GroupStateEvictor`), `worker/group/GroupChunkTask` (declaration +
  placeholder handler), `persistence/{GroupStateEntity,GroupStateRepository}`,
  `configuration/{ClockConfiguration,GroupProperties}`, `adas/dto/ReadyWhenDto`.
- **Modified code**: `adas/dto/RuleDto` (+`groupBy`, +`readyWhen`), `sync/RuleRouting`
  (`unsupportedReason` narrows; group readiness helpers), `scan/RuleScanExecutor` (page disposition
  branches on trigger kind), `scan/DeterministicId` (+group instance id), `configuration/DbSchedulerConfiguration`.
- **Migration**: `V3__group_state.sql` — additive, one new table and two indexes. No change to existing
  tables, so it is backwards-compatible and needs no backfill.
- **ADAS contract**: consumes the group projection of the existing `POST /v1/rules/{id}/scan` (no new
  endpoint, no ADAS change). Group rows arrive carrying `group_by`, the `ready_when.signal` columns, and
  the source's version column under its physical name — `_ingested_at` for `dial_usage_log`.
- **Configuration**: new `group.*` group (`GROUP_SWEEP_INTERVAL`, `GROUP_SWEEP_BATCH`,
  `GROUP_EVICTION_MARGIN`, `GROUP_DEFAULT_IDLE`) documented in `docs/configuration.md`.
- **Not here**: fetching a group's members, calling the model, writing the enrichment row (`add-group-worker`); the
  per-group cost ceiling, which `ready_when.cost_ceiling` declares and the sweep will honour (`add-group-cost-ceiling`).
  `cost_ceiling` is therefore parsed and ignored here.
- **Test infrastructure**: the suite gains its first controllable clock, which `add-group-cost-ceiling` and any later
  time-threshold work reuse.
