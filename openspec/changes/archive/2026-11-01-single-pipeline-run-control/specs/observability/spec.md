## MODIFIED Requirements

### Requirement: Runner gauges are cheap and bounded

The runner's own gauges SHALL NOT perform unbounded work on the scrape path: queue depth SHALL be a `COUNT`
over the scheduler table (not a deserialization of every pending payload), and any gauge whose value requires
a query over a growing table — DLQ size, active-group count, dirty backlog — SHALL be refreshed off the scrape
path, so scrape latency never becomes a function of table size.

Gauge **cardinality** SHALL stay bounded: per-pipeline gauges SHALL be evicted when a pipeline leaves the cache or
is stopped, and registered again when it is next active — a stopped pipeline's lag would otherwise read as a stall
nothing will move — and no gauge SHALL be tagged by an unbounded identifier. A group key is unbounded, so
group-level detail SHALL be exposed as aggregates tagged by pipeline, with the per-group view left to spans, the MDC
and the pull-based introspection endpoint.

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

#### Scenario: A stopped pipeline's per-pipeline gauges are evicted

- **GIVEN** an active pipeline with a scan-lag series
- **WHEN** a sync caches it as stopped and the Prometheus endpoint is scraped
- **THEN** no per-pipeline series carries its name, and the series returns once it is active and scanning again

### Requirement: Pipeline identity is spelled the same way in every signal

The runner SHALL identify a pipeline by its **name**, under one spelling per signal type, so a metric series,
a log query and a trace filter can be written from the same value without translation:

- **Gauges** naming the synced set SHALL be `runner.pipelines.cached`, `runner.pipelines.stopped`,
  `runner.pipelines.paused` and `runner.pipelines.unsupported`.
- **Per-pipeline series** SHALL carry the tag key `pipeline` — on `runner.spend.calls.today`,
  `runner.scan.lag.seconds`, `runner.class1.rows.written`, `runner.scan.rows.skipped`,
  `runner.scan.pages.refused` and `runner.backfill.batch.failures`.
- The **MDC** key SHALL be `pipeline_name`. It SHALL be present on every record logged while a pipeline-scoped
  execution runs, including its failure processing, and it SHALL be exported under the same name as an OTLP
  log-record attribute.
- The **span attribute** SHALL be `pipeline.name`, set on every span the runner opens that is scoped to one
  pipeline: the scan tick, the dirty-mark, the materialize, the group sweep, the group assembly, the DIAL
  call and the upsert.

A series that is **not** scoped to one pipeline SHALL carry no `pipeline` tag rather than a placeholder
value: `runner.adas.contract.compatible` and `runner.sync.empty.feeds` describe the runner's relationship
with ADAS, which no pipeline owns, and `runner.pipeline.state.resets` describes the registry.

Span **names** are unaffected and SHALL keep their existing spellings (`enrichment.scan-tick`,
`enrichment.dirty-mark`, `enrichment.materialize`, `enrichment.group-sweep`, `enrichment.group-assembly`,
`enrichment.dial-call`, `enrichment.upsert`) — they name an activity, not an identity.

These spellings are a contract with dashboards, alerts and log queries rather than an internal detail: a
renamed series does not fail, it goes **blank**, so the signal an operator watches disappears silently. That
is why the set is enumerated here rather than left to follow from the code. ADAS uses the same `pipeline_name`
MDC key, so one attribute filter selects a pipeline's records from both services.

#### Scenario: The synced-set gauges carry the pipeline spelling

- **WHEN** the exposed metric set is inspected
- **THEN** it contains `runner.pipelines.cached`, `runner.pipelines.stopped`, `runner.pipelines.paused` and
  `runner.pipelines.unsupported`, and no `runner.rules.*` series

#### Scenario: A per-pipeline series is tagged by pipeline

- **GIVEN** a pipeline that has recorded scan lag and spend
- **WHEN** its series are inspected
- **THEN** each carries a `pipeline` tag holding the pipeline's name, and no `rule` tag

#### Scenario: A work item's log records and span agree on the identity

- **WHEN** a work item executes for a pipeline
- **THEN** its log records carry `pipeline_name` in the MDC and its span carries `pipeline.name`, both holding
  the same value

#### Scenario: A runner-scoped series carries no pipeline tag

- **WHEN** `runner.adas.contract.compatible` is inspected
- **THEN** it carries no `pipeline` tag

#### Scenario: A failure record keeps the identity

- **WHEN** a pipeline-scoped execution fails and its failure is logged by the scheduler and by the failure
  handler
- **THEN** each of those records carries `pipeline_name`

### Requirement: An auto-pause is alertable on its own

The runner SHALL expose the number of pipelines paused **by the circuit breaker** separately from the
total number paused.

Every pause the runner holds is now the breaker's — an operator stops a pipeline in ADAS, and the runner counts that
in `runner.pipelines.stopped` — so the two series read the same. Both SHALL remain, because alert rules and
dashboards are written against each, and a stopped pipeline SHALL be counted by neither: a stop is a legitimate
steady state, so a rule written against a series that included it would be either permanently lit or permanently
ignored. A breaker-origin pause has no legitimate steady state: it is by construction a temporary inference about an
upstream, so "one of these has persisted" is exactly the alert condition, and the separate series is what makes it
expressible.

Both series SHALL be untagged counts. The condition is about the runner's state, not about any one
pipeline's identity, and the listing is where a specific pipeline is named.

#### Scenario: A stopped pipeline does not light the auto-pause alarm

- **GIVEN** a pipeline disabled in ADAS and cached as stopped
- **WHEN** the metrics are scraped
- **THEN** the stopped gauge counts it, and neither the total paused gauge nor the auto-paused gauge does

#### Scenario: A resumed auto-pause clears its series

- **GIVEN** an auto-paused pipeline whose pause window has elapsed
- **WHEN** the metrics are scraped after it resumes
- **THEN** the auto-paused gauge reads zero

### Requirement: A pipeline state reset is logged and counted

When the sync resets a pipeline recreated under the same name, the runner SHALL log one WARN line naming the pipeline,
the `created_at` it replaced and the new one, how many group rows and queued work items it deleted and how many
backfill runs it cancelled, and whether a breaker pause was lifted; and SHALL count the reset in
`runner.pipeline.state.resets`. The counter SHALL be untagged: a reset describes the registry rather than one
pipeline's activity, the line names the pipeline, and a per-pipeline series would be evicted with the pipeline it
counts. The line is the runner's, not an operator action: it SHALL NOT be written through the operator-action log.

#### Scenario: A reset is logged and counted

- **WHEN** the sync resets a recreated pipeline
- **THEN** one WARN line names the pipeline, both `created_at` values and what was removed, and
  `runner.pipeline.state.resets` increases by one

### Requirement: Each pipeline reports its status periodically

The runner SHALL log one status line per synced pipeline every `observability.status-report.interval`
(`STATUS_REPORT_INTERVAL`, default `5m`). Each line SHALL carry:
- the lane and the generation;
- the state: stopped, active, paused with its origin, over budget, backpressured, or held;
- how long ago it last wrote, and the rows written and items dead-lettered since the previous line;
- the lag, how long ago it last scanned, and when it next fires, including whether that fire is overdue.

The runner SHALL additionally log one runner-wide line carrying:
- the sync age and ADAS compatibility;
- queue depth per task, overdue executions and the longest running execution;
- the DLQ size, and backfills running, paused and orphaned.

Each line SHALL be a single record of `|`-separated sections in plain words, meant to be read rather than parsed.
Instants SHALL be given relative to the report (`3m ago`, `in 4m`). A count of zero in the queue depth or the
backfill counts MAY be left out, with `empty` or `none` standing in when every count is zero.

A backfill run is orphaned when it reads running but has no `backfill-run` execution, so nothing will advance it.
The runner SHALL log one WARN per orphaned run on every report, naming the run, its pipeline and the remedy (pause
and resume), because the condition persists until an operator acts. No read endpoint SHALL log it.

A stopped pipeline's line SHALL be logged at INFO and SHALL NOT be reported as stalled: a stop is a decision taken
in ADAS rather than a condition, and a stopped pipeline writes nothing by design. A pipeline's line SHALL be logged
at WARN when the pipeline is paused. It SHALL also be logged at WARN when the
pipeline has written nothing for more than its stall threshold while lag is above zero. The stall threshold is
three times its cadence; for a group pipeline it SHALL be the longer of that and its effective `ready_when.idle`
(the declared window, else `GROUP_DEFAULT_IDLE`) plus `GROUP_SWEEP_INTERVAL`, because a group pipeline's cadence is
its mark scan's and a dirty group writes nothing until it has been quiet for the idle window and a sweep has run.
`max_staleness` and a signal only make a group ready sooner, so they do not lengthen the threshold. After a
restart, that second condition SHALL NOT be reported until the threshold has passed, because write activity is
held in process memory. Otherwise the line SHALL be logged at INFO. A value the runner cannot know SHALL be logged as
`unknown` rather than estimated.

Stopping is the failure a log-only operator cannot otherwise see: a pipeline that stops writing emits nothing.

#### Scenario: An orphaned backfill is reported by the status report

- **GIVEN** a running backfill run whose `backfill-run` execution is gone
- **WHEN** the status report runs twice
- **THEN** each report names the run and its pipeline at WARN, and the runner line counts it as orphaned
- **AND** listing backfills logs nothing about it

#### Scenario: A stalled pipeline warns

- **GIVEN** a row-grain pipeline with lag above zero and no write for more than three times its cadence
- **WHEN** the status report runs
- **THEN** its line is logged at WARN with how long ago it last wrote and its lag

#### Scenario: A group pipeline is not stalled within its idle window

- **GIVEN** a group pipeline with a one-minute mark-scan cadence, a ten-minute effective idle window and a
  thirty-second sweep interval, lag above zero, and its last write five minutes ago
- **WHEN** the status report runs
- **THEN** its line is not a WARN for missing writes, because its stall threshold is ten minutes thirty seconds,
  not three minutes

#### Scenario: A healthy pipeline reports at INFO

- **GIVEN** a pipeline writing within its cadence
- **WHEN** the status report runs
- **THEN** its line is logged at INFO with rows written since the previous line

#### Scenario: A stopped pipeline reports at INFO

- **GIVEN** a pipeline cached as stopped, with lag above zero and no write for longer than its stall threshold
- **WHEN** the status report runs
- **THEN** its line is logged at INFO with the state `stopped`, and is not a WARN for missing writes

#### Scenario: A fresh start does not cry stall

- **GIVEN** a runner started less than its stall threshold ago with a lagging pipeline
- **WHEN** the status report runs
- **THEN** that pipeline's line is not a WARN for missing writes, and its last write is `unknown`
