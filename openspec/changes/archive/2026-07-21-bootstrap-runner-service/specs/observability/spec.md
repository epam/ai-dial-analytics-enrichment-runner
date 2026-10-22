## ADDED Requirements

### Requirement: Health endpoint for liveness and readiness

The service SHALL expose an Actuator health endpoint at `/actuator/health` that reports `UP` when
the application context and its runtime-state datasource are healthy. The container image SHALL use
this endpoint for its health check.

#### Scenario: Health reports UP when dependencies are healthy

- **WHEN** the application is running with a reachable PostgreSQL datasource
- **THEN** `GET /actuator/health` returns status `UP`

### Requirement: Prometheus metrics endpoint

The service SHALL expose a Prometheus scrape endpoint at `/actuator/prometheus` backed by a
Micrometer Prometheus registry.

#### Scenario: Prometheus endpoint is scrapeable

- **WHEN** `GET /actuator/prometheus` is requested
- **THEN** the response is Prometheus text-format metrics

### Requirement: OpenTelemetry OTLP export

The service SHALL support exporting traces, logs, and metrics over OTLP, disabled by default and
enabled via environment configuration (`OTEL_EXPORT_ENABLED` and per-signal overrides). Enabling
export SHALL NOT disable the Prometheus scrape endpoint.

#### Scenario: OTLP export is off by default

- **WHEN** the application starts with no OTLP configuration
- **THEN** no OTLP exporter is active and the Prometheus endpoint remains available

#### Scenario: OTLP export can be enabled

- **WHEN** `OTEL_EXPORT_ENABLED=true` and an OTLP endpoint are configured
- **THEN** traces, logs, and metrics are exported over OTLP to that endpoint
