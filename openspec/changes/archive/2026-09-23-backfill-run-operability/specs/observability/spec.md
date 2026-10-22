## ADDED Requirements

### Requirement: A backfill batch failure is counted per pipeline

The runner SHALL count backfill batch failures per pipeline, on a series of their own, and SHALL evict that
series with the pipeline like every other per-pipeline meter.

The backfill batch retries indefinitely by design, so a batch that can never succeed keeps re-issuing while
the run reads `running` and `rows_done` is frozen; before this series existed, no counter moved while it
did. The refused-page counter covers only two of the five
ways a batch fails and says nothing about an ADAS rejection or a serialization failure; the cursor-stall
counter covers a different condition that already pauses the run; and the Class-1 lane's tick-failure
counter has no backfill equivalent.

The series SHALL be tagged by **pipeline** and not by run. A run id is unbounded cardinality, and the run
that failed is named on the run row, which is where an operator reads the cause. The counter's job is the
aggregate one: how often a backfill batch fails, which is the measurement that has to exist before any
auto-pause threshold can be chosen with evidence rather than guessed.

A **held** batch — one meeting an incompatible ADAS or a pipeline cache that has not yet synced — SHALL NOT
be counted, for the same reason it consumes no attempt: it is a condition being waited out, not a failure,
and counting it would corrupt the measurement.

#### Scenario: A failing backfill batch is countable

- **GIVEN** a backfill batch that fails
- **WHEN** the metrics are scraped
- **THEN** a batch-failure count for that pipeline has increased

#### Scenario: Two pipelines' backfill failures are told apart

- **WHEN** backfills for two pipelines each fail a batch
- **THEN** each pipeline's failures are readable on its own series

#### Scenario: A held batch does not register as a failure

- **GIVEN** a backfill batch held because ADAS is incompatible
- **WHEN** the metrics are scraped
- **THEN** the batch-failure count is unchanged

#### Scenario: A removed pipeline's counter is evicted

- **WHEN** a pipeline leaves the synced set
- **THEN** its backfill batch-failure series is removed from the registry

## MODIFIED Requirements

### Requirement: Pipeline identity is spelled the same way in every signal

The runner SHALL identify a pipeline by its **name**, under one spelling per signal type, so a metric series,
a log query and a trace filter can be written from the same value without translation:

- **Gauges** naming the synced set SHALL be `runner.pipelines.cached`, `runner.pipelines.paused` and
  `runner.pipelines.unsupported`.
- **Per-pipeline series** SHALL carry the tag key `pipeline` — on `runner.spend.calls.today`,
  `runner.scan.lag.seconds`, `runner.class1.rows.written`, `runner.scan.rows.skipped`,
  `runner.scan.pages.refused` and `runner.backfill.batch.failures`.
- The **MDC** key SHALL be `pipeline_name`.
- The **span attribute** SHALL be `pipeline.name`, set on every span the runner opens that is scoped to one
  pipeline: the scan tick, the dirty-mark, the materialize, the group sweep, the group assembly, the DIAL
  call and the upsert.

A series that is **not** scoped to one pipeline SHALL carry no `pipeline` tag rather than a placeholder
value: `runner.adas.contract.compatible` and `runner.sync.empty.feeds` describe the runner's relationship
with ADAS, which no pipeline owns.

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

#### Scenario: A runner-scoped series carries no pipeline tag

- **WHEN** `runner.adas.contract.compatible` is inspected
- **THEN** it carries no `pipeline` tag
