## Why

The runner shipped with a single execution path — scan rows, chunk, enqueue, call DIAL, validate, upsert —
which assumes every rule needs a model call per row. ADAS now registers **Class-1 rules**
(`evaluator.type=sql`): deterministic, row-grain derivations whose values ADAS computes set-based
inside ClickHouse via `POST /v1/rules/{id}/materialize`. Such a rule needs *none* of the Class-2
machinery, and today the runner would mis-route it into the row scan — which ADAS answers with
`422 rule_validation_failed`, so the rule simply never runs.

This change adds the Class-1 lane. It is the cheapest of the new lanes
and shares no code with the group-grain work beyond `RuleCache` and `rule_cursor`, so it lands first.

## What Changes

- **Evaluator type becomes a router**: `EvaluatorType` gains `SQL`, and `EvaluatorVarDto` gains the
  `sql` component that a Class-1 output var carries in place of `jsonata`. A `sql` evaluator arrives
  with **no model, no preset, no params, no request template and no response schema** — only output
  vars whose SQL expressions the runner never interprets.
- **New materialize client**: `MaterializeRequest`/`MaterializeResponse` DTOs and
  `AdasMaterializeClient` for `POST /v1/rules/{id}/materialize` → `{rows_written, next_cursor,
  has_more, lag_seconds}`. No row payload crosses the wire in either direction.
- **New recurring task**: `class1-materialize`, one instance per Class-1 rule, fired at the rule's
  `cadence`. Its tick is a drain loop around one HTTP call — read `rule_cursor`, materialize a
  window, persist `next_cursor` on success, repeat while `has_more` and the per-fire budget allows.
- **Type-aware rule reconciliation**: a new `RuleRouting` helper becomes the single source of the
  `sql` vs `llm` and row vs group predicates (also absorbing the evaluator-version resolution
  currently duplicated in four classes). `RuleSyncService` reconciles a Class-1 rule onto
  `class1-materialize` instead of `rule-scan`, cancels across **both** task names, and moves a rule
  that changes type across a generation bump from one task to the other.
- **Rule admission gate**: a rule the runner cannot execute (for now: a `sql` evaluator with no
  output vars, or a `sql` evaluator on the `group` trigger) is skipped at sync with a warning
  instead of being cached and scheduled into repeated failure.
- **Class-1 is excluded from spend and rate limiting**, not counted as zero: there are no model
  calls to budget or throttle. `SpendPauseGate` never gates a Class-1 rule.
- **No queue, no DIAL, no DLQ on this path.** A non-2xx fails the tick, leaves the cursor unmoved and
  retries on the next fire; correctness rests on ADAS probing the window before it writes it, so a
  replayed cursor re-executes an identical range that the target's `ReplacingMergeTree` collapses.

## Capabilities

### New Capabilities

- `class1-enrichment`: routing a rule by `evaluator.type`, the materialize tick and its drain budget,
  advance-the-cursor-only-on-success semantics, and the Class-1 failure model (no queue, no DLQ, no
  spend).

### Modified Capabilities

- `rule-sync`: "Recurring task reconciliation" — reconciliation is no longer against a single task
  name; it selects a task per rule from `evaluator.type`, cancels across both task names, and
  migrates a rule whose type changed. Adds an admission gate that refuses to cache or schedule a
  rule the runner cannot execute.
- `spend-tracking`: "Soft daily spend-pause gate" — the gate applies to model-calling rules only; a
  Class-1 rule is excluded from it rather than evaluated with a zero counter.

## Impact

- **New code**: `class1/` (`Class1MaterializeTask`, `Class1MaterializeExecutor`, `Class1Properties`),
  `adas/AdasMaterializeClient`, `adas/dto/{MaterializeRequest,MaterializeResponse}`,
  `sync/RuleRouting`.
- **Modified code**: `adas/dto/{EvaluatorType,EvaluatorVarDto}`, `sync/RuleSyncService`,
  `spend/SpendPauseGate`, `configuration/DbSchedulerConfiguration`, and the four classes whose
  duplicated evaluator-version resolution moves into `RuleRouting` (`scan/RuleScanExecutor`,
  `worker/WorkerService`, `backfill/BackfillExecutor`, `web/mapper/RuleCacheMapper`).
- **ADAS contract**: consumes the new `POST /v1/rules/{id}/materialize`; no ADAS change required (it
  is already shipped). Authorized by the same target-enrichment WRITE grant as scan and write-back.
- **Configuration**: new `class1.*` group (`CLASS1_DEFAULT_LIMIT`, `CLASS1_DEFAULT_CADENCE`)
  documented in `docs/configuration.md`.
- **No migration.** Class-1 reuses `rule_cursor` and `run_history`; it adds no tables, no queue rows,
  no `spend_counter` entries and no `dlq_item` rows.
- **Observability**: a Class-1 rule's progress is visible only through its lag gauge, rows-written
  counter and tick-failure counter — there is no queue depth or DLQ to inspect. Full metric coverage
  lands with `add-phase2-ops`.
