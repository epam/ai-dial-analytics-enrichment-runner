## Why

Two gaps remain before the row lane is feature-complete: there is no visibility or cap on model spend,
and no way to (re)enrich a rule's history. This change adds best-effort spend
accounting with a soft budget gate, and a full-replay backfill that runs in its own priority lane so
it never starves live scans.

## What Changes

- **Spend counter**: the worker records per-DIAL-call usage (calls + tokens, and failures) into
  `spend_counter` keyed by (rule, evaluator version, UTC day) via a race-free Postgres UPSERT.
- **Spend-pause gate**: `SpendPauseGate` skips a rule's scan enqueue when its daily calls/tokens
  exceed the configured budget; soft — re-evaluated each fire, auto-resuming when the UTC day rolls.
- **Priority lanes**: the scheduler enables db-scheduler priority; live chunks enqueue at a higher
  priority than backfill chunks, keeping backfill from starving live scans.
- **Backfill**: `POST /v1/rules/{id}/backfill` starts a full-replay run (scan from the source start,
  cursor omitted) tracked in `run_history` with its own cursor, independent of the live
  `rule_cursor`. A `backfill-run` task drains it batch-by-batch, enqueuing chunks at backfill
  priority. Runs are inspectable and pausable/resumable/cancellable
  (`GET`/`pause`/`resume`/`cancel` under `/v1/backfills/{runId}`).
- **Refactor**: row processing (sampling + input mapping + chunking) is extracted into a shared
  `ChunkBuilder` used by both the live scan and the backfill.
- **Config**: `spend.pause.enabled`, `spend.budget.daily-calls/daily-tokens`.

## Capabilities

### New Capabilities
- `spend-tracking`: best-effort per-rule/day usage accounting and the soft daily-budget pause gate.
- `backfill`: full-replay re-enrichment of a rule's history on its own cursor and priority lane,
  pausable/resumable/cancellable.

### Modified Capabilities
<!-- none: extends incremental-scan/enrichment-worker without changing their spec'd behavior -->

## Impact

- New code: `spend.*` (`SpendService`, `SpendPauseGate`, `DailySpend`), `backfill.*`
  (`RunKind`/`RunState`, `BackfillService`, `BackfillExecutor`, `BackfillTask`,
  `BackfillPageCommitter`), `RunHistoryEntity` + repo, `scan.ChunkBuilder`/`ChunkBatch`,
  `web/controller/BackfillController` + DTO/mapper, `SpendProperties`, `BackfillRunNotFoundException`.
- `WorkerService` records spend; `RuleScanExecutor` gains the spend gate and uses `ChunkBuilder`;
  `EnrichChunkTask` gains priority lanes; `DbSchedulerConfiguration` enables priority and registers
  the backfill task.
- No new migration (`run_history`/`spend_counter` exist from `V2`).
