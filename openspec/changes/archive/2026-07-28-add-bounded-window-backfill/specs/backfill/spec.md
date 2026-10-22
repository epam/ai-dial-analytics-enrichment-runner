## MODIFIED Requirements

### Requirement: Full-replay backfill on an independent cursor

The runner SHALL run a backfill that re-drives a rule's history on its own cursor and progress in
`run_history`, independent of the live `rule_cursor`. A backfill MAY be bounded to a `[from, to]` version
window, which SHALL be passed to ADAS on every request and SHALL bound the keyset rather than replace the
cursor — so a bounded run still advances, resumes and stalls exactly as an unbounded one does. An unbounded
backfill SHALL behave as before, replaying from the source start.

The backfill SHALL drive whichever read its rule's kind requires, matching the live path's routing:
- a **row-grain Class-2** rule's backfill scans pages and enqueues chunks;
- a **group** rule's backfill re-drives the dirty-mark over the window and stops there — the ordinary
  readiness sweep decides when a marked group is evaluated, so the rule's `idle`, `max_staleness` and
  `cost_ceiling` still apply and backfill SHALL NOT enqueue group work items itself;
- a **Class-1** rule's backfill drives the materialize loop bounded by the window, advancing the run cursor
  only on a successful response.

A backfill SHALL drain batch by batch, advancing its cursor and row count each batch, and SHALL mark the run
done when no more pages remain. It SHALL honour the same enqueue guards as the live path: it SHALL record page
dead-letter rates to the DLQ circuit breaker and SHALL pause the run when its rule is paused (by an operator or
the breaker) or over its daily spend budget; a page that reports `has_more` but does not advance the cursor
SHALL pause the run for investigation rather than looping.

For a group rule, completing the run SHALL mean the window has been **marked**, not that its groups have been
evaluated — the evaluation follows on the sweep's schedule and under its gates.

#### Scenario: A backfill drains history and completes

- **GIVEN** a started backfill for a rule and a single available scan page (no more pages)
- **WHEN** the backfill batch runs
- **THEN** the page's chunks are enqueued, the run's cursor and row count advance, and the run is
  marked done

#### Scenario: A backfill pauses when its rule is paused

- **GIVEN** a running backfill whose rule becomes paused (e.g. the DLQ circuit breaker trips)
- **WHEN** the next batch runs
- **THEN** the run is paused and no further pages are drained

#### Scenario: A bounded backfill touches only its window

- **GIVEN** a backfill started with a `[from, to]` window
- **WHEN** its batches run
- **THEN** every request to ADAS carries that window

#### Scenario: A group rule's backfill marks rather than enqueues

- **GIVEN** a started backfill for a group rule
- **WHEN** its batch runs
- **THEN** the window's groups are marked dirty and no group work item is enqueued by the backfill itself

#### Scenario: A Class-1 rule's backfill materializes its window

- **GIVEN** a started backfill for a Class-1 rule
- **WHEN** its batch runs
- **THEN** it drives the materialize loop with the window, enqueues nothing, and advances the run cursor only
  on a successful response

### Requirement: Backfill runs are pausable, resumable and cancellable

The runner SHALL let an operator start, inspect, pause, resume, and cancel a backfill run, optionally bounding
a run to a version window at start. A run that is paused or cancelled SHALL stop draining; a paused run SHALL
resume from its committed cursor when resumed, retaining its window. Only a PAUSED run SHALL be resumable —
resuming a done, cancelled, or already-running run SHALL return `409`. Starting a backfill for a rule absent
from the cache SHALL return `404 not_found`, and starting a second backfill for a rule that already has a
running one SHALL return `409`. A window whose `from` is not strictly before its `to` SHALL be rejected with
`400` at the runner's own API, rather than creating a run that could only fail on its first batch. Inspecting
an unknown run id SHALL return `404 not_found`.

A run's window SHALL be readable from the run itself, so an operator can tell what a past run covered without
reconstructing the request that started it.

#### Scenario: A paused run does not drain

- **GIVEN** a started backfill that is then paused
- **WHEN** its batch task runs
- **THEN** nothing is scanned or enqueued and the run stays paused

#### Scenario: Resuming a non-paused run is rejected

- **GIVEN** a backfill run that is done, cancelled, or running
- **WHEN** an operator resumes it
- **THEN** the response is `409` and the run's state is unchanged

#### Scenario: A second concurrent backfill for a rule is rejected

- **GIVEN** a rule that already has a running backfill
- **WHEN** an operator starts another backfill for the same rule
- **THEN** the response is `409` and no second run is created

#### Scenario: Unknown run id is not found

- **WHEN** an operator requests a backfill run id that does not exist
- **THEN** the response is `404 not_found`

#### Scenario: An inverted window is rejected

- **WHEN** an operator starts a backfill whose `from` is not strictly before its `to`
- **THEN** the response is `400` in the standard error shape and no run is created

#### Scenario: A run reports the window it covers

- **GIVEN** a backfill started with a window
- **WHEN** an operator inspects the run
- **THEN** the run reports that window
