# Analytics Enrichment Runner

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-25-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.x-brightgreen)

A standalone Spring Boot / Java service that runs enrichments for DIAL
Analytics 2.0. It incrementally scans ADAS ([Analytics Data Access
Service](https://github.com/epam/ai-dial-analytics))
source tables, calls DIAL Core LLMs to derive per-row labels (topic / sentiment / language, …),
and writes the results back to ADAS-managed ClickHouse **enrichment** side-tables (joined onto their
source on a grain key at query time).

The runner holds **no definitions and no ClickHouse connection**. Pipeline definitions
live in the ADAS control-plane; the runner syncs enabled pipelines from ADAS and owns only its own
PostgreSQL runtime state (job queue, scan cursors, dead-letter items, spend counters). Source reads
and enrichment writes go through ADAS's pipeline-scoped endpoints; model calls go to DIAL Core.

## How it works

**Pipeline sync** polls ADAS `GET /v1/pipelines?kind=enrich&enabled=true`, caching those pipelines
and reconciling one recurring task per pipeline. A pipeline the runner cannot
execute is refused at sync — visibly absent rather than cached and failing every fire.

Each pipeline then runs in one of **three lanes**, chosen by its transform type and trigger kind:

- **Row × LLM** — scan pages from `POST /v1/pipelines/{name}/scan`, chunk them, and for each row render the
  transform's request template, call DIAL Core chat/completions with a JSON-schema `response_format`, validate and
  map the output. The write is a separate work item, so retrying a failed write never re-calls the model.
- **Group × LLM** — the same scan doubles as a *dirty-mark*: it records per-group state whose cost is
  proportional to new rows, not to the number of groups. A scheduled *readiness sweep* then enqueues only
  groups that are dirty, ready (quiet for their idle window, stale, or signalled complete by a member — the
  signalled branch fires with no time bound) and within their daily evaluation ceiling. One group is one
  model call over its assembled members and one enrichment row.
- **Row × SQL (Class-1)** — no model call at all. ADAS computes the derivation set-based inside ClickHouse
  via `POST /v1/pipelines/{name}/materialize`; the runner supplies only cadence, window and cursor bookkeeping,
  and no row payload crosses the wire in either direction.

**Correctness** is at-least-once execution with **exactly-once effect**: duplicate writes collapse on the
enrichment table's `ReplacingMergeTree` grain key read with `final=1`.

**Runs as a single active replica** — one replica, no autoscaler. A *brief* two-pod overlap is nonetheless
safe, because the work is serialized in PostgreSQL and not in the process: db-scheduler locks each execution
to one node, every enqueue is idempotent on a deterministic instance id, and a duplicated write is a no-op.
So a rolling update (`maxSurge: 1`, `maxUnavailable: 0`) is supported and costs no restart gap. What is
per-process — the DIAL rate limiter, the pipeline-pause registry, the DLQ breaker counters — is why N>1 is not a
running configuration.

## Inbound authentication

Callers are authenticated the way ADAS authenticates them: a **Bearer JWT** verified against a configured
identity provider's JWKS, an **opaque Bearer token** introspected against a provider's userinfo endpoint,
or a **DIAL `Api-Key`** introspected against DIAL Core. The token shape is chosen per request from the
credential itself. Every functional endpoint under `/v1` requires the `FULL_ADMIN` role, reads included —
the runner holds no per-target grants, so there is no narrower caller to authorize.

This is **off by default** (`SECURITY_MODE=none`) and a deployment must opt in with `SECURITY_MODE=oidc`.
Note that `ADAS_API_KEY` / `DIAL_API_KEY` are *outbound* credentials — what the runner presents to ADAS and
DIAL — and authenticate nothing inbound; with authentication off, any caller that can reach the port can
start a backfill or requeue (real model calls, real spend) acting through the runner's own identity. The
startup log states the posture in effect, at WARN when it is open.

Health probes and the Prometheus scrape endpoint stay reachable without a credential in every mode, so
enabling authentication never affects probe wiring. The springdoc paths (Swagger UI and OpenAPI JSON) need
`FULL_ADMIN` in `oidc` mode unless `DISABLE_SWAGGER_AUTHORIZATION=true`; in `none` mode everything is open. See
[`docs/configuration.md`](docs/configuration.md) for the variables and the probe table.

**Exposure.** The runner publishes no consumer-facing read API — every `/v1` endpoint is an operator control
and several of them spend money — so the default posture is to keep the Service cluster-internal and reach
it by `kubectl port-forward` or from an in-cluster caller. When an outside caller genuinely needs it, the
endpoint-by-endpoint allowlist and the Kubernetes/Traefik walkthrough (ingress, egress NetworkPolicy,
verification) are in [`docs/kubernetes-exposure.md`](docs/kubernetes-exposure.md).

## Sensitive-data posture

Distinct from the above, and about the runner's *outbound* identity. The runner requires an ADAS identity
**entitled to read `sensitive` columns** (`FULL_ADMIN`) for any pipeline that references one. ADAS re-checks
the entitlement at scan, group-assembly and materialize egress and **rejects rather than strips**, so a
least-privilege runner receives an error rather than a redacted row — there is no degraded mode to operate in.

What that widens is narrower than it sounds. The entitlement widens *which columns a pipeline may
reference*, not *which pipelines the runner may operate*: the scan projects only pipeline-referenced
columns, group assembly returns only the pipeline's membership, and write-back rejects any column outside
the pipeline's declared outputs plus permitted provenance. A narrower per-pipeline or per-service-identity
grant is the named follow-up.

Two consequences worth knowing:

- ADAS's check **fails open** when ADAS security is disabled, which is the local-development default. Local
  runs therefore cannot surface a misconfigured production identity; the runner says so at startup.
- Prefer a **Class-1 (`type=sql`) pipeline** wherever the derivation is expressible in SQL. Bodies then stay
  inside ClickHouse entirely, which is both cheaper and a smaller exposure surface.

Source content is never logged at any level, a group work item carries identifying keys only, and derived
content persists in exactly one place — a dead-letter item's raw response, under the DLQ retention window.

## Tech stack

Java 25 · Spring Boot 4 (Web MVC) · Jackson 3 · Log4j2 · Spring Data JPA + Flyway (PostgreSQL) ·
db-scheduler (Postgres-backed job queue) · Actuator + Micrometer Prometheus + OpenTelemetry ·
Gradle (`-Werror`, Google Java Style checkstyle at 180 chars).

## Prerequisites

- JDK 25
- Docker — **required to run the test suite**: the integration tests start a real PostgreSQL via
  [Testcontainers](https://testcontainers.com/), so `./gradlew clean build` and `./gradlew test` need a
  running Docker daemon. Also used for containerized runs.
- To actually enrich anything: a PostgreSQL database for the runner's own state, a reachable
  [ADAS](https://github.com/epam/ai-dial-analytics) instance with enrichment pipelines defined, and
  [DIAL Core](https://github.com/epam/ai-dial-core) for the LLM lanes.

## Build & run

```bash
./gradlew clean build                       # compile + checkstyle + tests (needs Docker for Testcontainers)
HTTP_PORT=5100 ./gradlew bootRun            # run locally on http://localhost:5100
./gradlew checkstyleMain checkstyleTest     # code style only
./gradlew test                              # tests only
```

`HTTP_PORT` defaults to **5000** (the container port the `dial-extension` Helm chart expects); local runs override it
because a local ADAS or DIAL Core already holds 5000.

Smoke test a running instance:

```bash
curl -s localhost:5100/actuator/health/liveness   # {"status":"UP"} — process is up
curl -s localhost:5100/actuator/health/readiness  # UP only once a pipeline sync has succeeded
```

Use the **liveness** group for a smoke test, not the aggregate `/actuator/health`. The aggregate includes the
`db` and `pipelineSync` contributors, so it reports `DOWN` with HTTP 503 until the first ADAS pipeline sync succeeds —
which on a fresh instance means it looks broken while it is merely still warming up. The same distinction
governs probe wiring: see the probe table in
[`docs/configuration.md`](docs/configuration.md#health-probes).

## Docker

```bash
docker build -t analytics-enrichment-runner .
docker run --rm -p 5000:5000 \
  -e POSTGRES_HOST=<postgres-host> \
  -e ADAS_BASE_URL=http://<adas-host>:5000 -e ADAS_API_KEY=<key> \
  -e DIAL_BASE_URL=http://<dial-core-host>:8080 -e DIAL_API_KEY=<key> \
  analytics-enrichment-runner
```

The defaults point at `localhost`, which inside the container is the container itself; set the
PostgreSQL, ADAS and DIAL Core variables (see [`docs/configuration.md`](docs/configuration.md)) to
reachable hosts. Inbound authentication is off by default (`SECURITY_MODE=none`), which is for local
development only — set `SECURITY_MODE=oidc` for any shared deployment (see
[Inbound authentication](#inbound-authentication)).

## Configuration

Configuration is environment-driven (`${ENV_VAR:default}` in `application.yml`). Authoritative
descriptions of every variable live in [`docs/configuration.md`](docs/configuration.md).

## Development workflow

This project uses [OpenSpec](https://github.com/Fission-AI/OpenSpec) for spec-driven development
(mirroring ADAS). Specs live under `openspec/specs/<capability>/`; in-flight work under
`openspec/changes/<change>/`. See [`CLAUDE.md`](CLAUDE.md) and `openspec/config.yaml`.

## Release notes

Operator upgrade guides for releases are in `docs/release-<version>-upgrade.md`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Report security issues as described in
[SECURITY.md](SECURITY.md), not as public issues.

## License

Copyright 2026 EPAM Systems, Inc.

Licensed under the [Apache License, Version 2.0](LICENSE).
