## MODIFIED Requirements

### Requirement: PostgreSQL runtime-state schema

The service SHALL own a PostgreSQL schema for its runtime state and SHALL provision it via Flyway
migrations under `classpath:db/migration/postgres` on startup (togglable by
`POSTGRES_MIGRATIONS_ENABLED`). The schema SHALL include the db-scheduler `scheduled_tasks` table and
the runner-state tables `rule_cursor`, `run_history`, `dlq_item`, `spend_counter`, and `group_state`.
The service SHALL hold no ClickHouse connection.

#### Scenario: Migrations provision the schema on boot

- **WHEN** the application starts against an empty database with migrations enabled
- **THEN** Flyway applies `V1__db_scheduler.sql`, `V2__runner_state.sql` and `V3__group_state.sql`, and
  the tables `scheduled_tasks`, `rule_cursor`, `run_history`, `dlq_item`, `spend_counter` and
  `group_state` exist

#### Scenario: Migrations can be disabled for out-of-band provisioning

- **WHEN** `POSTGRES_MIGRATIONS_ENABLED` is `false`
- **THEN** the application starts without running Flyway migrations
