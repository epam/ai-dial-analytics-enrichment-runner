## Context

This change adds spend visibility/capping and history re-enrichment. Both build on the existing scan/worker
pipeline; the backfill deliberately reuses the same rule-scoped scan (ADAS is stateless about the
cursor, so live and backfill scans are identical to it) and the same chunk-building logic.

## Goals / Non-Goals

**Goals:** best-effort spend counter + soft budget gate; full-replay backfill with its own cursor,
its own priority lane, and pause/resume/cancel.

**Non-Goals:** cost/pricing (usage only); bounded-window backfill `[from,to]` (full-replay-from-start
only — the ADAS scan endpoint has no range params); cluster-wide budgets.

## Decisions

- **Spend counter via Postgres UPSERT.** A single `INSERT ... ON CONFLICT DO UPDATE` increments the
  per-(rule, evaluator, UTC-day) row race-free, avoiding read-modify-write. Recorded per DIAL call
  (repair calls count too — they are real API calls); a non-retryable DIAL 4xx records a failure.
- **Soft budget gate, not a hard pause.** The gate re-checks the current-day counter each fire, so a
  rule auto-resumes at UTC midnight — unlike the DLQ circuit breaker's hard pause (which needs manual
  resume). This matches "skip enqueue when over budget".
- **Per-instance priority lanes.** db-scheduler priority is enabled; live chunks enqueue at a higher
  priority than backfill chunks on the same `enrich-chunk` task, so backfill never starves live. This
  needed per-instance priority (`instanceBuilder(...).priority(n)`), which db-scheduler supports.
- **Backfill as a self-continuing one-time task.** A `backfill-run` task drains a batch (bounded by
  the per-fire page budget), commits each page (enqueue + run-cursor advance in one tx), then either
  schedules the next batch (unique instance id namespaced by cursor) or marks the run done. A batch
  failure retries (resuming from the committed run cursor). Pause/cancel stop the chain because the
  next batch sees a non-running state and returns.
- **Shared `ChunkBuilder`.** Sampling + input mapping + chunking is extracted so the live scan and
  backfill apply identical logic; only the cursor sink and enqueue priority differ.

## Risks / Trade-offs

- **Spend is best-effort** → recorded outside the enqueue/upsert transactions; a crash can lose a
  counter increment. Acceptable — it drives a soft gate and dashboards, not correctness.
- **Backfill of a disabled rule stalls** → the runner's cache holds only enabled rules, so a backfill
  needs the rule enabled to resolve its evaluator/bindings; a missing rule stalls the run (resumable
  after re-enabling) rather than failing.
- **Paused run leaves a scheduled next-batch instance that no-ops** until resumed → cheap; the chain
  is re-armed on resume from the committed cursor.
