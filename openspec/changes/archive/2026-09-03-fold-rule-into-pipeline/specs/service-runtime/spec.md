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
that started it. The service SHALL hold no ClickHouse connection.

Every runner-state table SHALL key its rows on the ADAS pipeline **name**, held in a column named
`pipeline_name` (and, on `pipeline_cursor`, as the table's primary key). The name is the pipeline's identity
in the ADAS registry and in the runner's work-item ids alike, so the runner stores no second identifier for
the same thing.

#### Scenario: Migrations provision the schema on boot

- **WHEN** the application starts against an empty database with migrations enabled
- **THEN** Flyway applies `V1__db_scheduler.sql`, `V2__runner_state.sql`, `V3__group_state.sql`,
  `V4__group_dlq_stages.sql`, `V5__group_cost_ceiling.sql`, `V6__run_history_window.sql` and
  `V7__group_signal.sql`, `V8__rekey_and_discard_runner_state.sql`, and the tables `scheduled_tasks`,
  `pipeline_cursor`, `run_history`, `dlq_item`, `spend_counter` and `group_state` exist, each of the last
  five keyed on `pipeline_name`

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

### Requirement: Sensitive-data identity posture

The runner SHALL operate against ADAS with an identity entitled to read `sensitive` columns —
`FULL_ADMIN` — for any pipeline that references one. This **supersedes** the original least-privilege stance
(per-target grants only, no global admin role) and is recorded as a deliberate deviation rather than an
oversight. It is forced, not chosen: ADAS re-checks sensitive entitlement at scan, group-assembly and
materialize egress against the calling identity and **rejects rather than strips**, so a least-privilege
runner receives an error rather than a redacted row and no degraded mode exists.

What the entitlement widens SHALL be stated as precisely as the entitlement itself: it widens *which columns
a pipeline may reference*, not *which pipelines the runner may operate*. The surface stays pipeline-scoped in both
directions — the scan projects only pipeline-referenced columns, group assembly returns only the pipeline's
membership, and write-back rejects any column outside the pipeline's declared outputs plus permitted provenance.

The runner SHALL state the entitlement it expects, and that ADAS's sensitive check fails **open** when ADAS
runs with security disabled, in a single factual startup log line — enough for an operator to recognise what a
`403` on a body-reading pipeline points at, and that a clean local run proves nothing about production. That line
SHALL carry no rationale, deviation history, or follow-up roadmap: those belong to this spec and
`docs/configuration.md`, which are where a reader looks for *why*, and cannot be acted on from a log.

A narrower grant — per-pipeline or per-service-identity — that would let the runner return to per-target access
SHALL be recorded as the follow-up rather than implied to exist.

#### Scenario: The identity expectation is stated at startup

- **WHEN** the application starts
- **THEN** it logs that it expects an identity entitled to read sensitive columns, and that ADAS's check
  fails open when ADAS security is disabled, without restating the posture's rationale or deferred follow-up

#### Scenario: The entitlement does not widen which pipelines the runner may operate

- **WHEN** the runner holds a sensitive-entitled identity
- **THEN** it still reads only columns its pipelines reference and writes only columns its pipelines declare

### Requirement: Body-handling constraints

The runner SHALL NOT log assembled group input, member rows, or rendered prompts at **any** level. This
covers the framework as well as the runner's own code: Spring's HTTP client logs request and response bodies
at DEBUG/TRACE, and for this service those bodies *are* the enrichment payloads — a rendered prompt carries the
source content a pipeline reads. Those loggers SHALL therefore be pinned independently of the application log
level, so raising verbosity for diagnosis cannot silently start writing source content to the log pipeline.
Supplying a logging configuration that overrides the pin re-enables body logging, which a deployment handling
sensitive data SHALL NOT do.

The runner SHALL NOT place member rows in a work-item payload: a group work item carries identifying keys only, so member
bodies never reach `scheduled_tasks`. Model output SHALL persist in exactly one place — a dead-letter item's
raw response, bounded by the dead-letter retention window — so an audit of where derived content lands has a
single answer.

These constraints SHALL be verified rather than trusted, because each is a property of every present and
future line in the enrichment path and the cost of breaking one is source content in a log aggregator.

#### Scenario: Member content never reaches the logs

- **GIVEN** a group whose members carry identifiable content, and logging raised to its most verbose level
- **WHEN** the group is evaluated, including its failure paths
- **THEN** no log record contains that content, from the runner's code or from the HTTP client

#### Scenario: A group work item carries no member rows

- **WHEN** a group work item is enqueued and serialized
- **THEN** its payload carries only identifying keys, not the group's members
