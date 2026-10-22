## Why

Backfill today has one shape: replay a rule's whole history from the source start. That is the wrong tool for
the case that actually recurs — an evaluator expression changed, and the fix needs re-applying to *last week*,
not to everything ever ingested. Re-driving the full history to correct a bounded range costs model calls
proportional to the source, not to the mistake.

It is also the only lane still stuck on the original row-only path. Backfill drives the row scan and builds
chunks, so a Class-1 rule's backfill would try to scan a source ADAS answers with `422` (use materialize), and
a group rule's would build chunks nothing consumes. Three lanes exist live; backfill knows about one.

ADAS is ready: `from`/`to` are already on both `ScanRequest` and `MaterializeRequest`, with identical
half-open `(from, to]` semantics clamped by the safety-lag horizon. This change
gives backfill the window and the other two lanes.

## What Changes

- **A backfill may be bounded** to a `[from, to]` version window, passed through to ADAS on every page. An
  unbounded backfill keeps working exactly as today, so the existing behaviour is the default.
- **`ScanRequest` gains `from`/`to`**, which `MaterializeRequest` already has — one window vocabulary for both
  reads, because they mean the same thing on ADAS's side.
- **Backfill routes by rule kind, like the live path.** A row rule's backfill scans and chunks; a **group**
  rule's backfill re-drives the dirty-mark over the window and lets the ordinary readiness sweep evaluate what
  it marks; a **Class-1** rule's backfill is the materialize loop bounded by the window.
- **`run_history` records the window** (`window_from`, `window_to`), so an operator inspecting a run can see
  what it covers rather than inferring it from when it was started.
- **An inverted or empty window is rejected at the API**, with the stable `ErrorView` contract, rather than
  being passed to ADAS to reject.
- **This is how an expression-version bump is re-applied to history**: bump the evaluator version in ADAS,
  then backfill the affected range — named explicitly, because it is the motivating workflow and nothing else
  in the runner performs it.

## Capabilities

### New Capabilities

<!-- none: extends backfill to a window and to the two lanes it did not cover -->

### Modified Capabilities

- `backfill`: "Full-replay backfill on an independent cursor" becomes window-capable and lane-aware — the run
  drives whichever read its rule's kind requires; "Backfill runs are pausable, resumable and cancellable"
  gains the window's validation semantics.
- `incremental-scan`: "Draining incremental scan" — the scan request may carry a version window, which bounds
  the keyset rather than replacing the cursor.
- `class1-enrichment`: "Draining materialize tick" — a backfill drives the same loop with a bounded window,
  which is also how a re-derivation is applied to history.
- `service-runtime`: "PostgreSQL runtime-state schema" — `run_history` gains the window columns.

## Impact

- **New code**: `web/dto/backfill/BackfillRequest` (the optional window), `backfill/BackfillWindow`.
- **Modified code**: `adas/dto/ScanRequest` (+`from`/`to`), `adas/AdasScanClient` (a windowed overload),
  `backfill/BackfillExecutor` (three-lane routing + window pass-through), `backfill/BackfillService`
  (accept and persist a window), `backfill/BackfillPageCommitter` (group marks as well as chunks),
  `web/controller/BackfillController`, `web/handler/DefaultExceptionHandler` (an inverted window → 400),
  `persistence/RunHistoryEntity`, `web/dto/backfill/BackfillRunDto` + its mapper.
- **Migration**: `V6__run_history_window.sql` — two nullable timestamp columns. Additive; an existing run
  reads as unbounded, which is what it was.
- **ADAS contract**: none. `from`/`to` are already shipped on both endpoints with the semantics this relies on.
- **Configuration**: none. A window is per-run, supplied by the operator who knows the range.
- **Not here**: scheduling a backfill for later; parallel backfills for one rule (the existing single-running
  conflict stands); and any change to how a backfill is prioritised, which already works.
