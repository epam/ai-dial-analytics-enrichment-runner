# service-runtime Specification

## Purpose
The service's own foundations: how it boots, the PostgreSQL runtime-state schema it owns and migrates, the
stable error contract every failure returns, environment-driven configuration, and the sensitive-data identity
posture and body-handling constraints it operates under.

## Requirements

### Requirement: Bootable service context

The service SHALL start as a Spring Boot application with base package
`com.epam.aidial.enrichment` and expose HTTP functional endpoints under `/v1` and operational
endpoints under `/actuator/*`. The build SHALL compile on a Java 25 toolchain with `-Werror` and
SHALL fail on any Google-style Checkstyle violation (180-char lines).

#### Scenario: Application context starts

- **WHEN** the application is launched with a reachable PostgreSQL datasource
- **THEN** the Spring context initializes without error and the HTTP server listens on the
  configured port (default `5000`)

#### Scenario: Style and compilation are enforced

- **WHEN** `./gradlew clean build` runs
- **THEN** compilation uses `-Werror` and Checkstyle runs over main and test sources, and the build
  fails if either reports a violation

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

### Requirement: Stable error contract

Every endpoint SHALL return failures as the stable `ErrorView` JSON shape
`{status, error, message, path, method}`, where `error` is a stable machine-readable code and
`message` is human-readable with stack traces stripped and length-capped. New failure modes SHALL be
mapped by adding an `@ExceptionHandler` to `DefaultExceptionHandler`.

#### Scenario: Unknown route returns the error shape

- **WHEN** a request targets a route that does not exist
- **THEN** the response is `404` with body `{status:404, error:"not_found", message, path, method}`

#### Scenario: Runtime-state database unavailable

- **WHEN** a data-access error occurs because PostgreSQL is unreachable
- **THEN** the response is `503` with `error:"postgres_unavailable"` and no stack trace in `message`

### Requirement: Environment-driven configuration

Configuration SHALL be environment-driven via `application.yml` using `${ENV_VAR:default}` bindings,
with authoritative property descriptions kept only in `docs/configuration.md`. No secrets SHALL be
committed to source or config.

#### Scenario: Defaults allow local boot

- **WHEN** the application starts with no environment variables set beyond a reachable database
- **THEN** it binds documented defaults (e.g. `HTTP_PORT=5000`) and starts successfully

### Requirement: Sensitive-data identity posture

The runner SHALL operate against ADAS with an identity entitled to read `sensitive` columns —
`FULL_ADMIN` — for any pipeline that references one. This **supersedes** the phase-1 least-privilege stance
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

The pinned set SHALL be `org.springframework.web.client` and `org.springframework.http.converter` (bodies) and
`org.hibernate.SQL`, `org.hibernate.orm.jdbc.bind` and `org.springframework.jdbc.core` (statements and bound
values), each declared in `log4j2-spring.xml` with its own logger entry at INFO. Runtime level changes (see the
`observability` capability) SHALL refuse any level below INFO for a pinned logger or any of its descendants.
Because each pin is its own logger entry, a level applied to an **ancestor** leaves it in place.

A response body SHALL NOT reach the log through an exception either. Spring embeds the full response body in a
`RestClientResponseException`'s message, so its `toString()` and the first line of its stack trace carry the
body. A failure path SHALL log such an exception by its classified form only, and SHALL NOT pass it — or any
throwable whose cause chain contains one — as a log call's throwable argument.

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

#### Scenario: An error response body stays out of failure lines

- **GIVEN** DIAL or ADAS answering an error status whose body carries a marker string
- **WHEN** the chunk, group, upsert, backfill or Class-1 failure path logs the failure
- **THEN** no log record — message or stack trace — contains the marker, and the record names the classified
  cause

#### Scenario: An ancestor level change keeps the pins

- **GIVEN** the runtime level file sets `org.springframework` to DEBUG
- **WHEN** the level is applied
- **THEN** `org.springframework.web.client` and `org.springframework.http.converter` stay at INFO
