# Configuration

Property reference for the Analytics Enrichment Runner: **what each variable is and what to set it
to**. Every operationally relevant property is bound to an environment variable in
`src/main/resources/application.yml` using the `${ENV_VAR:default}` convention. Why a limit exists,
what a subsystem does with it, and what a refusal means are specified in
`openspec/specs/<capability>/spec.md`.

## Overview

Values may be supplied as environment variables, YAML/properties files, command-line arguments or
system properties, resolved in Spring Boot's normal precedence order. The `Property` column is the
`application.yml` path — what an external YAML file overrides. Durations take Spring's format (`30s`,
`5m`, `7d`) and cron expressions Spring's six fields.

**Required** is `Yes` when a real deployment cannot work correctly on the default, `No` when the
variable is tuning and safe to leave alone, and otherwise the short condition under which it is
required. Supply anything described as a password, key or secret from the deployment's secret store,
and never commit it.

Every variable has a default, so the service starts with none of them set. The defaults describe a
local development stack: PostgreSQL on `localhost:5432`, ADAS and DIAL Core both on `localhost:5000`.
A local run must move `HTTP_PORT`, because a local ADAS or DIAL Core already holds `5000` — see
[`e2e-runbook.md`](e2e-runbook.md). Point `SPRING_CONFIG_ADDITIONAL_LOCATION` at a file or directory
(`file:/config/override.yml`, `file:/config/`) to override any property from outside the image; that
is also the practical way to supply the map-valued `providers.*` block.

## Container environment

Read by `docker-entrypoint.sh` and the base image's certificate hook, not by Spring:

| Variable                            | Default   | Required | Description                                                                                             |
|-------------------------------------|-----------|----------|---------------------------------------------------------------------------------------------------------|
| `USE_SYSTEM_CA_CERTS`               | _(unset)_ | No       | Any non-empty value (`1`, `true`, `yes`) imports certificates mounted into `/certificates/` at startup. |
| `JAVA_OPTS`                         | _(empty)_ | No       | Extra JVM arguments appended to the launch command.                                                     |
| `DEBUG_OPTS`                        | _(empty)_ | No       | JVM debug arguments, applied before `JAVA_OPTS`.                                                        |
| `SPRING_CONFIG_ADDITIONAL_LOCATION` | _(unset)_ | No       | External YAML/properties location overriding any property here.                                         |

The image's `HEALTHCHECK` follows `HTTP_PORT` and targets `/actuator/health/liveness`, so overriding
the port does not silently break it. The container persists nothing to its filesystem and runs with a
read-only root given a writable `/tmp`.

## Spring framework

### Server

| Variable           | Property                                      | Default | Required | Description                                                                                                                                      |
|--------------------|-----------------------------------------------|---------|----------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| `HTTP_PORT`        | `server.port`                                 | `5000`  | No       | Port the runner serves HTTP on — functional endpoints under `/v1`, operational under `/actuator/*`. The `dial-extension` chart's container port. |
| `SHUTDOWN_TIMEOUT` | `spring.lifecycle.timeout-per-shutdown-phase` | `30s`   | No       | On SIGTERM the server stops accepting requests and waits this long for in-flight work before forcing exit.                                       |

### Logging

| Variable                  | Property                                               | Default                               | Required | Description                                                                                 |
|---------------------------|--------------------------------------------------------|---------------------------------------|----------|---------------------------------------------------------------------------------------------|
| `APP_LOG_LEVEL`           | `logging.level.com.epam.aidial.enrichment`             | `INFO`                                | No       | Log level for the application package (`TRACE`/`DEBUG`/`INFO`/`WARN`/`ERROR`).              |
| `LOGGER_STATUS_LOG_LEVEL` | _(read by `log4j2-spring.xml`, not `application.yml`)_ | `WARN`                                | No       | Level of Log4j2's own internal status logger (configuration diagnostics).                   |
| `LOG_CONFIG_PATH`         | `observability.log-levels.path`                        | `/app/log-config/logging.levels.json` | No       | Runtime level file (below). A missing file changes nothing.                                 |
| `LOG_CONFIG_INTERVAL`     | `observability.log-levels.interval`                    | `10s`                                 | No       | How often the level file is re-read; at least `1s`.                                         |
| `EXECUTION_TRACE_ENABLED` | `observability.execution-trace.enabled`                | `true`                                | No       | Call tracing for `@LogExecution` classes, active only while a class's logger is at `TRACE`. |
| `STATUS_REPORT_INTERVAL`  | `observability.status-report.interval`                 | `5m`                                  | No       | How often each pipeline logs its status line.                                               |

**Runtime levels.** To change a level without a restart, put it in the level file, and give anything below
`INFO` a `validTill` (epoch milliseconds) so it reverts on its own:

```json
{"loggerLevels": {
   "com.epam.aidial.enrichment.class1": {"defaultLevel": "INFO", "configuredLevel": "DEBUG", "validTill": 1790000000000}}}
```

In Kubernetes, mount the file from a ConfigMap as a **directory** at `/app/log-config` — a `subPath` mount never
sees updates — and allow about a minute for a change to arrive. `LOGGING_LEVEL_<PACKAGE>` variables still set
static levels at startup.

Not configurable: `org.springframework.web.client`, `org.springframework.http.converter`, `org.hibernate.SQL`,
`org.hibernate.orm.jdbc.bind` and `org.springframework.jdbc.core` are pinned at `INFO`, and neither
`APP_LOG_LEVEL` nor the level file can lower them — below `INFO` they print request bodies, which here are
rendered prompts, and bound SQL values.

### API documentation (springdoc / Swagger UI)

Both are enabled by default (`/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**`). In `oidc` mode
they require a `FULL_ADMIN` credential, like the endpoints they describe, unless
`DISABLE_SWAGGER_AUTHORIZATION=true` serves them without one. In `none` mode they are open, as is
everything else.

| Variable                        | Property                                 | Default | Required | Description                                                                                                                                                                      |
|---------------------------------|------------------------------------------|---------|----------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `API_DOCS_ENABLED`              | `springdoc.api-docs.enabled`             | `true`  | No       | Serve the OpenAPI document at `/v3/api-docs`; `false` also disables the UI, which is rendered from it.                                                                           |
| `SWAGGER_UI_ENABLED`            | `springdoc.swagger-ui.enabled`           | `true`  | No       | Serve the Swagger UI at `/swagger-ui.html`, keeping the OpenAPI JSON available.                                                                                                  |
| `DISABLE_SWAGGER_AUTHORIZATION` | `security.disable-swagger-authorization` | `false` | No       | Serve the OpenAPI document and Swagger UI without a credential in `oidc` mode. They map every functional endpoint, so leave it `false` wherever the runner is broadly reachable. |

### Health probes

Actuator exposes `health`, `info` and `prometheus` over HTTP, and those paths stay reachable without a
credential in every security mode. The three health paths are **not interchangeable**:

| Probe     | Endpoint                     | Depends on                                                        |
|-----------|------------------------------|-------------------------------------------------------------------|
| liveness  | `/actuator/health/liveness`  | process state only — nothing external                             |
| readiness | `/actuator/health/readiness` | PostgreSQL reachability **and** one successful ADAS pipeline sync |
| startup   | `/actuator/health/readiness` | as above; allow headroom for the first sync                       |

Never point a liveness probe at the aggregate `/actuator/health`: it includes the `db` and
`pipelineSync` contributors, so a PostgreSQL blip or a sync that has never succeeded would restart the
container. Size the startup probe's `failureThreshold` to cover `ADAS_PIPELINES_INITIAL_DELAY_MS` plus
at least one `ADAS_PIPELINES_POLL_INTERVAL_MS`, or a slow-starting ADAS has the pod killed before it
ever syncs.

Not configurable: the actuator endpoints exposed over HTTP are fixed to `health,info,prometheus`, the
health endpoint always returns full details, and both probe groups are always enabled.

### OpenTelemetry

All export is disabled by default. `OTEL_EXPORT_ENABLED` is the master switch; each per-signal flag
overrides it for its signal. The base endpoint is fanned out to the per-signal URLs by appending
`/v1/traces`, `/v1/logs` and `/v1/metrics`. The Prometheus scrape endpoint stays active regardless.

| Variable                              | Property                                                                             | Default                       | Required              | Description                                                                                                                   |
|---------------------------------------|--------------------------------------------------------------------------------------|-------------------------------|-----------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `OTEL_EXPORT_ENABLED`                 | _(fallback for the flags below)_                                                     | `false`                       | No                    | Master switch for OTLP export of traces, logs and metrics.                                                                    |
| `OTEL_TRACES_EXPORT_ENABLED`          | `management.tracing.export.enabled`                                                  | `OTEL_EXPORT_ENABLED`         | No                    | Export spans over OTLP.                                                                                                       |
| `OTEL_LOGS_EXPORT_ENABLED`            | `management.logging.export.otlp.enabled`                                             | `OTEL_EXPORT_ENABLED`         | No                    | Export application logs over OTLP.                                                                                            |
| `OTEL_METRICS_EXPORT_ENABLED`         | `opentelemetry.metrics.enabled`                                                      | `OTEL_EXPORT_ENABLED`         | No                    | Export metrics over OTLP, additively to the Prometheus endpoint.                                                              |
| `OTEL_EXPORTER_OTLP_ENDPOINT`         | _(base for the endpoints below)_                                                     | `http://localhost:4318`       | If any export enabled | Collector base URL, no trailing slash. The default points at localhost.                                                       |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`  | `management.opentelemetry.tracing.export.otlp.endpoint`                              | base + `/v1/traces`           | No                    | Full traces URL; overrides the base for this signal.                                                                          |
| `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT`    | `management.opentelemetry.logging.export.otlp.endpoint`                              | base + `/v1/logs`             | No                    | Full logs URL; overrides the base for this signal.                                                                            |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | `opentelemetry.metrics.endpoint`                                                     | base + `/v1/metrics`          | No                    | Full metrics URL; overrides the base for this signal.                                                                         |
| `OTEL_EXPORTER_OTLP_PROTOCOL`         | `management.opentelemetry.*.export.otlp.transport`, `opentelemetry.metrics.protocol` | `http`                        | No                    | Transport for all three signals: `http` or `grpc`.                                                                            |
| `OTEL_TRACES_SAMPLING_PROBABILITY`    | `management.tracing.sampling.probability`                                            | `1.0`                         | No                    | Probability (0.0–1.0) that a new trace is sampled.                                                                            |
| `OTEL_METRICS_EXPORT_INTERVAL`        | `opentelemetry.metrics.interval`                                                     | `60s`                         | No                    | How often accumulated metrics are flushed. Traces and logs are batched by the SDK's own defaults and have no equivalent knob. |
| `OTEL_SERVICE_NAME`                   | `management.opentelemetry.resource-attributes.service.name`                          | `analytics-enrichment-runner` | No                    | `service.name` resource attribute reported to telemetry backends.                                                             |

Not configurable: `management.otlp.metrics.export.enabled` is pinned `false`. All three signals go
through one OpenTelemetry SDK so they share a resource; Boot's Micrometer OTLP exporter turns itself
on whenever its registry is on the classpath, and would otherwise export every meter a second time
under a resource of its own. Use `OTEL_METRICS_EXPORT_ENABLED`.

## Runtime state

### PostgreSQL

The runner owns a PostgreSQL database holding db-scheduler's job tables plus its own runtime tables
(`pipeline_cursor`, `run_history`, `dlq_item`, `spend_counter`, `group_state`). It holds **no
ClickHouse connection** — source reads and enrichment writes go through ADAS. It needs its own
*database*, not its own server.

The first four variables compose `spring.datasource.url`
(`jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DATABASE}?${POSTGRES_OPS}`) rather
than binding one-to-one.

| Variable                       | Property                             | Default     | Required | Description                                                                                                                            |
|--------------------------------|--------------------------------------|-------------|----------|----------------------------------------------------------------------------------------------------------------------------------------|
| `POSTGRES_HOST`                | _(composes `spring.datasource.url`)_ | `localhost` | Yes      | PostgreSQL host.                                                                                                                       |
| `POSTGRES_PORT`                | _(composes `spring.datasource.url`)_ | `5432`      | No       | PostgreSQL port.                                                                                                                       |
| `POSTGRES_DATABASE`            | _(composes `spring.datasource.url`)_ | `testdb`    | Yes      | Database name.                                                                                                                         |
| `POSTGRES_OPS`                 | _(composes `spring.datasource.url`)_ | _(empty)_   | No       | Extra JDBC query options appended after `?`, e.g. `sslmode=require`.                                                                   |
| `POSTGRES_DATASOURCE_USERNAME` | `spring.datasource.username`         | `postgres`  | Yes      | Datasource user. Needs DDL on its own schema when migrations are enabled.                                                              |
| `POSTGRES_DATASOURCE_PASSWORD` | `spring.datasource.password`         | `postgres`  | Yes      | Password for that user. Supply from the deployment's secret store.                                                                     |
| `POSTGRES_MIGRATIONS_ENABLED`  | `spring.flyway.enabled`              | `true`      | No       | Run the Flyway migrations under `classpath:db/migration/postgres` at startup. Disable only when the schema is provisioned out of band. |

### Job scheduler (db-scheduler)

The Postgres-backed queue for recurring scan and materialize ticks, group sweeps, and enrichment work
items.

| Variable                        | Property                        | Default | Required | Description                                                                                                                                     |
|---------------------------------|---------------------------------|---------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `DB_SCHEDULER_HEARTBEAT`        | `db-scheduler.heartbeat`        | `45s`   | No       | How often a running execution updates its heartbeat. Tuned below the library default so a crashed replica's in-flight work is revived promptly. |
| `DB_SCHEDULER_POLLING_INTERVAL` | `db-scheduler.polling-interval` | `10s`   | No       | How often the scheduler polls for due executions.                                                                                               |
| `DB_SCHEDULER_THREADS`          | `db-scheduler.threads`          | `3`     | No       | Worker threads consuming due executions. Keep threads × ADAS's 2 GiB read ceiling under ClickHouse's free memory; 8 GiB fits 3, 16 GiB fits 5.  |
| `DB_SCHEDULER_AUTO_START`       | `db-scheduler.auto-start`       | `true`  | No       | Whether the scheduler starts polling on application start.                                                                                      |

Not configurable: the datasource keeps Spring Boot's HikariCP defaults, so at most 10 connections —
the ceiling the scheduler's threads, the worker's row parallelism and the serving threads share.

## Upstreams

### ADAS

Pipeline definitions, source scans, group assembly, materialize and enrichment write-back all go
through ADAS. `ADAS_API_KEY` is an **outbound** credential — what the runner presents *to* ADAS — and
authenticates nothing inbound.

A pipeline referencing a `sensitive` column needs this key to resolve to an identity entitled to read
sensitive columns (`FULL_ADMIN`) — ADAS rejects rather than strips, so a narrower identity fails such
a pipeline outright. The check fails open when ADAS security is disabled, the local development
default, so a local run cannot surface a misconfigured production identity.

Keep `ADAS_READ_TIMEOUT_MS` **above** ADAS's own `PIPELINE_BUDGET_TIMEOUT`, and with room for the
several statements one group fetch issues. At equal values the two expire together: ADAS finishes
writing the `422` that names its ceiling into a socket the runner has already closed, the runner reads
the aborted body as `application/octet-stream` rather than as a timeout, and retries the same call
indefinitely. The defaults leave a 60 s margin.

A materialize call's probes and writes share one `PIPELINE_BUDGET_TIMEOUT`, so the margin above covers it
whole; a group fetch needs the extra room. Raise the two together.

| Variable                          | Property                          | Default                 | Required | Description                                                                                                                                     |
|-----------------------------------|-----------------------------------|-------------------------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `ADAS_BASE_URL`                   | `adas.base-url`                   | `http://localhost:5000` | Yes      | ADAS base URL.                                                                                                                                  |
| `ADAS_API_KEY`                    | `adas.api-key`                    | _(empty)_               | Yes      | DIAL `Api-Key` presented to ADAS. Supply from the deployment's secret store.                                                                    |
| `ADAS_CONNECT_TIMEOUT_MS`         | `adas.connect-timeout-ms`         | `5000`                  | No       | HTTP connect timeout for ADAS calls.                                                                                                            |
| `ADAS_READ_TIMEOUT_MS`            | `adas.read-timeout-ms`            | `180000`                | No       | HTTP read timeout for ADAS calls. Must stay above ADAS's `PIPELINE_BUDGET_TIMEOUT`; equal values let a refusal race a socket timeout.           |
| `ADAS_PIPELINES_POLL_INTERVAL_MS` | `adas.pipelines.poll-interval-ms` | `30000`                 | No       | How often the runner polls `GET /v1/pipelines?kind=enrich&enabled=true` to refresh the pipeline cache and reconcile recurring tasks.            |
| `ADAS_PIPELINES_INITIAL_DELAY_MS` | `adas.pipelines.initial-delay-ms` | `10000`                 | No       | Delay before the first sync. Readiness reports DOWN until that sync succeeds, so size a startup probe for this plus at least one poll interval. |

### DIAL Core

The worker's chat/completions calls. One `Api-Key` can serve both ADAS auth and model calls, so
`DIAL_API_KEY` falls back to `ADAS_API_KEY`. The Class-1 lane makes no DIAL calls and is unaffected by
everything here.

| Variable                    | Property                    | Default                 | Required  | Description                                                                                                                                                                                                                                                       |
|-----------------------------|-----------------------------|-------------------------|-----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `DIAL_BASE_URL`             | `dial.base-url`             | `http://localhost:5000` | LLM lanes | DIAL Core base URL.                                                                                                                                                                                                                                               |
| `DIAL_API_KEY`              | `dial.api-key`              | `ADAS_API_KEY`          | No        | DIAL `Api-Key` for chat/completions. Leave unset when one key serves both; supply from the deployment's secret store otherwise.                                                                                                                                   |
| `DIAL_API_VERSION`          | `dial.api-version`          | `2025-01-01-preview`    | No        | The `api-version` query parameter on every chat/completions call. Core forwards the query string verbatim and the Azure-OpenAI-compatible adapters reject a request without it, so match what the target deployment's adapter expects. Blank omits the parameter. |
| `DIAL_CONNECT_TIMEOUT_MS`   | `dial.connect-timeout-ms`   | `5000`                  | No        | HTTP connect timeout for DIAL calls.                                                                                                                                                                                                                              |
| `DIAL_READ_TIMEOUT_MS`      | `dial.read-timeout-ms`      | `60000`                 | No        | HTTP read timeout for DIAL calls.                                                                                                                                                                                                                                 |
| `DIAL_DEFAULT_RPM`          | `dial.default-rpm`          | `60`                    | No        | Per-pipeline requests per minute when a pipeline declares no `advanced.rate_rpm`. Per-process, so DIAL Core's own per-key limit is the real backstop.                                                                                                             |
| `DIAL_RATE_LIMITER_TIMEOUT` | `dial.rate-limiter-timeout` | `5s`                    | No        | How long a worker waits for a rate-limit permit before failing the chunk, which then retries with backoff.                                                                                                                                                        |

## Pipeline execution

### Scan

Page sizing for the row lane, and for a group pipeline's dirty-mark. A pipeline's own `advanced.*`
knobs override the defaults; the `MAX` values are ceilings the runner clamps to. There is no cadence
here: a row-grain pipeline, on this lane or the Class-1 one, fires on its `trigger.cron`, and a group
pipeline's mark scan on `GROUP_SCAN_CADENCE`.

| Variable                  | Property                  | Default | Required | Description                                                                                                                                                      |
|---------------------------|---------------------------|---------|----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SCAN_DEFAULT_SCAN_LIMIT` | `scan.default-scan-limit` | `1000`  | No       | Scan page size when a pipeline declares no `advanced.rows_per_scan`.                                                                                             |
| `SCAN_MAX_SCAN_LIMIT`     | `scan.max-scan-limit`     | `10000` | No       | Ceiling the runner clamps a page to. Keep at or below ADAS's own `enrichment.scan.max-limit` — ADAS answers an over-limit scan with 400 rather than clamping it. |
| `SCAN_DEFAULT_CHUNK`      | `scan.default-chunk`      | `25`    | No       | Work-item chunk size when a pipeline declares no `advanced.rows_per_call`.                                                                                       |
| `SCAN_MAX_INFLIGHT`       | `scan.max-inflight`       | `1000`  | No       | Backpressure: a scan fire is skipped while the pipeline's pending + delayed chunk depth exceeds this.                                                            |
| `SCAN_MAX_PAGES_PER_FIRE` | `scan.max-pages-per-fire` | `50`    | No       | Per-fire drain budget — at most this many pages per scheduled fire. Shared with the Class-1 lane.                                                                |

### Worker

Row-lane model calls, response validation and write-back. Does not apply to the Class-1 lane.

| Variable                 | Property                 | Default | Required | Description                                                                                                                                   |
|--------------------------|--------------------------|---------|----------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| `WORKER_REPAIR_RETRIES`  | `worker.repair-retries`  | `1`     | No       | How many times an invalid model response is re-requested, with the validation error as a corrective message, before the row is dead-lettered. |
| `WORKER_ROW_PARALLELISM` | `worker.row-parallelism` | `4`     | No       | Rows evaluated concurrently within one chunk; `1` is sequential.                                                                              |
| `WORKER_MAX_ATTEMPTS`    | `worker.max-attempts`    | `5`     | No       | Consecutive chunk failures retried before the chunk is archived to the DLQ.                                                                   |
| `WORKER_BACKOFF_BASE`    | `worker.backoff-base`    | `10s`   | No       | Base delay for exponential backoff with jitter between chunk retries.                                                                         |

### Class-1 (SQL) materialize

A pipeline whose `transform.type` is `sql` is computed set-based inside ClickHouse by ADAS; the runner
drives only the window and the cursor. The lane makes no DIAL calls, enqueues no work items and keeps
no dead-letter queue, so the DIAL, worker, DLQ and spend settings do not apply to it. Its per-fire
drain budget is `SCAN_MAX_PAGES_PER_FIRE`.

| Variable                 | Property                 | Default  | Required | Description                                                                                                                                                         |
|--------------------------|--------------------------|----------|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `CLASS1_DEFAULT_LIMIT`   | `class1.default-limit`   | `10000`  | No       | Window size in source rows when a pipeline declares no `advanced.rows_per_scan`.                                                                                    |
| `CLASS1_MAX_LIMIT`       | `class1.max-limit`       | `100000` | No       | Ceiling the runner clamps a window to. Keep at or below ADAS's own `enrichment.materialize.max-limit` — ADAS rejects an over-limit request rather than clamping it. |

### Group enrichment

A `trigger.kind: group` pipeline is evaluated once per group key. The ordinary scan tick doubles as
the dirty-mark, so its cost is proportional to new rows rather than to the number of groups; a
separate readiness sweep then decides which dirty groups are worth evaluating. Which readiness
conditions apply, and the per-group daily `ready_when.cost_ceiling`, are declared on the pipeline in
ADAS rather than here.

| Variable                    | Property                    | Default        | Required | Description                                                                                                                                                                                                                                                                    |
|-----------------------------|-----------------------------|----------------|----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GROUP_SCAN_CADENCE`        | `group.scan-cadence`        | `1m`           | No       | Fixed delay of a group pipeline's mark scan. At least `1s`, or startup fails. Keep it well below the shortest `ready_when.idle` and `GROUP_DEFAULT_IDLE`: a group's later rows sit unmarked for up to one cadence, so a still-active group can look idle and be evaluated early, then again. Sync WARNs once per generation for a group pipeline whose effective idle window is under twice this. Keep it the same on every replica: each poll rewrites a stored schedule that differs, so mixed values flap. |
| `GROUP_SWEEP_INTERVAL`      | `group.sweep-interval`      | `30s`          | No       | How often the readiness sweep runs. Sweeping more often than groups change is harmless: work items are keyed by group version, so a re-sweep of an unchanged group is suppressed.                                                                                              |
| `GROUP_SWEEP_INITIAL_DELAY` | `group.sweep-initial-delay` | `15s`          | No       | Delay before the first sweep, so the first pipeline sync completes before groups are swept.                                                                                                                                                                                    |
| `GROUP_SWEEP_BATCH`         | `group.sweep-batch`         | `500`          | No       | Maximum groups one sweep pass enqueues per pipeline, so a large dirty backlog cannot monopolise a single pass.                                                                                                                                                                 |
| `GROUP_MAX_INFLIGHT`        | `group.max-inflight`        | `0`            | No       | Ceiling on one group pipeline's pending work (compute plus write-back); the sweep defers the rest. `0` derives it as `DB_SCHEDULER_THREADS` × 10.                                                                                                                             |
| `GROUP_EVICTION_CRON`       | `group.eviction-cron`       | `0 30 3 * * *` | No       | Schedule for evicting quiet groups from `group_state`.                                                                                                                                                                                                                         |
| `GROUP_EVICTION_MARGIN`     | `group.eviction-margin`     | `7d`           | No       | Grace added on top of a pipeline's readiness window before its quiet, *clean* groups may be evicted. A dirty group is never evicted.                                                                                                                                           |
| `GROUP_DEFAULT_IDLE`        | `group.default-idle`        | `10m`          | No       | Idle window when a pipeline declares no `ready_when.idle`. Applies to a signal-only pipeline too, as the backstop that gets a never-signalled group evaluated and eventually evicted.                                                                                          |
| `GROUP_FETCH_LIMIT`         | `group.fetch-limit`         | `200`          | No       | Members requested per group. Keep at or below ADAS's own `enrichment.group-fetch.max-rows` — ADAS rejects a larger value rather than clamping it.                                                                                                                              |
| `GROUP_INPUT_TOKEN_BUDGET`  | `group.input-token-budget`  | `60000`        | No       | Approximate token ceiling on a group's assembled input, applied on top of ADAS's row and byte caps; `0` disables the runner's own trimming. Tune against `runner_group_input_budget_cut_total`, not against the `truncated` rate, which a narrowing `member_select` also sets. |

### Spend

Best-effort usage accounting per pipeline, generation and UTC day. The budget sums every generation of a pipeline's
day, so editing a declaration does not reset it. The gate is soft: it re-evaluates each
scan fire against the current UTC day's spend, so a paused pipeline resumes when the day rolls over.
**Nothing caps spend unless this is switched on** — worth setting before exposing the backfill
endpoints. Enabling the gate needs at least one non-zero budget, or it never trips.

| Variable                    | Property                    | Default | Required                 | Description                                                         |
|-----------------------------|-----------------------------|---------|--------------------------|---------------------------------------------------------------------|
| `SPEND_PAUSE_ENABLED`       | `spend.pause.enabled`       | `false` | No                       | Skip a pipeline at enqueue once its daily spend exceeds the budget. |
| `SPEND_BUDGET_DAILY_CALLS`  | `spend.budget.daily-calls`  | `0`     | If `SPEND_PAUSE_ENABLED` | Maximum successful DIAL calls per pipeline per day; failed calls are counted separately and not charged. `0` is unlimited. |
| `SPEND_BUDGET_DAILY_TOKENS` | `spend.budget.daily-tokens` | `0`     | If `SPEND_PAUSE_ENABLED` | Maximum token usage per pipeline per day; `0` is unlimited.         |

### Dead-letter queue and circuit breaker

Items that exhaust their retries are archived to `dlq_item` with their raw response. A breaker keyed
on dead-letter rate auto-pauses a pipeline's enqueue for `DLQ_BREAKER_PAUSE_FOR`, doubling on each consecutive
trip up to `DLQ_BREAKER_PAUSE_MAX`; the pause then lifts itself, unless `DLQ_BREAKER_PAUSE_FOR` is `0`, which
disables expiry. An operator can lift it earlier with `POST /v1/pipelines/{name}/resume`. Pauses are held in memory, so a restart lifts every pause.

| Variable                    | Property                    | Default       | Required | Description                                                                                                                                                                                                   |
|-----------------------------|-----------------------------|---------------|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `DLQ_RETENTION_DAYS`        | `dlq.retention-days`        | `30`          | No       | How long dead-lettered items and their raw responses are kept before the TTL sweep deletes them. This is the only place derived content persists.                                                             |
| `DLQ_SWEEP_CRON`            | `dlq.sweep-cron`            | `0 0 3 * * *` | No       | Cron schedule for the DLQ TTL sweep.                                                                                                                                                                          |
| `DLQ_BREAKER_THRESHOLD`     | `dlq.breaker.threshold`     | `500`         | No       | Absolute dead-letter count within one scan page or worker chunk that auto-pauses the pipeline. Non-positive disables this lever.                                                                              |
| `DLQ_BREAKER_FRACTION`      | `dlq.breaker.fraction`      | `0.5`         | No       | Fraction that must be dead-lettered to auto-pause: of a scan page or worker chunk on the row lane, of the recent-outcome window on the group lane. Non-positive disables this lever.                          |
| `DLQ_BREAKER_GROUP_WINDOW`  | `dlq.breaker.group-window`  | `50`          | No       | How many of a pipeline's most recent group outcomes the fraction is measured over. The window must be full before it can trip, so this is also the minimum sample; `0` disables auto-pause for group pipelines|
| `DLQ_BREAKER_PAUSE_FOR`     | `dlq.breaker.pause-for`     | `15m`         | No       | How long the first auto-pause of a pipeline lasts before it resumes itself; each consecutive auto-pause doubles it. `0` disables expiry.                                                                      |
| `DLQ_BREAKER_PAUSE_MAX`     | `dlq.breaker.pause-max`     | `4h`          | No       | Ceiling on that doubling.                                                                                                                                                                                     |

The two row-lane levers cross at `DLQ_BREAKER_THRESHOLD / DLQ_BREAKER_FRACTION` rows — 1000 at the
defaults, so an ordinary page is judged proportionally and the count only backstops a pipeline that
raises `advanced.rows_per_scan`. The count never applies to a group work item, which is not a page.

## Security

### Authentication

In `oidc` mode every `/v1` endpoint requires a credential holding `FULL_ADMIN` — an OIDC `Bearer`
token from a configured [identity provider](#identity-providers), or a DIAL
[`Api-Key` header](#api-key-authentication) when that is enabled; `Bearer` takes precedence when both
are sent. The operational paths (`/actuator/health/**`, `/actuator/info`, `/actuator/prometheus`) stay
public; the springdoc UI and OpenAPI document are public only with
[`DISABLE_SWAGGER_AUTHORIZATION=true`](#api-documentation-springdoc--swagger-ui).

In `none` mode — the default — all endpoints are open, so any caller that can reach the port can start
a backfill or requeue a dead-letter item (real DIAL model calls, so real spend) or pause a pipeline,
acting through the runner's own outbound identity. Deploy `none` only on a trusted, non-internet-exposed
network segment. The startup log states the posture in effect, at WARN when it is open.

Startup fails on a `SECURITY_MODE` value that is not exactly `none` or `oidc` in lowercase, and on
`oidc` with no usable identity provider. The endpoint-by-endpoint authorization model and the error
codes a rejection carries are in `openspec/specs/api-authentication/spec.md`.

| Variable                 | Property                            | Default                                                 | Required   | Description                                                                                                                                                                                                                     |
|--------------------------|-------------------------------------|---------------------------------------------------------|------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SECURITY_MODE`          | `security.mode`                     | `none`                                                  | If exposed | `oidc` enforces authentication and role-based authorization; `none` permits every request. Must be exactly one of those two, lowercase.                                                                                         |
| `SECURITY_REQUIRE_EMAIL` | `security.require-email`            | `false`                                                 | No         | Reject a caller whose identity carries no resolvable email claim. A DIAL project key is exempt, being a service identity Core returns no email for. `oidc` only.                                                                |
| `SECURITY_ROLES_MAPPING` | `security.defaults.roles-mapping`   | `{"ConfigAdmin":["FULL_ADMIN"],"admin":["FULL_ADMIN"]}` | No         | Default JSON mapping of provider role names to application roles (`FULL_ADMIN` / `READ_ONLY_ADMIN`), merged under each provider's own mapping. Also used for JWT-rooted API keys. A role with no entry is dropped. `oidc` only. |
| `SECURITY_EMAIL_CLAIM`   | `security.defaults.email-claim`     | `unique_name`                                           | No         | Default claim to read the caller's email from when a provider sets none. `oidc` only.                                                                                                                                           |
| `SECURITY_USER_CLAIM`    | `security.defaults.principal-claim` | `oid`                                                   | No         | Default claim used as the authenticated principal name when a provider sets none. Note the name mismatch between variable and property. `oidc` only.                                                                            |

`READ_ONLY_ADMIN` is accepted as a mapping target but grants nothing here: every `/v1` endpoint
requires `FULL_ADMIN`, reads included, because the runner holds no per-target grants.

### Identity providers

Configured under the top-level `providers.<name>` map, where `<name>` is an arbitrary local label —
the same layout as ADAS and ai-dial-admin-backend. At least one usable provider is required when
`SECURITY_MODE=oidc`, or startup fails. Both token shapes are accepted, and may be configured side by
side; which one applies is decided per request from the credential itself, never from configuration:

- **JWT providers** — set `issuer` **and** `jwk-set-uri`. A credential that parses as a signed JWT is
  verified locally against the matching issuer's JWKS. Two JWT providers may not accept the same issuer.
- **Opaque providers** — set `user-info-endpoint`. Any other credential is introspected by calling
  each opaque provider's userinfo endpoint until one accepts it.

A provider with neither is ignored. There is no fallback between the two routes, so a provider issuing
**JWT-shaped access tokens** (Okta, Auth0 and Keycloak do by default) must be declared as a JWT
provider — declaring only its `user-info-endpoint` leaves its callers unable to authenticate.

Startup also fails, naming the provider and the property, when a provider is declared but unusable —
each of these would otherwise start cleanly and then refuse every caller:

- no `role-claims` (**both kinds**): no provider role can be read, so every caller is refused `403`;
- `role-claims` naming a `fn:` converter that does not exist, or naming one on a provider with no
  `user-info-endpoint` (see [Role converters](#role-converters-for-opaque-providers));
- no `audiences` (**JWT providers**): the `aud` check then rejects every token with `401`;
- an `issuer` that is a tenant id rather than a URL with no `aliases` to expand it (**JWT providers**):
  the provider matches no issuer and every token is rejected with `401`.

Because the providers form a map keyed by an arbitrary name, they have **no fixed environment-variable
names** — hence property paths below rather than a variable column. Supply the `providers:` block in a
file referenced by `SPRING_CONFIG_ADDITIONAL_LOCATION`, which keeps the lists and the `roles-mapping`
JSON readable. Environment variables do bind via relaxed binding, with two traps: the uppercase form
**drops** the dashes inside a property name rather than replacing them with underscores
(`PROVIDERS_AZURE_JWKSETURI`, not `PROVIDERS_AZURE_JWK_SET_URI`, which binds to the wrong path and is
silently ignored), and the provider name must then be a single lowercase word. List properties take a
comma-separated value; `roles-mapping` takes a JSON string.

| Property                              | Example                               | Required         | Description                                                                                                                                                                                                    |
|---------------------------------------|---------------------------------------|------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `providers.<name>.issuer`             | `providers.azure.issuer`              | JWT providers    | Expected `iss` claim (a URL), or a tenant id when `aliases` is set.                                                                                                                                            |
| `providers.<name>.jwk-set-uri`        | `providers.azure.jwk-set-uri`         | JWT providers    | JWKS endpoint for the provider's signing keys. With `issuer`, marks the provider as a JWT provider.                                                                                                            |
| `providers.<name>.user-info-endpoint` | `providers.google.user-info-endpoint` | Opaque providers | OIDC userinfo endpoint introspected for opaque tokens. Marks the provider as an opaque provider.                                                                                                               |
| `providers.<name>.audiences`          | `providers.azure.audiences`           | JWT providers    | Accepted `aud` values; an `api://`-prefixed variant of each is also accepted. Not applicable to opaque providers.                                                                                              |
| `providers.<name>.role-claims`        | `providers.azure.role-claims`         | Yes              | Dotted claim paths to read provider role names from (`roles`, `resource_access.roles`). For opaque providers these read the userinfo response. A single `fn:`-prefixed entry selects a role converter instead. |
| `providers.<name>.email-claims`       | `providers.azure.email-claims`        | No               | Claim names to read the caller's email from, tried in order; falls back to `SECURITY_EMAIL_CLAIM`.                                                                                                             |
| `providers.<name>.principal-claim`    | `providers.azure.principal-claim`     | No               | Claim used as the principal name; falls back to `SECURITY_USER_CLAIM`.                                                                                                                                         |
| `providers.<name>.roles-mapping`      | `providers.azure.roles-mapping`       | No               | JSON mapping of this provider's role names to application roles, merged over `SECURITY_ROLES_MAPPING`.                                                                                                         |
| `providers.<name>.aliases`            | `providers.azure.aliases`             | No               | Host aliases used to expand Azure v1/v2 issuer URLs from a tenant id in `issuer`. JWT providers.                                                                                                               |

An opaque provider whose userinfo returns no role information — plain Google userinfo, for instance —
yields no application role, so such callers are refused `403` unless a default mapping applies or a
role converter is selected.

#### Role converters for opaque providers

A provider's `role-claims` may instead name a **role converter**: a single `fn:`-prefixed entry
standing alone (mixing it with claim paths is not supported). The converter derives provider role
names from another source using the caller's own token, and `roles-mapping` then maps them exactly as
it maps claim-derived names.

| Converter                     | Applies to                | Derives roles from                                                                                                                                                                 |
|-------------------------------|---------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `fn:getGoogleWorkspaceGroups` | Google / Google Workspace | The caller's **direct** Google Workspace group memberships, read from the Cloud Identity API with the caller's own token. Each group's email address becomes a provider role name. |

```yaml
providers:
  google:
    user-info-endpoint: https://openidconnect.googleapis.com/v1/userinfo
    principal-claim: sub
    email-claims: [email]
    role-claims: ["fn:getGoogleWorkspaceGroups"]
    roles-mapping: '{"analytics-admins@example.com":["FULL_ADMIN"]}'
```

`fn:getGoogleWorkspaceGroups` needs three things, or introspection fails with `401`: the Cloud
Identity API enabled for the workspace, the caller's email resolvable (set `email-claims: [email]` as
above — the `unique_name` default is Azure-shaped and never appears in a Google userinfo response),
and a token scoped for group-membership reads (e.g.
`https://www.googleapis.com/auth/cloud-identity.groups.readonly`). Only **direct** memberships
resolve and only the first page is read (a paginated response is logged as a warning), so map roles
onto a few dedicated groups rather than a caller's full group list. A cache miss costs one userinfo
call plus one Cloud Identity call.

### Opaque token introspection

Applies to opaque providers only. Successful introspections are cached, keyed by a digest of the
token, so a valid token reaches the provider at most once per TTL and a revoked one stops working
within one TTL; rejected tokens are never cached. At most 16 cache-miss introspections run at once (not
configurable), and a miss beyond that is answered `401` without contacting a provider.

| Variable                          | Property                                   | Default | Required | Description                                                                                                                                                          |
|-----------------------------------|--------------------------------------------|---------|----------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `OPAQUE_TOKEN_CACHE_TTL_SECONDS`  | `security.opaque-token.cache-ttl-seconds`  | `60`    | No       | TTL for cached successful introspections, and the worst-case revocation window. `0` disables caching; must not be negative.                                          |
| `OPAQUE_TOKEN_CACHE_MAX_SIZE`     | `security.opaque-token.cache-max-size`     | `10000` | No       | Maximum cached introspection entries. Must not be negative.                                                                                                          |
| `OPAQUE_TOKEN_REQUEST_TIMEOUT_MS` | `security.opaque-token.request-timeout-ms` | `3000`  | No       | Connect and read timeout for the userinfo call. **Must be greater than zero** — startup fails otherwise, because `0` means *no* timeout rather than the fastest one. |

A token no provider accepts is not cached and costs one round trip per opaque provider on the serving
thread, so keep the provider count small and this timeout tight wherever `/v1` is broadly reachable.
An opaque token carries no issuer, so it is offered to each opaque provider's userinfo endpoint in turn:
configure only opaque providers that are trusted to see each other's tokens.

### API-key authentication

Layered onto `oidc` mode: callers may present a DIAL `Api-Key` header instead of a `Bearer` token,
validated against DIAL Core's `/v1/user/info`. Project-key responses map through
`API_KEY_ROLES_MAPPING`; JWT-rooted per-request keys map through `SECURITY_ROLES_MAPPING` and the
`security.defaults.*` claim names, since they carry the role names that user would carry calling with
a JWT directly. Successful introspections are cached; rejected keys are not. At most 16 cache-miss
introspections run at once (not configurable), and a miss beyond that is answered `503` without
contacting Core. The credential-free
operational paths skip the filter entirely, so a bad header on a health probe is ignored rather than
turned into a `401`.

Inactive unless `SECURITY_MODE=oidc`, and that mode still requires at least one identity provider.

| Variable                     | Property                              | Default   | Required             | Description                                                                                                                                                                                                                                                                                  |
|------------------------------|---------------------------------------|-----------|----------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `API_KEY_ENABLED`            | `security.api-key.enabled`            | `false`   | No                   | Master switch. Only takes effect in `oidc` mode.                                                                                                                                                                                                                                             |
| `API_KEY_CORE_URL`           | `security.api-key.core-url`           | _(empty)_ | If `API_KEY_ENABLED` | DIAL Core base URL, e.g. `http://dial-core:8080`. Keys are introspected against `<core-url>/v1/user/info`.                                                                                                                                                                                   |
| `API_KEY_ROLES_MAPPING`      | `security.api-key.roles-mapping`      | _(empty)_ | Project-key callers  | JSON mapping of Core **project-key** role names to application roles, e.g. `{"admin":["FULL_ADMIN"]}`. There is no fallback: unset, a project key authenticates and is then refused `403`. Startup fails only when neither this nor `SECURITY_ROLES_MAPPING` maps any role.                  |
| `API_KEY_CACHE_TTL_SECONDS`  | `security.api-key.cache-ttl-seconds`  | `60`      | No                   | TTL for cached successful introspections. Also bounds how long a key revoked in Core keeps working.                                                                                                                                                                                          |
| `API_KEY_CACHE_MAX_SIZE`     | `security.api-key.cache-max-size`     | `10000`   | No                   | Maximum cached introspection entries.                                                                                                                                                                                                                                                        |
| `API_KEY_REQUEST_TIMEOUT_MS` | `security.api-key.request-timeout-ms` | `3000`    | No                   | Connect and read timeout for the Core introspection call.                                                                                                                                                                                                                                    |
| `API_KEY_STARTUP_PROBE`      | `security.api-key.startup-probe`      | `false`   | No                   | Probe Core reachability at startup and fail fast if unreachable, catching a wrong `API_KEY_CORE_URL` at once. Off by default so an unreachable Core does not stop the runner starting; enable it only with generous startup-probe headroom, or a Core outage surfaces as a CrashLoopBackOff. |

## Exposing endpoints from a cluster

**The default is to expose nothing.** Unlike ADAS the runner publishes no consumer-facing read
surface: its whole `/v1` surface is an operator control plane, and several of those controls spend
money on the deployment's behalf. Keep the Service `ClusterIP` and reach it with `kubectl port-forward`
or from an in-cluster admin backend. Expose it only after setting `SECURITY_MODE=oidc`, and treat the
ingress path list as the exposure allowlist.

## Release and deployment order

Enrichment Runner and ADAS ship as a pair. Deploy both halves in one window, with this service scaled to
zero for its whole length:

1. **Scale the runner to 0** and wait for the pod to be gone. A rolling update is not enough: it
   overlaps an old and a new runner pod against a half-updated ADAS.
2. **Deploy ADAS.**
3. **Deploy the runner** to the matching build.
4. **Scale the runner back to 1.** Readiness stays DOWN until the first pipeline sync succeeds, so a
   ready pod is itself the confirmation that the pair matches — size the startup probe as described
   under [Health probes](#health-probes).

Nothing is lost while the runner is at 0: no scan, no write-back, no DIAL spend, and no cursor moves.
The visible cost is on the ADAS side, where Class-1 materialize positions stop advancing until a
cadence after this service is back — keep the window short.

As a backstop, each side carries a build constant (`AdasContract.VERSION` here,
`RunnerContract.VERSION` there) that must match, and a mismatch stops the runner at its paid-work gate
instead of letting it act on a definition it misread. That is a safety net, not a substitute for the
order above: a skewed deploy still charges model calls whose write-back is then refused and
dead-lettered on the first attempt, can stamp groups clean and can trip the DLQ circuit breaker into
pausing the pipeline. Clearing that is manual — requeue with the pipeline **enabled**, or the stored
item is destroyed — and whatever a requeue cannot recover has to be re-derived at full model cost.

## Related documents

- [`kubernetes-exposure.md`](kubernetes-exposure.md) — the endpoint allowlist and a Kubernetes/Traefik
  walkthrough: port-forward, ingress, egress NetworkPolicy, verification.
- [`e2e-runbook.md`](e2e-runbook.md) — a minimal end-to-end local run and the variables it sets.
- `openspec/specs/<capability>/spec.md` — what each subsystem does with these values.
