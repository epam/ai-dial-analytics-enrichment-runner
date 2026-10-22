## ADDED Requirements

### Requirement: ADAS contract compatibility is published as a metric, not as readiness

The runner SHALL publish `runner.adas.contract.compatible` as a gauge holding `1` while the last ADAS
response carried this build's contract echo and `0` while it did not. The gauge SHALL be registered at
startup so the series exists before the first skew, and it SHALL be untagged: the condition is a property
of the pair of deployments, not of a pipeline.

The runner SHALL also count `runner.sync.empty.feeds` once per successful poll that returns an empty
envelope, including the first, unconfirmed one.

These two are the whole paging channel for a version skew, by design. Readiness deliberately does not
move for a process that has already started (see the `pipeline-sync` capability), so an alert has nothing
else to read: the sync is not failing, the DLQ is empty, no tick-failure counter moves, and scan lag falls
to zero rather than rising. A condition whose only other symptom is the *absence* of activity has to be
published as a value.

#### Scenario: The compatibility gauge exists before any skew

- **WHEN** the exposed metric set is inspected on a runner that has only ever seen a matching ADAS
- **THEN** `runner.adas.contract.compatible` is present and reads `1`

#### Scenario: The gauge falls and recovers with the condition

- **GIVEN** an ADAS answering without the contract echo
- **WHEN** a poll runs and later a matching ADAS answers
- **THEN** the gauge reads `0` while incompatible and `1` again afterwards, with no restart

#### Scenario: An empty feed is counted even when it is not acted on

- **WHEN** one poll returns an empty envelope and the runner leaves its tasks in place
- **THEN** `runner.sync.empty.feeds` increases

### Requirement: Rows skipped and pages refused are counted per pipeline

The runner SHALL count every scanned row it does not turn into work as `runner.scan.rows.skipped`, tagged
by `pipeline` and by `reason` (sampling, or a null value in the grain or group-by column), and SHALL count
every page it refuses as `runner.scan.pages.refused`, tagged by `pipeline` and by `reason` (a required
column absent from the page, or a page whose rows were not all accounted for). Both series SHALL be
evicted with the pipeline, like every other per-pipeline meter, so tag cardinality stays bounded, and both
SHALL be recorded for a refused **backfill** page as well as a live one — the tag is the pipeline, and a
backfill's refusals belong to it.

A skipped row is the one event on the scan path that moves nothing else: the cursor advances, the lane
reports success, lag reports caught up, and the DLQ stays empty. Counting the skips is what makes the
difference between "this pipeline enriches a sampled fraction" and "this pipeline enriched nothing"
readable without reading the source table. The refusal counter is its pair: it says the runner declined to
advance, which is otherwise visible only as a tick failure among all other tick failures.

#### Scenario: A sampled-out row and a null grain are told apart

- **GIVEN** a pipeline with a sampling fraction whose page also contains a row with a null grain value
- **WHEN** the page is processed
- **THEN** `runner.scan.rows.skipped` records each under its own `reason` for that pipeline

#### Scenario: A refused page is attributable

- **WHEN** a page is refused for a missing required column
- **THEN** `runner.scan.pages.refused` increases for that pipeline with that reason

#### Scenario: A removed pipeline's series are evicted

- **WHEN** a pipeline leaves the synced set
- **THEN** its `runner.scan.rows.skipped` and `runner.scan.pages.refused` series are removed from the
  registry

## MODIFIED Requirements

### Requirement: Pipeline identity is spelled the same way in every signal

The runner SHALL identify a pipeline by its **name**, under one spelling per signal type, so a metric series,
a log query and a trace filter can be written from the same value without translation:

- **Gauges** naming the synced set SHALL be `runner.pipelines.cached`, `runner.pipelines.paused` and
  `runner.pipelines.unsupported`.
- **Per-pipeline series** SHALL carry the tag key `pipeline` — on `runner.spend.calls.today`,
  `runner.scan.lag.seconds`, `runner.class1.rows.written`, `runner.scan.rows.skipped` and
  `runner.scan.pages.refused`.
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
