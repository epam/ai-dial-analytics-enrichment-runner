## Context

The runner has one lane: `RuleSyncService` reconciles every enabled rule onto a single `rule-scan`
recurring task, whose executor drains scan pages into `enrich-chunk` work items that call DIAL. ADAS
now introduces a second kind of rule — `evaluator.type=sql`, Class-1 — for which that lane is not
merely inefficient but rejected: `ScanService` throws `422 rule_validation_failed` for a `sql` rule
("use POST /v1/rules/{id}/materialize instead of the row scan"), and symmetrically `MaterializeService`
422s an `llm` rule. Routing is therefore not an optimisation; it is a correctness requirement.

The as-built ADAS contract, read from the running instance's `/v3/api-docs`:

- `POST /v1/rules/{id}/materialize` body `{cursor?, limit?, from?, to?}` → `{rows_written,
  next_cursor, has_more, lag_seconds}`, all snake_case. `rows_written` and `has_more` are primitives
  and always present; `next_cursor` and `lag_seconds` are omitted when null.
- ADAS **probes** the window (a cheap two-column keyset scan) and then writes **exactly** the probed
  range with one `INSERT … SELECT`, so the window is chosen before it is executed.
- An empty window returns `rows_written: 0`, `has_more: false`, and echoes the caller's cursor
  unchanged — omitting `next_cursor` entirely when the caller sent none.
- `limit` above `enrichment.materialize.max-limit` (100000) is a **400, not a clamp**.
- Provenance (`evaluator_version`, `enriched_at`) is stamped server-side inside the INSERT; `model`
  is skipped because there is no model. The runner writes no rows and sends no provenance.
- A `sql` evaluator carries only `{name, version, type, output_vars[{name, type, sql}], created_at}`.

## Goals / Non-Goals

**Goals:** route a rule to the correct lane from `evaluator.type`; drive a Class-1 rule's backlog
window-by-window under a per-fire budget; advance `rule_cursor` only on a successful response; keep
Class-1 entirely outside the queue, DIAL client, rate limiter, spend counters and DLQ; make a rule the
runner cannot execute visibly skipped rather than silently failing every fire; and remove the
duplicated evaluator-version resolution while adding the routing predicates, so the new lanes do not
multiply that duplication.

**Non-Goals:** group-grain Class-1 (ADAS rejects `sql` + `group` at registration, so it cannot exist);
bounded `[from, to]` windows on the materialize call (the DTO carries the fields, but driving them is
`add-bounded-window-backfill`); the full Class-1 metric set and tracing spans (`add-phase2-ops`);
cost or pricing of any kind; and per-column null-rate monitoring of malformed-body defaults, which is
an ADAS-side concern.

## Decisions

- **Class-1 is a distinct tick, not a worker path.** The tick is a loop around one HTTP call: read
  cursor → materialize → persist cursor → repeat. *Rejected:* threading `sql` through
  `EnrichChunkTask` behind a conditional, which would carry chunking, rate limiting, repair retries
  and a DLQ that this path has no use for, and would make every Class-2 change reason about a
  no-model case.

- **`RuleRouting` is a static helper, not a strategy hierarchy.** Two rules kinds and three lanes do
  not justify polymorphism; a single class holding `isClass1`, `isGroup`, `evaluatorVersion` and
  `unsupportedReason` keeps the predicates in one place and lets `RuleSyncService`, the executors and
  the mappers agree by construction. It also absorbs the evaluator-version resolution that is
  currently copy-pasted into `RuleScanExecutor`, `WorkerService`, `BackfillExecutor` and
  `RuleCacheMapper` — the same duplication routing would otherwise reproduce.

- **A separate recurring task, not a branch inside `RuleScanExecutor`.** Unlike the group path (which
  shares the scan endpoint, the cursor, the drain loop and every gate, and so will branch inside the
  executor), Class-1 shares only the cursor: a different endpoint, a different response shape, no
  chunking, no backpressure signal and different gates. A `class1-materialize` task keeps each
  executor's control flow readable. Both tasks use `Tasks.recurringWithPersistentSchedule` with the
  rule id as the instance id, so `ScheduleFactory` and the reconciliation shape are reused unchanged.

- **Advance the cursor on success, with no transaction.** The row lane's scan tick needs
  enqueue-and-cursor atomicity because the work it commits is *queued elsewhere*; a Class-1 window is
  already written when the response returns. A crash between ADAS's INSERT and the cursor persist
  replays the same window on the next fire, which is a no-op in effect: ADAS re-probes an identical
  range and the target's `ReplacingMergeTree`, read with `final=1`, collapses the duplicate. Plain
  `save()` on `RuleCursorEntity` is therefore correct, and cheaper than pretending otherwise.

- **Reuse the row lane's drain semantics verbatim**: `scan.max-pages-per-fire` bounds the windows per
  fire, and the cursor-stall guard (`next_cursor` null or unchanged while `has_more` is true) halts
  the drain rather than busy-re-materialising the same range. A backlog therefore catches up over
  successive fires instead of advancing one window per cadence. An empty window is the natural stop:
  `has_more=false` breaks the loop, and because ADAS echoes the input cursor there is nothing to
  persist.

- **Reuse `rule_cursor`, and never reset it on an empty response.** `next_cursor` is *absent* rather
  than null on an empty first window, so the client must treat absent as "keep what I have". Writing
  a null cursor back would restart the rule from the source on the next fire.

- **Class-1 is excluded from `SpendPauseGate` and `DialRateLimiter`, not zeroed.** A zero-token
  counter would make a Class-1 rule indistinguishable from a broken Class-2 rule in the spend tables,
  and a rule-level daily call budget expressed in DIAL calls has no meaning for a path that makes
  none. Progress accounting instead flows into the existing `run_history.rows_done` from
  `rows_written`, which already permits a `rows` usage unit.

- **Unexecutable rules are refused at sync, not at tick time.** `RuleRouting.unsupportedReason`
  returns a human-readable reason; `RuleSyncService` logs it, counts it, and neither caches nor
  schedules the rule. Caching it instead would produce a rule that looks healthy in
  `GET /v1/rules/cache` while failing on every fire. The gate is deliberately conservative: it
  rejects only what the runner provably cannot execute, so an unrecognised-but-harmless future field
  does not disable a rule.

- **Task migration on a type change.** A rule whose evaluator type flips across a generation bump has
  a stale instance under the other task name. Reconciliation fetches scheduled instances for **both**
  task names and cancels any instance whose rule is absent from that task's desired set — so the
  migration falls out of the existing cancel pass rather than needing a special case. The cursor is
  deliberately left untouched: the rule's position in its source has not changed.

## Risks / Trade-offs

- **A long materialize ties up an ADAS request thread** → bound the window with `class1.default-limit`
  and rely on the existing ADAS read timeout; drain many small windows per fire rather than
  requesting one large one. The `limit` is also a hard ADAS-side ceiling (400 above 100000), so the
  default is set an order of magnitude below it.

- **A persistently failing Class-1 rule is quiet** → there is no DLQ to inspect and no queue depth to
  alarm on, by design: the failure modes a DLQ exists for (expensive non-idempotent calls, poison
  rows that never succeed on replay) do not occur on a set-based deterministic path. Visibility rests
  on the lag gauge and a tick-failure counter, and full metric coverage is `add-phase2-ops`. Until then
  the tick logs each failure at `warn` with the rule id.

- **The admission gate could hide a rule from an operator** → the skip is logged with its reason and
  counted, and the rule's absence from `GET /v1/rules/cache` is itself the signal. The alternative
  (schedule it anyway) trades a visible skip for an invisible per-fire 422.

- **Removing the duplicated evaluator-version resolution touches four classes outside this
  milestone's feature area** → it is a pure extract with no behaviour change, covered by the existing
  tests for each caller, and doing it now is what stops the new lanes from adding a fifth and sixth copy.

- **`RuleScanExecutor`'s constructor and `ScanProperties` are positional** → adding routing to the
  executor breaks `RuleScanExecutorTest`'s 12-argument hand-wired construction. Accepted: the test is
  updated in the same change rather than working around the shape.
