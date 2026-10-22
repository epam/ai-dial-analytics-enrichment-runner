## RENAMED Requirements

- FROM: `### Requirement: Class-1 throughput is attributable per rule`
- TO: `### Requirement: Class-1 throughput is attributable per pipeline`

## MODIFIED Requirements

### Requirement: Health endpoint for liveness and readiness

The service SHALL expose an Actuator health endpoint at `/actuator/health` with `/actuator/health/liveness`
and `/actuator/health/readiness` as its probe groups, and the three SHALL NOT be interchangeable:

- **Liveness** SHALL reflect process state alone, so no external dependency can report it DOWN. This is the
  only one of the three safe to restart a container on.
- **Readiness** SHALL additionally require PostgreSQL reachability and at least one successful ADAS pipeline
  sync — reasons to withhold traffic, not reasons to restart.
- The **aggregate** endpoint SHALL report the union of every contributor, readiness's included. It therefore
  reports DOWN until the first pipeline sync succeeds, which makes it unsuitable for a liveness probe: a
  dependency that has not warmed up yet is indistinguishable from a dead process.

The container image SHALL health-check the **liveness** probe. Documentation and smoke-test instructions
SHALL NOT present the aggregate endpoint as reporting `UP` on a freshly started instance, because it does
not, and following that instruction produces a restart loop.

The pipeline-sync gate SHALL be a one-way latch: once the first sync succeeds it never reports DOWN again, so a
later sync failure or an upstream ADAS restart cannot restart the container or withdraw it from service.

#### Scenario: Liveness is UP as soon as the process is running

- **WHEN** the application is running and no pipeline sync has completed
- **THEN** `GET /actuator/health/liveness` returns status `UP`

#### Scenario: The aggregate endpoint is DOWN before the first pipeline sync

- **GIVEN** a reachable PostgreSQL datasource and no completed pipeline sync
- **WHEN** `GET /actuator/health` is requested
- **THEN** the status is `DOWN` with the `pipelineSync` contributor reporting `DOWN`

#### Scenario: Health reports UP once every dependency is satisfied

- **GIVEN** a reachable PostgreSQL datasource and a completed successful pipeline sync
- **WHEN** `GET /actuator/health` is requested
- **THEN** the status is `UP`

#### Scenario: A later sync failure does not withdraw the instance

- **GIVEN** the first pipeline sync has already succeeded
- **WHEN** a subsequent sync fails or ADAS restarts
- **THEN** liveness and readiness both continue to report `UP`

### Requirement: Runner gauges are cheap and bounded

The runner's own gauges SHALL NOT perform unbounded work on the scrape path: queue depth SHALL be a `COUNT`
over the scheduler table (not a deserialization of every pending payload), and any gauge whose value requires
a query over a growing table — DLQ size, active-group count, dirty backlog — SHALL be refreshed off the scrape
path, so scrape latency never becomes a function of table size.

Gauge **cardinality** SHALL stay bounded: per-pipeline gauges SHALL be evicted when a pipeline leaves the cache, and
no gauge SHALL be tagged by an unbounded identifier. A group key is unbounded, so group-level detail SHALL be
exposed as aggregates tagged by pipeline, with the per-group view left to spans, the MDC and the pull-based
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
- **THEN** group-level signals appear as per-pipeline aggregates and no series is tagged by an individual group key

### Requirement: Lag reports being caught up as a value

A pipeline's scan-lag gauge SHALL report `0` once its lane has nothing left to read, and SHALL NOT hold the last
non-empty page's value while the pipeline is caught up. ADAS omits `lag_seconds` for an empty page or window
because there is no page end version to subtract from now; the runner SHALL treat that absence **on an empty
page** as zero lag rather than as a missing measurement, so the gauge decays as a pipeline drains its backlog.

An absent `lag_seconds` on a page that **did** carry rows SHALL be treated as unknown and SHALL leave both the
persisted `pipeline_cursor.lag_seconds` and the exported gauge at their last recorded value — no lane may invent a
zero it was not told. The value the lane records and the value it persists SHALL be the same one, so the column
and the gauge can never disagree about the same page.

This applies to every cursor-driven lane (row scan, group dirty-mark, Class-1 materialize). A backfill SHALL
NOT record lag at all: a historical window's distance from now is not the pipeline's live lag, and recording it
would overwrite the signal that is.

#### Scenario: A caught-up pipeline's lag decays to zero

- **GIVEN** a pipeline whose gauge reports a large lag from a backlog it has since drained
- **WHEN** its next page or window comes back empty, with `lag_seconds` absent
- **THEN** the gauge reports `0` and `pipeline_cursor.lag_seconds` is `0`

#### Scenario: An unknown lag does not overwrite the last known one

- **WHEN** a page carries rows but its response omits `lag_seconds`
- **THEN** the exported gauge and the persisted column both keep the value they already held

#### Scenario: A backfill does not move the pipeline's live lag

- **GIVEN** a bounded backfill replaying a historical window for a pipeline
- **WHEN** its batches complete
- **THEN** the pipeline's scan-lag gauge is unchanged by the run

### Requirement: A silently halted drain is counted

The runner SHALL count a drain halted because the cursor did not advance while the response still reported
backlog, as `runner.cursor.stalls` tagged by lane (`scan`, `class1`, `backfill`). The halt itself raises no
error — it is the correct response to a non-advancing cursor — so no tick-failure or dead-letter signal moves
and the pipeline merely stops making progress. The counter SHALL be registered for every lane at startup, so the
series exists before the first stall rather than appearing with it.

#### Scenario: A non-advancing cursor increments its lane's counter

- **GIVEN** a pipeline whose response reports `has_more` with an absent or unchanged `next_cursor`
- **WHEN** its drain halts
- **THEN** `runner.cursor.stalls` for that lane increases and no tick-failure counter moves

#### Scenario: Every lane's stall series exists before any stall

- **WHEN** the exposed metric set is inspected on a runner that has never stalled
- **THEN** `runner.cursor.stalls` is present for each of `scan`, `class1` and `backfill`

### Requirement: Class-1 throughput is attributable per pipeline

The rows a Class-1 materialize window writes SHALL be counted per pipeline (`runner.class1.rows.written` tagged by
pipeline name), because the lane enqueues nothing and dead-letters nothing — so this is its only throughput signal,
and a single summed total cannot say which pipeline stopped writing. The per-pipeline series SHALL be evicted with the
pipeline, like the other per-pipeline meters, so tag cardinality stays bounded; the lane total remains available as a
sum over the series.

#### Scenario: Two Class-1 pipelines are told apart

- **WHEN** two Class-1 pipelines each materialize a window
- **THEN** each pipeline's written-row count is readable on its own series

#### Scenario: A removed pipeline's counter is evicted

- **WHEN** a pipeline leaves the synced set
- **THEN** its `runner.class1.rows.written` series is removed from the registry

## ADDED Requirements

### Requirement: Pipeline identity is spelled the same way in every signal

The runner SHALL identify a pipeline by its **name**, under one spelling per signal type, so a metric series,
a log query and a trace filter can be written from the same value without translation:

- **Gauges** naming the synced set SHALL be `runner.pipelines.cached`, `runner.pipelines.paused` and
  `runner.pipelines.unsupported`.
- **Per-pipeline series** SHALL carry the tag key `pipeline` — on `runner.spend.calls.today`,
  `runner.scan.lag.seconds` and `runner.class1.rows.written`.
- The **MDC** key SHALL be `pipeline_name`.
- The **span attribute** SHALL be `pipeline.name`, set on every span the runner opens that is scoped to one
  pipeline: the scan tick, the dirty-mark, the materialize, the group sweep, the group assembly, the DIAL
  call and the upsert.

Span **names** are unaffected and SHALL keep their existing spellings (`enrichment.scan-tick`,
`enrichment.dirty-mark`, `enrichment.materialize`, `enrichment.group-sweep`, `enrichment.group-assembly`,
`enrichment.dial-call`, `enrichment.upsert`) — they name an activity, not an identity.

These spellings are a contract with dashboards, alerts and log queries rather than an internal detail: a
renamed series does not fail, it goes **blank**, so the signal an operator watches disappears silently. That
is why the set is enumerated here rather than left to follow from the code.

#### Scenario: The synced-set gauges carry the pipeline spelling

- **WHEN** the exposed metric set is inspected
- **THEN** it contains `runner.pipelines.cached`, `runner.pipelines.paused` and `runner.pipelines.unsupported`,
  and no `runner.rules.*` series

#### Scenario: A per-pipeline series is tagged by pipeline

- **GIVEN** a pipeline that has recorded scan lag and spend
- **WHEN** its series are inspected
- **THEN** each carries a `pipeline` tag holding the pipeline's name, and no `rule` tag

#### Scenario: A work item's log records and span agree on the identity

- **WHEN** a work item executes for a pipeline
- **THEN** its log records carry `pipeline_name` in the MDC and its span carries `pipeline.name`, both holding
  the same value
