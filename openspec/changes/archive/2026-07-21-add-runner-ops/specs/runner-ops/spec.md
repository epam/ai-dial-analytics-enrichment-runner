## ADDED Requirements

### Requirement: Runner metrics exposed via Prometheus

The runner SHALL expose its own operational metrics on the Prometheus scrape endpoint: gauges for
rules cached, DLQ size, enrich-chunk queue depth, and per-rule scan lag; a timer for DIAL call
latency with success and error counters; and counters for response-validation failures and
enrichment rows upserted.

#### Scenario: Runner gauges are scrapeable

- **WHEN** `GET /actuator/prometheus` is requested
- **THEN** the response includes the runner's own metrics (e.g. `runner_rules_cached`)

### Requirement: Readiness gates on a successful rule sync

The runner's readiness SHALL be UP only after at least one successful ADAS rule sync has completed
(in addition to PostgreSQL reachability); liveness SHALL reflect process health independently. The
readiness contributor SHALL report DOWN until the first successful sync.

#### Scenario: Readiness turns UP after the first sync

- **GIVEN** the runner has completed a successful rule sync
- **WHEN** `GET /actuator/health/readiness` is requested
- **THEN** the status is UP

### Requirement: Rule-cache introspection

The runner SHALL expose a read-only endpoint listing the rules it currently has cached, with each
rule's id, name, evaluator version, trigger kind, enabled flag, and generation.

#### Scenario: Cached rules are listed

- **GIVEN** a rule is present in the runner's cache
- **WHEN** `GET /v1/rules/cache` is requested
- **THEN** the response lists that rule with its id, trigger kind, and generation

### Requirement: Structured chunk-boundary logging

The runner SHALL log at chunk-processing boundaries with correlation context (rule id and chunk id
in the MDC), and SHALL NOT log full request/response payloads at INFO.

#### Scenario: A chunk logs a correlated boundary line

- **WHEN** the worker begins processing a chunk
- **THEN** it logs a boundary line carrying the rule id and chunk id, without full payloads
