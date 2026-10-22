## Context

`BackfillExecutor.run` reads the run's cursor, calls `AdasScanClient.scan(ruleId, cursor, limit)`, builds
chunks with `ChunkBuilder`, and commits them at backfill priority. Every part of that is row-and-Class-2
shaped. The group and Class-1 work added two more lanes to the live path — a group rule dirty-marks instead of chunking, a
Class-1 rule materializes instead of scanning — and backfill learned about neither, so pointing a backfill at
either kind of rule does the wrong thing rather than nothing.

Separately, the run is always a full replay: `BackfillService.start` sets `cursor = null` and there is no way
to bound it. The recurring real need is the opposite — an expression changed and a bounded range needs
re-deriving.

ADAS already supports the bound. `ScanRequest` and `MaterializeRequest` both take `from`/`to` as half-open
`(from, to]` on the source's version column, ANDed with the keyset and clamped by the safety-lag horizon, and
`from >= to` is a `400`. So the window is a pass-through, not a negotiation.

## Goals / Non-Goals

**Goals:** bound a backfill to a version window; make backfill work for all three rule kinds rather than one;
record the window so a run is self-describing; validate the window at the runner's own API rather than
discovering it at ADAS; and leave an unbounded backfill behaving exactly as it does today.

**Non-Goals:** scheduling a backfill to start later; more than one concurrent backfill per rule; changing
backfill priority or its independent cursor; and inventing a runner-side window vocabulary different from
ADAS's.

## Decisions

- **Backfill routes by the same predicates as the live path**, via `RuleRouting`. A row rule scans and chunks,
  a group rule dirty-marks, a Class-1 rule materializes. *Rejected:* a separate backfill task per kind — the
  run lifecycle (state machine, cursor, pause/resume, chaining) is identical across all three and only the
  page disposition differs, which is exactly the shape the live scan tick already uses.

- **A group backfill re-drives the dirty-mark and stops there.** It does not enqueue group work items itself:
  marking a group dirty over the window is sufficient, because the readiness sweep is what decides *when* a
  dirty group is worth evaluating, and it applies the rule's `idle`/`max_staleness`/`cost_ceiling` exactly as
  for live marks. Enqueueing directly from backfill would bypass all three. *Rejected:* backfill enqueuing
  groups at backfill priority (duplicates the sweep's gating logic and would defeat `add-group-cost-ceiling`'s ceiling).

- **A Class-1 backfill is the materialize loop with a window.** No queue, no chunking, no DLQ — the same
  "advance the run cursor on 2xx" the live tick uses. This is also the mechanism for re-applying an
  expression-version bump to history, which is worth naming because nothing else in the runner does it.

- **`from`/`to` are the runner's window vocabulary because they are ADAS's.** Both endpoints already take them
  with the same meaning; inventing a different shape (a duration, an offset, a page range) would need
  translating and would drift. The runner passes them through and validates only what it can validate locally.

- **`from >= to` is rejected at the runner's API, not at ADAS.** ADAS returns `400 bad_request` for it, but
  waiting for that means the run is created and then fails on its first batch, leaving a `run_history` row for
  a run that never could have worked. Rejecting at `start` keeps the failure where the operator's mistake is.

- **The window lives on `run_history`, not only in the request.** A run is inspected after the fact — often to
  answer "did we already re-derive that range?" — and a window that exists only in the caller's shell history
  cannot answer it. Two nullable columns; null means unbounded, which is what every existing run was.

- **An unbounded backfill is the default and is unchanged.** The window is optional at every layer, and a null
  window means the predicates are simply absent from the ADAS request. Existing callers, tests and runs keep
  working without modification — the milestone adds a capability rather than migrating one.

- **The window bounds the keyset; it does not replace the cursor.** A bounded backfill still advances its own
  cursor batch by batch and still resumes from it, so pause/resume, the stall guard and the per-fire budget all
  behave as before. That is ADAS's semantics (`(from, to]` ANDed with the keyset) and the runner does not need
  its own notion of progress within a window.

- **`BackfillPageCommitter` gains the group-mark path rather than a sibling committer.** It already commits
  "page work + DLQ items + run cursor" atomically; a group page's work is marks instead of chunks, which is one
  branch, and duplicating the run-cursor bookkeeping into a second class would put the run's progress semantics
  in two places.

## Risks / Trade-offs

- **A window whose `to` exceeds the safety-lag horizon is silently clamped by ADAS** → so a backfill "to now"
  quietly stops short of now. Documented rather than worked around: the runner cannot know ADAS's horizon, and
  re-running the backfill later picks up what the clamp excluded, since the cursor is where it stopped.

- **A group backfill's effect is deferred and indirect** → marking groups dirty produces no immediate work, so
  an operator watching for enqueued items sees nothing until the sweep runs and the readiness windows are
  satisfied. That is correct (it respects idle, staleness and the ceiling) but surprising, so the run's
  completion means "the window has been marked", not "the groups have been evaluated". Stated in the spec.

- **A bounded backfill can overlap the live cursor** → both write the same grain, and the enrichment table's
  `ReplacingMergeTree` collapses the duplicate; the two use independent cursors and deterministic ids
  namespaced by run, so neither corrupts the other. This is the pre-existing situation for a full replay, not
  something the window introduces.

- **Three lanes in one executor makes it the widest class in the backfill package** → mitigated by keeping the
  per-lane work in the collaborators the live path already uses (`ChunkBuilder`, `GroupMarkBuilder`,
  `AdasMaterializeClient`), so the executor holds routing and run bookkeeping rather than logic.

- **A Class-1 backfill records `rows_written` from ADAS while a Class-2 one records rows scanned** → the same
  `rows_done` column with subtly different meanings per lane. Accepted and documented; both answer "how much
  has this run got through", which is what the field is for.
