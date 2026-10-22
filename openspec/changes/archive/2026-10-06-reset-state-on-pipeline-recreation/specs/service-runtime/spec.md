## MODIFIED Requirements

### Requirement: PostgreSQL runtime-state schema

The service SHALL own a PostgreSQL schema for its runtime state and SHALL provision it via Flyway
migrations under `classpath:db/migration/postgres` on startup (togglable by
`POSTGRES_MIGRATIONS_ENABLED`). The schema SHALL include the db-scheduler `scheduled_tasks` table and
the runner-state tables `pipeline_cursor`, `run_history`, `dlq_item`, `spend_counter`, and `group_state`.
`dlq_item.stage` SHALL remain constrained to the set of stages the runner actually produces — the row
stages `input_map`, `dial_call`, `validate`, `output_map`, `upsert` plus the group stages `group_fetch` and
`group_map` — so an unrecognised stage is rejected by the database rather than stored. `group_state` SHALL
carry a per-group daily evaluation count alongside the day it belongs to, so the cost ceiling is enforced by
the same query that selects dirty and ready groups and is evicted with the group it counts. `group_state`
SHALL also carry whether the group has been signalled complete, so the readiness sweep evaluates that
condition in the same query — the flag records ADAS's verdict and is never derived here. `run_history`
SHALL carry the version window a run covers, so a past run is self-describing rather than needing the request
that started it. `run_history` SHALL also carry **why a run was paused and when**, in a `CHECK`-constrained
reason column and a timestamp, so the condition survives the log line that announced it and survives a
restart — which the in-memory pipeline-pause registry does not. The reason column SHALL stay
`CHECK`-constrained for the reason `dlq_item.stage` is: an unrecognised value is then a database error rather
than a stored typo; and a second constraint SHALL require the reason and its timestamp to be present
together or not at all. That a reason appears only on a `paused` run is enforced by the runner's transitions
in this release; the constraint that makes it a database rule SHALL follow once no deployable image predates
it, because an older image's resume and cancel cannot clear a column they do not map. `run_history` SHALL also
carry the reason its **last batch failed** and when, so a run that has stopped making progress is
distinguishable from a slow one without reading a log — the backfill batch retries indefinitely by design,
and nothing else on the run changes while it does. `run_history` SHALL carry the **pipeline generation** a
run is draining under, so a declaration edited mid-run is detected rather than silently adopted. The
database SHALL admit at most one `running` run per pipeline, through a partial unique index, so the start
conflict holds under concurrent requests rather than only under a check-then-insert.
`run_history` SHALL carry the rows a run has **processed** and SHALL NOT read or write a total: the column
that once promised one was never written, is no longer mapped, and is dropped once no deployable image maps
it; no total is obtainable — ADAS exposes no cardinality, the cursor is
opaque, the window's upper bound moves, and a source count would not be comparable with the processed count.
`dlq_item` SHALL carry the **backfill run** that produced an item, nullable, with null meaning the live path;
it SHALL be indexed only where present, since the live majority must not pay write throughput for a query
only the run-scoped views issue, and it SHALL carry **no foreign key** to `run_history`, whose retention is
independent of the dead-letter sweep's and which has been truncated outright before — a deleted run must not
cascade into, or block the deletion of, the record of what failed.
The service SHALL hold no ClickHouse connection.

Every runner-state table SHALL key its rows on the ADAS pipeline **name**, held in a column named
`pipeline_name` (and, on `pipeline_cursor`, as the table's primary key). The name is the pipeline's identity
in the ADAS registry and in the runner's work-item ids alike, so the runner stores no second identifier for
the same thing.

A name can outlive the pipeline it names, though, so `pipeline_cursor` SHALL also record, as the nullable
`pipeline_created_at`, the ADAS `created_at` of the pipeline the name meant when the runner last saw it. It is not a
second key: nothing is keyed on it, and it is read only to detect a pipeline recreated under the same name (see the
`pipeline-sync` capability). The sync SHALL own it; a cursor commit SHALL only stamp it on a row the commit inserts, and SHALL NOT overwrite it.

#### Scenario: Migrations provision the schema on boot

- **WHEN** the application starts against an empty database with migrations enabled
- **THEN** Flyway applies `V1__db_scheduler.sql`, `V2__runner_state.sql`, `V3__group_state.sql`,
  `V4__group_dlq_stages.sql`, `V5__group_cost_ceiling.sql`, `V6__run_history_window.sql`,
  `V7__group_signal.sql`, `V8__rekey_and_discard_runner_state.sql`,
  `V9__rekey_state_on_pipeline_generation.sql`, `V10__backfill_run_operability.sql`,
  `V11__dlq_listing_keyset.sql`, `V12__group_listing_keyset.sql` and `V13__pipeline_cursor_created_at.sql`, and the tables
  `scheduled_tasks`, `pipeline_cursor`, `run_history`, `dlq_item`, `spend_counter` and `group_state` exist,
  each of the last five keyed on `pipeline_name`

#### Scenario: Migrations can be disabled for out-of-band provisioning

- **WHEN** `POSTGRES_MIGRATIONS_ENABLED` is `false`
- **THEN** the application starts without running Flyway migrations

#### Scenario: An unrecognised dead-letter stage is rejected

- **WHEN** a write attempts to store a `dlq_item` whose stage is outside the permitted set
- **THEN** the database rejects it

#### Scenario: An existing group needs no backfill for the counter

- **GIVEN** a database whose `group_state` rows predate the evaluation-count columns
- **WHEN** the migration is applied
- **THEN** those groups are treated as having no evaluations recorded for the current day, and are counted
  from their next evaluation onwards

#### Scenario: An existing group needs no backfill for the signal

- **GIVEN** a database whose `group_state` rows predate the signal column
- **WHEN** the migration is applied
- **THEN** those groups read as un-signalled, which for a pipeline declaring no `ready_when.signal` is their only
  possible state, and a signalling member marks them from the next scan onwards

#### Scenario: An existing run reads as unbounded

- **GIVEN** a database whose `run_history` rows predate the window columns
- **WHEN** the migration is applied
- **THEN** those runs report no window, which is what they were

#### Scenario: An existing paused run reads as having no recorded reason

- **GIVEN** a database whose `run_history` rows predate the pause-reason columns, including a run that is
  currently paused
- **WHEN** the migration is applied
- **THEN** those runs report no pause reason and no pause instant, which is the truth about them — no reason
  is invented for a pause the runner did not record

#### Scenario: An unrecognised pause reason is rejected

- **WHEN** a write attempts to store a `run_history` pause reason outside the permitted set
- **THEN** the database rejects it

#### Scenario: A pause reason without its instant is rejected

- **WHEN** a write attempts to store a `run_history` pause reason without a pause instant, or the reverse
- **THEN** the database rejects it

#### Scenario: A second running run for a pipeline is rejected by the database

- **GIVEN** a pipeline with a `running` run
- **WHEN** a write attempts to set a second run for the same pipeline `running`
- **THEN** the database rejects it

#### Scenario: An existing run carries no generation

- **GIVEN** a database whose `run_history` rows predate the generation column
- **WHEN** the migration is applied
- **THEN** those runs record no generation, and a still-running one adopts the pipeline's current generation
  on its next batch rather than pausing

#### Scenario: An existing run reads as having failed no batch

- **GIVEN** a database whose `run_history` rows predate the last-error columns
- **WHEN** the migration is applied
- **THEN** those runs report no failure reason and no failure instant, which is either true of them or
  unknowable, and nothing is invented

#### Scenario: An existing dead letter reads as the live path's

- **GIVEN** a database whose `dlq_item` rows predate the run column
- **WHEN** the migration is applied
- **THEN** those items report no run, which means the live path — what every one of them was
