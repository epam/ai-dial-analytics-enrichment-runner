## Context

Context: the enrichment path works end to end (`bootstrap-runner-service` through `add-spend-and-backfill`); this change makes it operable — metrics for
the runner's own signals, a readiness gate that reflects rule loading, introspection, and correlated
logging. Outbound HTTP spans are already produced by the Spring Boot OpenTelemetry auto-instrumentation.

## Goals / Non-Goals

**Goals:** Prometheus metrics (lag, queue depth, DLQ size, DIAL latency/outcomes, validation
failures, rows upserted); rule-sync readiness gate; rule-cache introspection; chunk-boundary MDC
logging; a local e2e runbook.

**Non-Goals:** custom OTel spans beyond auto-instrumentation; dashboards/alerts (deployment concern);
multi-replica coordination.

## Decisions

- **Micrometer gauges read live state at scrape time.** Rules-cached, DLQ-size and queue-depth gauges
  are backed by functions over `RuleCache`, the DLQ repo, and the scheduler — no background refresh.
  Per-rule scan lag registers a tagged gauge on first sight and updates a map the gauge reads.
- **Readiness via a health contributor, not availability state.** A `ruleSync` `HealthIndicator` in
  the readiness group is UP once `ReadinessState` records the first successful sync; the built-in
  `db` indicator covers PostgreSQL. This keeps the gate declarative and visible under
  `/actuator/health/readiness`.
- **Correlation via MDC + explicit boundary logs.** Rather than custom spans, the worker sets
  `rule_id`/`chunk_id` in the MDC around chunk processing and logs a boundary line; auto-instrumented
  HTTP spans link the DIAL/ADAS calls. Full payloads are never logged at INFO.
- **Introspection reuses the wire DTOs.** `GET /v1/rules/cache` maps the cached `RuleDto`s to a lean
  summary via a `web/mapper` component (no persistence coupling).

## Risks / Trade-offs

- **Queue-depth gauge scans scheduled executions each scrape** → O(queue) per scrape; acceptable at
  initial scale and only on scrape.
- **MDC visibility depends on the log layout** → the explicit boundary log carries the ids directly,
  so correlation is present regardless of layout; MDC additionally flows to OTel log export.
- **No custom spans** → accepted for now; auto-instrumentation covers the outbound calls that
  matter most.
