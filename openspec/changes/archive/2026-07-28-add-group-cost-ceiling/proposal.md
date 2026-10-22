## Why

A group rule re-evaluates a group every time it changes. That is the correct semantics — a label must
track its group — but it means a chatty group costs one model call per change, and nothing currently
bounds that. `add-group-worker`'s design named the gap explicitly: `max_staleness` and the sweep interval are the only
throttles, so a group receiving a message a minute is re-labelled a minute at a time, indefinitely.

ADAS already lets a rule declare the bound: `ready_when.cost_ceiling` is a per-group daily evaluation
budget, validated positive at registration, described as one "the caller enforces". `add-group-dirty-mark` and `add-group-worker` parse it and
ignore it. This change enforces it.

It is deliberately the *last* piece of Track A rather than the first: the ceiling is only meaningful once
groups are actually being evaluated, and only measurable once the worker exists to count.

## What Changes

- **Per-group daily evaluation counter** on `group_state` (`evaluations_day`, `evaluations`), reset lazily
  when the UTC day rolls over. No new table: the counter belongs to the group, is read by the same sweep
  query that already filters per rule, and is evicted with the group it counts.
- **The readiness sweep gates on the ceiling.** A group that has reached its rule's `cost_ceiling` for the
  current UTC day is not enqueued, even if it is dirty and ready — and even if it is stale. Being stale is
  precisely the case the ceiling exists to stop from re-firing without limit.
- **The counter increments when the model is actually called**, not per enqueue or per attempt. A group
  that is empty, or that ADAS refuses at assembly, consumed no evaluation and SHALL not be charged for one.
- **A rule declaring no `cost_ceiling` is unlimited**, which is every rule today — so the default behaviour
  is unchanged and the feature is opt-in per rule, by the rule's author, in ADAS.
- **A blocked group is visible.** A gauge reports how many groups the last sweep pass declined on the
  ceiling, because a group that silently stops being evaluated is otherwise indistinguishable from a group
  that stopped changing.

## Capabilities

### New Capabilities

<!-- none: completes group-enrichment rather than introducing a capability -->

### Modified Capabilities

- `group-enrichment`: adds the per-group ceiling and its counter semantics, and revises "Readiness sweep
  enqueues dirty and ready groups" — dirty and ready is no longer sufficient, the group must also be within
  budget.
- `spend-tracking`: "Soft daily spend-pause gate" — notes that a group rule carries a second, finer budget
  enforced per group key, so the rule-level gate is not the only spend control a reader should look for.
- `service-runtime`: "PostgreSQL runtime-state schema" — `group_state` gains the two counter columns via
  `V5`.

## Impact

- **New code**: none beyond a properties addition; the change is concentrated in existing collaborators.
- **Modified code**: `persistence/GroupStateWriter` (the ceiling clause in the dirty-and-ready query, and
  the counter increment), `sweep/GroupReadinessSweep` (pass the rule's ceiling and the current UTC day),
  `worker/group/GroupWorkerService` (charge an evaluation when the model is called),
  `sync/RuleRouting` (expose the declared ceiling), `observability/RunnerMetrics` (the blocked gauge).
- **Migration**: `V5__group_cost_ceiling.sql` — two nullable/defaulted columns on `group_state`. Additive,
  backwards-compatible, no backfill: an existing group starts the day it is next evaluated.
  `add-group-dirty-mark` deliberately kept these out of `V3` rather than shipping columns nothing read.
- **ADAS contract**: none. `ready_when.cost_ceiling` is already synced and already validated positive;
  this change is entirely runner-side enforcement of a value ADAS says the caller owns.
- **Configuration**: none. The ceiling is per-rule, declared in ADAS — there is deliberately no global
  default, because a number that bounds one rule's groups is meaningless for another's.
- **Not here**: cost or pricing of any kind (counts only, by design); a rule-level group
  budget, which the existing `SpendPauseGate` already covers; and any interaction with `run_history`.
