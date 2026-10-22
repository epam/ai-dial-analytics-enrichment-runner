## MODIFIED Requirements

### Requirement: Full-replay backfill on an independent cursor

The runner SHALL run a backfill that re-drives a pipeline's history on its own cursor and progress in
`run_history`, independent of the live `pipeline_cursor`. A backfill MAY be bounded to a `[from, to]` version
window, which SHALL be passed to ADAS on every request and SHALL bound the keyset rather than replace the
cursor — so a bounded run still advances, resumes and stalls exactly as an unbounded one does. An unbounded
backfill SHALL behave as before, replaying from the source start.

The backfill SHALL drive whichever read its pipeline's kind requires, matching the live path's routing:
- a **row-grain Class-2** pipeline's backfill scans pages and enqueues chunks;
- a **group** pipeline's backfill re-drives the dirty-mark over the window and stops there — the ordinary
  readiness sweep decides when a marked group is evaluated, so the pipeline's `idle`, `max_staleness` and
  `cost_ceiling` still apply and backfill SHALL NOT enqueue group work items itself;
- a **Class-1** pipeline's backfill drives the materialize loop bounded by the window, advancing the run cursor
  only on a successful response.

A backfill SHALL drain batch by batch, advancing its cursor and row count each batch, and SHALL mark the run
done when no more pages remain. It SHALL honour the same enqueue guards as the live path: it SHALL record page
dead-letter rates to the DLQ circuit breaker and SHALL pause the run when its pipeline is paused (by an operator or
the breaker) or over its daily spend budget; a page that reports `has_more` but does not advance the cursor
SHALL pause the run for investigation rather than looping, and SHALL leave the run's stored cursor as it was
rather than clearing it — a cleared cursor would restart the run from the beginning of its window on resume.

For a group pipeline, completing the run SHALL mean the window has been **marked**, not that its groups have been
evaluated — the evaluation follows on the sweep's schedule and under its gates.

#### Scenario: A backfill drains history and completes

- **GIVEN** a started backfill for a pipeline and a single available scan page (no more pages)
- **WHEN** the backfill batch runs
- **THEN** the page's chunks are enqueued, the run's cursor and row count advance, and the run is
  marked done

#### Scenario: A backfill pauses when its pipeline is paused

- **GIVEN** a running backfill whose pipeline becomes paused (e.g. the DLQ circuit breaker trips)
- **WHEN** the next batch runs
- **THEN** the run is paused and no further pages are drained

#### Scenario: A bounded backfill touches only its window

- **GIVEN** a backfill started with a `[from, to]` window
- **WHEN** its batches run
- **THEN** every request to ADAS carries that window

#### Scenario: A group pipeline's backfill marks rather than enqueues

- **GIVEN** a started backfill for a group pipeline
- **WHEN** its batch runs
- **THEN** the window's groups are marked dirty and no group work item is enqueued by the backfill itself

#### Scenario: A Class-1 pipeline's backfill materializes its window

- **GIVEN** a started backfill for a Class-1 pipeline
- **WHEN** its batch runs
- **THEN** it drives the materialize loop with the window, enqueues nothing, and advances the run cursor only
  on a successful response

### Requirement: Backfill runs in the backfill priority lane

The runner SHALL enqueue backfill chunks at a lower priority than live chunks so a backfill never
starves live scans. Backfill chunk work items SHALL use instance ids namespaced by the run id so they
never collide with live chunks scanned at the same cursor.

That namespace SHALL be one no pipeline name can produce, so a backfill chunk is unreachable from any
pipeline's instance-id prefix. A backfill chunk rides the same `enrich-chunk` task as a live one but is
deliberately outside every pipeline's in-flight count, and under the previous identity a UUID prefix could
never have collided with it. A pipeline name can: a bare word namespace would be the whole prefix scan of a
pipeline named that word, which would count every other pipeline's backfill chunks as its own depth and
backpressure it into silence.

#### Scenario: Backfill chunks carry backfill priority

- **WHEN** a backfill batch enqueues chunk work items
- **THEN** those work items are scheduled at the backfill priority (below live)

#### Scenario: A backfill chunk is outside every pipeline's in-flight prefix

- **GIVEN** a pipeline whose name is the backfill namespace's own word
- **WHEN** its in-flight work depth is counted by instance-id prefix
- **THEN** no backfill chunk of any pipeline is counted

### Requirement: Backfill runs are pausable, resumable and cancellable

The runner SHALL let an operator start, inspect, pause, resume, and cancel a backfill run, optionally bounding
a run to a version window at start. A run that is paused or cancelled SHALL stop draining; a paused run SHALL
resume from its committed cursor when resumed, retaining its window. Only a PAUSED run SHALL be resumable —
resuming a done, cancelled, or already-running run SHALL return `409`. Starting a backfill for a pipeline absent
from the cache SHALL return `404 not_found`, and starting a second backfill for a pipeline that already has a
running one SHALL return `409`. A window whose `from` is not strictly before its `to` SHALL be rejected with
`400` at the runner's own API, rather than creating a run that could only fail on its first batch. Inspecting
an unknown run id SHALL return `404 not_found`.

A run's window SHALL be readable from the run itself, so an operator can tell what a past run covered without
reconstructing the request that started it.

A backfill SHALL be started at `POST /v1/pipelines/{name}/backfill`, and the run it creates SHALL report the
pipeline it belongs to under the JSON property `pipeline_name`. The run-scoped paths (`/v1/backfills/{runId}`
and its pause, resume and cancel verbs) are addressed by run id and are unaffected, which is precisely why the
property on the run body is the one place the identity surfaces there.

#### Scenario: A paused run does not drain

- **GIVEN** a started backfill that is then paused
- **WHEN** its batch task runs
- **THEN** nothing is scanned or enqueued and the run stays paused

#### Scenario: Resuming a non-paused run is rejected

- **GIVEN** a backfill run that is done, cancelled, or running
- **WHEN** an operator resumes it
- **THEN** the response is `409` and the run's state is unchanged

#### Scenario: A second concurrent backfill for a pipeline is rejected

- **GIVEN** a pipeline that already has a running backfill
- **WHEN** an operator starts another backfill for the same pipeline
- **THEN** the response is `409` and no second run is created

#### Scenario: A run reports the pipeline it belongs to

- **GIVEN** a backfill started for a pipeline
- **WHEN** the run is inspected at `GET /v1/backfills/{runId}`
- **THEN** the response carries that pipeline's name under `pipeline_name`

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
