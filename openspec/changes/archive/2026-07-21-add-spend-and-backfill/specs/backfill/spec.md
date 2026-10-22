## ADDED Requirements

### Requirement: Full-replay backfill on an independent cursor

The runner SHALL run a backfill that re-drives the rule-scoped scan from the source start (cursor
omitted), tracking its own cursor and progress in `run_history` independent of the live
`rule_cursor`. A backfill SHALL drain the history batch by batch, advancing its own cursor and
row count each batch, and SHALL mark the run done when no more pages remain.

#### Scenario: A backfill drains history and completes

- **GIVEN** a started backfill for a rule and a single available scan page (no more pages)
- **WHEN** the backfill batch runs
- **THEN** the page's chunks are enqueued, the run's cursor and row count advance, and the run is
  marked done

### Requirement: Backfill runs in the backfill priority lane

The runner SHALL enqueue backfill chunks at a lower priority than live chunks so a backfill never
starves live scans. Backfill chunk work items SHALL use instance ids namespaced by the run id so they
never collide with live chunks scanned at the same cursor.

#### Scenario: Backfill chunks carry backfill priority

- **WHEN** a backfill batch enqueues chunk work items
- **THEN** those work items are scheduled at the backfill priority (below live)

### Requirement: Backfill runs are pausable, resumable and cancellable

The runner SHALL let an operator start, inspect, pause, resume, and cancel a backfill run. A run that
is paused or cancelled SHALL stop draining; a paused run SHALL resume from its committed cursor when
resumed. Inspecting an unknown run id SHALL return `404 not_found`.

#### Scenario: A paused run does not drain

- **GIVEN** a started backfill that is then paused
- **WHEN** its batch task runs
- **THEN** nothing is scanned or enqueued and the run stays paused

#### Scenario: Unknown run id is not found

- **WHEN** an operator requests a backfill run id that does not exist
- **THEN** the response is `404 not_found`
