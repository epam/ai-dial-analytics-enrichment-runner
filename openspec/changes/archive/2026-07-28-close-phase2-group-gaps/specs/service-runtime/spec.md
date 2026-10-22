## MODIFIED Requirements

### Requirement: PostgreSQL runtime-state schema

The service SHALL own a PostgreSQL schema for its runtime state and SHALL provision it via Flyway
migrations under `classpath:db/migration/postgres` on startup (togglable by
`POSTGRES_MIGRATIONS_ENABLED`). The schema SHALL include the db-scheduler `scheduled_tasks` table and
the runner-state tables `rule_cursor`, `run_history`, `dlq_item`, `spend_counter`, and `group_state`.
`dlq_item.stage` SHALL remain constrained to the set of stages the runner actually produces — the row
stages `input_map`, `dial_call`, `validate`, `output_map`, `upsert` plus the group stages `group_fetch` and
`group_map` — so an unrecognised stage is rejected by the database rather than stored. `group_state` SHALL
carry a per-group daily evaluation count alongside the day it belongs to, so the cost ceiling is enforced by
the same query that selects dirty and ready groups and is evicted with the group it counts. `group_state`
SHALL also carry whether the group has been signalled complete, so the readiness sweep evaluates that
condition in the same query — the flag records ADAS's verdict and is never derived here. `run_history`
SHALL carry the version window a run covers, so a past run is self-describing rather than needing the request
that started it. The service SHALL hold no ClickHouse connection.

#### Scenario: Migrations provision the schema on boot

- **WHEN** the application starts against an empty database with migrations enabled
- **THEN** Flyway applies `V1__db_scheduler.sql`, `V2__runner_state.sql`, `V3__group_state.sql`,
  `V4__group_dlq_stages.sql`, `V5__group_cost_ceiling.sql`, `V6__run_history_window.sql` and
  `V7__group_signal.sql`, and the tables `scheduled_tasks`, `rule_cursor`, `run_history`, `dlq_item`,
  `spend_counter` and `group_state` exist

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
- **THEN** those groups read as un-signalled, which for a rule declaring no `ready_when.signal` is their only
  possible state, and a signalling member marks them from the next scan onwards

#### Scenario: An existing run reads as unbounded

- **GIVEN** a database whose `run_history` rows predate the window columns
- **WHEN** the migration is applied
- **THEN** those runs report no window, which is what they were
