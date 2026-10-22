## 1. Build system & style

- [x] 1.1 Add `settings.gradle`, `gradle.properties`, and the Gradle wrapper (9.6.0)
- [x] 1.2 Add `build.gradle` (Boot 4.0.6, Java 25 toolchain, `-Werror`, Lombok, MapStruct, trimmed
  deps: no ClickHouse/blob/Calcite/Arrow/Security) with CVE constraints
- [x] 1.3 Add Checkstyle config (`checkstyle/checkstyle.xml` + suppressions), `.gitignore`,
  `.gitattributes`

## 2. Application skeleton, config & error contract

- [x] 2.1 Add `AnalyticsEnrichmentRunnerApplication` (base package `com.epam.aidial.enrichment`)
- [x] 2.2 Add `configuration/JsonMapperConfiguration` (Jackson 3, case-insensitive enum reads)
- [x] 2.3 Add `web/handler/ErrorView` + `DefaultExceptionHandler` (stable error contract)
- [x] 2.4 Add `application.yml` (`${ENV:default}`: datasource, flyway, server, management/OTel,
  springdoc, logging)

## 3. Runtime-state schema (Flyway)

- [x] 3.1 Add `db/migration/postgres/V1__db_scheduler.sql` (priority-enabled `scheduled_tasks`)
- [x] 3.2 Add `db/migration/postgres/V2__runner_state.sql` (`rule_cursor`, `run_history`,
  `dlq_item`, `spend_counter`)

## 4. Container, CI & docs

- [x] 4.1 Add `Dockerfile` (no Arrow JVM flags) + `docker-entrypoint.sh`
- [x] 4.2 Add `.gitlab-ci.yml` (gradle build with Docker-in-Docker for Testcontainers)
- [x] 4.3 Add `docs/configuration.md` (authoritative env-var tables) and project `README.md`
- [x] 4.4 Add `CLAUDE.md` and OpenSpec scaffolding (`openspec/`, `.claude/opsx`)

## 5. Verification

- [x] 5.1 Add `@SpringBootTest` boot test (Testcontainers PostgreSQL) asserting context loads,
  Flyway provisioned the schema, and `/actuator/health` is UP
- [x] 5.2 `./gradlew clean build` passes (compile + checkstyle + tests)
- [x] 5.3 `openspec validate bootstrap-runner-service --strict` passes
