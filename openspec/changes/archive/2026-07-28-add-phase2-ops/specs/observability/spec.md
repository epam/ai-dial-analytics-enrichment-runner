## MODIFIED Requirements

### Requirement: Runner gauges are cheap and bounded

The runner's own gauges SHALL NOT perform unbounded work on the scrape path: queue depth SHALL be a `COUNT`
over the scheduler table (not a deserialization of every pending payload), and any gauge whose value requires
a query over a growing table — DLQ size, active-group count, dirty backlog — SHALL be refreshed off the scrape
path, so scrape latency never becomes a function of table size.

Gauge **cardinality** SHALL stay bounded: per-rule gauges SHALL be evicted when a rule leaves the cache, and
no gauge SHALL be tagged by an unbounded identifier. A group key is unbounded, so group-level detail SHALL be
exposed as aggregates tagged by rule, with the per-group view left to spans, the MDC and the pull-based
introspection endpoint.

#### Scenario: A scrape does not deserialize the queue

- **WHEN** the Prometheus endpoint is scraped while enrich-chunks are pending
- **THEN** queue depth is computed by a count query without deserializing chunk payloads

#### Scenario: Group-state gauges are refreshed off the scrape path

- **WHEN** the Prometheus endpoint is scraped
- **THEN** the active-group and dirty-backlog gauges report values gathered by a scheduled refresh rather
  than by querying during the scrape

#### Scenario: No gauge is tagged by a group key

- **WHEN** the exposed metric set is inspected
- **THEN** group-level signals appear as per-rule aggregates and no series is tagged by an individual group key
