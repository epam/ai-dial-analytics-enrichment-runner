## Context

The runner is a greenfield repo that must mirror the ADAS stack (Java 25 / Spring Boot 4.0.6 /
Jackson 3 / Log4j2 / Flyway-Postgres / Actuator+OTel) so the two services share conventions,
error contract, and operational surface. Unlike ADAS, the runner has **no ClickHouse connection**
and no blob/Calcite/Arrow-Flight surface: it owns only a PostgreSQL runtime-state schema and (in
later milestones) talks to ADAS and DIAL Core over HTTP. This change delivers the first step — a
bootable skeleton — without any enrichment logic.

## Goals / Non-Goals

**Goals:**
- A `./gradlew clean build`-green skeleton that boots, migrates its PostgreSQL schema, and serves
  `/actuator/health` = UP.
- Establish the stable error contract, env-driven config convention, and OpenSpec workflow so later
  milestones drop in cleanly.
- Provision the full runtime-state schema (db-scheduler + runner state) up front.

**Non-Goals:**
- Any ADAS/DIAL client, rule sync, scan, worker, DLQ, spend, or backfill logic (later changes, starting with `add-rule-sync`).
- Wiring the db-scheduler `Scheduler` bean (deferred to `add-rule-sync` with JacksonSerializer +
  `TransactionAwareDataSourceProxy`); this change ships only the schema.
- Inbound authentication on the runner's own endpoints (internal single-replica service).

## Decisions

- **Trim the dependency set vs ADAS.** Drop ClickHouse client/jdbc, jclouds/blob, Calcite,
  Arrow/Flight, and Spring Security. Rationale: the runner has no ClickHouse connection and no
  SQL-translation or Flight surface; carrying those deps adds CVE surface, build weight, and the
  Arrow JVM `--add-opens`/`--enable-native-access` flags for no benefit. Alternative (copy ADAS
  wholesale) was rejected.
- **Defer feature libraries to their milestones.** `db-scheduler`, `resilience4j`, `jsonata`, and
  `json-schema-validator` are added when first used by a later change, keeping this change's dependency graph minimal
  and avoiding premature Spring Boot 4 autoconfiguration compatibility work. The `V1` db-scheduler
  DDL still ships now (plain SQL, library-version-independent for the stable `scheduled_tasks`
  shape). Alternative (declare all deps up front) risks an unused-but-incompatible starter breaking
  this change.
- **Ship the priority-enabled `scheduled_tasks` variant.** `add-incremental-scan` and `add-spend-and-backfill` use db-scheduler's priority feature
  to keep backfill from starving live scans, so the table carries a `priority` column + index from
  the start rather than requiring a later ALTER.
- **Mirror ADAS's `JsonMapper` config.** Case-insensitive enum reads + lowercase enum writes match
  ADAS's wire format, so the runner's DTOs bind ADAS enum values (`on_ingest`, `schedule`, `live`,
  `chat_completion`) regardless of casing.
- **Adopt OpenSpec (CLI 1.4.1).** Project context lives in `openspec/config.yaml`; one change per
  milestone. `.claude/opsx` commands + skills are copied from ADAS (they are generic).

## Risks / Trade-offs

- **db-scheduler schema drift** → The canonical `scheduled_tasks` shape has been stable across
  db-scheduler majors; when the library is pinned in `add-rule-sync`, verify against its bundled DDL and add a
  corrective migration if needed.
- **Testcontainers requires Docker** → CI runs Docker-in-Docker; contributors need a local Docker
  daemon. Documented in the README/build commands.
- **Deferring deps means later build churn** → Accepted; each milestone owns its dependency additions
  and its own `clean build`-green gate, which is the point of milestone-by-milestone delivery.

## Migration Plan

Greenfield — no data migration. On first deploy, Flyway applies `V1`/`V2` against an empty database.
Rollback is dropping the schema (no production data exists at this stage).
