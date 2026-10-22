## ADDED Requirements

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
the runner-state tables `rule_cursor`, `run_history`, `dlq_item`, and `spend_counter`. The service
SHALL hold no ClickHouse connection.

#### Scenario: Migrations provision the schema on boot

- **WHEN** the application starts against an empty database with migrations enabled
- **THEN** Flyway applies `V1__db_scheduler.sql` and `V2__runner_state.sql`, and the tables
  `scheduled_tasks`, `rule_cursor`, `run_history`, `dlq_item`, and `spend_counter` exist

#### Scenario: Migrations can be disabled for out-of-band provisioning

- **WHEN** `POSTGRES_MIGRATIONS_ENABLED` is `false`
- **THEN** the application starts without running Flyway migrations

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
