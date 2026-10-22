## Why

The Analytics Enrichment Runner is a brand-new, empty repository. Before any enrichment feature
(rule sync, incremental scan, worker, DLQ) can be built, the service needs a runnable Spring Boot
skeleton mirroring the ADAS stack: a build that enforces the house style, a PostgreSQL runtime-state
schema provisioned by Flyway, the stable error contract, and operational health/metrics endpoints.
This change establishes that foundation.

## What Changes

- **Build system**: Gradle (wrapper 9.6.0) with Spring Boot 4.0.6, Java 25 toolchain, `-Werror`,
  Google-style Checkstyle (180-char), Lombok, MapStruct — trimmed of ADAS's ClickHouse / blob /
  Calcite / Arrow-Flight dependencies (the runner has no ClickHouse connection).
- **Application skeleton**: base package `com.epam.aidial.enrichment`, the Boot entry point, and the
  primary Jackson 3 `JsonMapper` (case-insensitive enum reads, matching ADAS's wire format).
- **Runtime-state schema**: Flyway migrations under `db/migration/postgres` — `V1__db_scheduler.sql`
  (canonical db-scheduler `scheduled_tasks`, priority-enabled) and `V2__runner_state.sql`
  (`rule_cursor`, `run_history`, `dlq_item`, `spend_counter`).
- **Error contract**: `ErrorView` + `DefaultExceptionHandler` producing the stable
  `{status, error, message, path, method}` shape.
- **Operations**: Actuator health/info/prometheus, OpenTelemetry OTLP export (disabled by default),
  springdoc OpenAPI; container image (Dockerfile + entrypoint, no Arrow JVM flags) and GitLab CI.
- **Docs**: `docs/configuration.md` (authoritative env-var reference), project README, `CLAUDE.md`,
  and OpenSpec scaffolding.

## Capabilities

### New Capabilities
- `service-runtime`: the bootable service — build/style gates, application context, PostgreSQL
  runtime-state schema provisioned by Flyway, the stable error contract, and env-driven config.
- `observability`: operational surface — Actuator health (liveness/readiness), Prometheus metrics,
  and OpenTelemetry OTLP export.

### Modified Capabilities
<!-- none: greenfield repository, no existing specs -->

## Impact

- New repository scaffolding: `build.gradle`, `settings.gradle`, gradle wrapper, `checkstyle/`,
  `Dockerfile`, `docker-entrypoint.sh`, `.gitlab-ci.yml`, `.gitignore`, `.gitattributes`.
- New code under `com.epam.aidial.enrichment`: `AnalyticsEnrichmentRunnerApplication`,
  `configuration/JsonMapperConfiguration`, `web/handler/{ErrorView,DefaultExceptionHandler}`.
- New resources: `application.yml`, `db/migration/postgres/V1__db_scheduler.sql`,
  `V2__runner_state.sql`.
- Tests require a running Docker daemon (Testcontainers PostgreSQL).
- No external service is called yet; ADAS/DIAL clients land in later changes.
