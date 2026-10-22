# DIAL Analytics Enrichment Runner

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-25-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.x-brightgreen)

A service that adds computed columns to [DIAL Analytics](https://github.com/epam/ai-dial-analytics) data.
It reads new rows from analytics tables through ADAS (Analytics Data Access Service), computes values for
them, and writes the results to enrichment tables that join back to the source rows.

Values can be computed in three ways:

- **Per row with an LLM**: for example, the topic, sentiment or language of a message
- **Per group of rows with an LLM**: for example, a summary of a whole chat session
- **Per row with SQL**: no LLM call; ADAS computes the value inside ClickHouse

Each enrichment is configured in ADAS as a *pipeline*: what to read, how to compute it, and where to write
the result. The runner picks up pipelines automatically, processes only new data, and calls
[DIAL Core](https://github.com/epam/ai-dial-core) for the LLM steps. It keeps its own state in PostgreSQL.

## Table of Contents

- [Features](#features)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [Security](#security)
- [Documentation](#documentation)
- [Spec-Driven Development](#spec-driven-development)
- [Contributing](#contributing)
- [License](#license)

## Features

- **Pipeline sync**: Picks up enabled enrichment pipelines from ADAS and schedules one recurring task
  for each.
- **Incremental scans**: Cursor-based scanning that processes only new rows, plus bounded backfills on
  demand.
- **Exactly-once effect**: Execution is at-least-once and duplicate writes collapse in the enrichment
  table, so retries are safe.
- **Resilience**: Retries, a dead-letter queue with requeue, and a circuit breaker that pauses a failing
  pipeline.
- **Spend tracking**: Daily call and token counters for each pipeline, with an optional budget that
  pauses the pipeline when it runs out.
- **Authentication**: OIDC JWT, opaque tokens, or DIAL API keys (off by default).
- **Observability**: Health probes, Prometheus metrics, OpenTelemetry traces and logs, and log levels you
  can change at runtime.

## Prerequisites

- JDK 25
- Docker, needed for the test suite (Testcontainers starts PostgreSQL) and for containerized runs
- To actually enrich data:
  - a PostgreSQL database for the runner's own state
  - a reachable [ADAS](https://github.com/epam/ai-dial-analytics) instance with enrichment pipelines defined
  - [DIAL Core](https://github.com/epam/ai-dial-core) for the LLM lanes

## Getting Started

### Build

```bash
./gradlew clean build      # compile, checkstyle and tests (needs Docker)
```

### Run with Gradle

```bash
HTTP_PORT=5100 ./gradlew bootRun
```

`HTTP_PORT` defaults to `5000`. Local runs move it because a local ADAS or DIAL Core usually holds that
port already.

### Run with Docker

```bash
docker build -t analytics-enrichment-runner .
docker run --rm -p 5000:5000 \
  -e POSTGRES_HOST=<postgres-host> \
  -e ADAS_BASE_URL=http://<adas-host>:5000 -e ADAS_API_KEY=<key> \
  -e DIAL_BASE_URL=http://<dial-core-host>:8080 -e DIAL_API_KEY=<key> \
  analytics-enrichment-runner
```

### Verify

```bash
curl -s localhost:5100/actuator/health/liveness   # {"status":"UP"}
```

Use the `liveness` group for a smoke test. The aggregate `/actuator/health` reports `DOWN` until the
first pipeline sync with ADAS succeeds.

For a full local walkthrough against a real ADAS, see the [end-to-end runbook](docs/e2e-runbook.md).

## Configuration

Configuration comes from environment variables. Every variable has a default for local development,
and the full reference is in [docs/configuration.md](docs/configuration.md).

### Key Configuration Areas

- **PostgreSQL**: The runner's own state (`POSTGRES_HOST`, `POSTGRES_DATABASE`, ...)
- **ADAS**: Connection to the control plane and data plane (`ADAS_BASE_URL`, `ADAS_API_KEY`)
- **DIAL Core**: Model calls for the LLM lanes (`DIAL_BASE_URL`, `DIAL_API_KEY`)
- **Pipeline execution**: Scan, worker, group, spend and dead-letter queue tuning
- **Security**: Inbound authentication mode and identity providers (`SECURITY_MODE`)
- **Observability**: Logging, health probes and OpenTelemetry export

## Security

**Inbound authentication is off by default (`SECURITY_MODE=none`).** In that mode, anyone who can reach
the port can start a backfill or requeue work, which makes real model calls that cost money. For any
shared deployment:

- Set `SECURITY_MODE=oidc` and configure at least one identity provider. Every `/v1` endpoint then
  requires the `FULL_ADMIN` role.
- Keep the service internal to the cluster. If outside callers need access, see
  [docs/kubernetes-exposure.md](docs/kubernetes-exposure.md).

Health probes and the Prometheus endpoint stay public in every mode. See
[Security](docs/configuration.md#security) for details. To report a vulnerability, follow
[SECURITY.md](SECURITY.md).

## Documentation

| Document                                              | What it covers                                           |
|-------------------------------------------------------|----------------------------------------------------------|
| [Configuration](docs/configuration.md)                | Every variable, health probes, and release order         |
| [End-to-end runbook](docs/e2e-runbook.md)             | Running all three lanes locally against ADAS             |
| [Kubernetes exposure](docs/kubernetes-exposure.md)    | Endpoint allowlist, ingress and network policy           |
| Upgrade guides (`docs/release-<version>-upgrade.md`)  | Operator steps for each release                          |
| [Specs](openspec/specs)                               | Detailed behavior of each capability                     |

## Spec-Driven Development

This project uses [OpenSpec](https://github.com/Fission-AI/OpenSpec). Shipped behavior is specified under
`openspec/specs/<capability>/`, and in-progress work lives under `openspec/changes/<change>/`.

| Command                   | Purpose                                                       |
|---------------------------|---------------------------------------------------------------|
| `/opsx:propose "<idea>"`  | Create a change with proposal, design, specs and tasks        |
| `/opsx:apply`             | Implement the tasks of the current change                     |
| `/opsx:archive`           | Merge delta specs into `openspec/specs/` and archive the change |

Project conventions are in `openspec/config.yaml`, and agent guidance is in [CLAUDE.md](CLAUDE.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Copyright 2026 EPAM Systems, Inc.

Licensed under the [Apache License, Version 2.0](LICENSE).
