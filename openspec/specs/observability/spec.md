# observability Specification

## Purpose
The runner's own signals and how they stay cheap: health probes, a Prometheus scrape endpoint, OTLP export,
and the discipline that keeps gauges from doing unbounded work on the scrape path or growing unbounded
cardinality.

## Requirements

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

### Requirement: Prometheus metrics endpoint

The service SHALL expose a Prometheus scrape endpoint at `/actuator/prometheus` backed by a
Micrometer Prometheus registry.

#### Scenario: Prometheus endpoint is scrapeable

- **WHEN** `GET /actuator/prometheus` is requested
- **THEN** the response is Prometheus text-format metrics

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

### Requirement: All OTLP signals report one identical resource

Exported traces, logs, and metrics SHALL carry the same OpenTelemetry resource, including the
`telemetry.sdk.*` attributes describing the emitting SDK. Metrics SHALL therefore be recorded through the
same OpenTelemetry SDK that carries traces and logs, rather than through a second OTLP implementation.

Spring Boot's OpenTelemetry module configures tracing and logging only and leaves metrics to Micrometer's
`OtlpMeterRegistry`, which serializes OTLP itself and stamps a resource naming Micrometer as the producing
SDK. Each value is individually accurate, but the two pipelines split one service into two OTLP resource
identities, fragmenting resource-keyed correlation in backends that group by more than `service.name`. The
divergence cannot be closed by configuration, because that registry treats `telemetry.sdk.name`,
`telemetry.sdk.language` and `telemetry.sdk.version` as reserved and drops caller-supplied values for them.
Because a split resource still exports working metrics, it is invisible to any assertion that only checks
that metrics are exported, and SHALL be covered by a test asserting the resource on exported metric data
equals the resource applied to traces and logs.

#### Scenario: A metric and a span agree on their origin

- **GIVEN** OTLP metric export is enabled
- **WHEN** a metric is recorded and flushed to the metric exporter
- **THEN** the resource on the exported metric equals the resource applied to the tracer and logger
  providers, and reports `telemetry.sdk.name` of `opentelemetry`

#### Scenario: Metric export stays off by default

- **WHEN** metric export is disabled
- **THEN** no meter provider, OTLP metric exporter, or bridged registry is created

#### Scenario: A second metric exporter does not run alongside

- **GIVEN** the framework's own OTLP metric exporter defaults to active whenever its registry is on the
  classpath
- **WHEN** the application starts with metric export enabled
- **THEN** only the SDK-backed exporter is active, so no meter is exported twice under two resources
- **AND** the Prometheus registry remains present

### Requirement: Application logs are bridged into the OpenTelemetry SDK

The log4j2 appender that feeds the OTLP log exporter SHALL be installed with the Spring-configured
OpenTelemetry SDK at startup. The Boot OpenTelemetry starter configures the exporter but does not install the
appender, and an uninstalled appender silently buffers a bounded number of events and drops the rest — so log
export is configured-but-dead unless the runner installs it itself. Because this failure mode is invisible
from configuration alone (traces and metrics keep exporting normally from the same endpoint and toggle), it
SHALL be covered by a test that drives a real log4j2 event through to a log-record exporter.

#### Scenario: An application log reaches the OTLP exporter

- **GIVEN** OTLP log export is enabled and the OpenTelemetry SDK bean is present
- **WHEN** application code logs through log4j2
- **THEN** the corresponding log record is emitted to the SDK's log-record exporter

#### Scenario: A missing SDK does not break startup

- **WHEN** the application starts with no OpenTelemetry bean available
- **THEN** the appender install is skipped and the context starts normally

### Requirement: Pipeline identity is spelled the same way in every signal

The runner SHALL identify a pipeline by its **name**, under one spelling per signal type, so a metric series,
a log query and a trace filter can be written from the same value without translation:

- **Gauges** naming the synced set SHALL be `runner.pipelines.cached`, `runner.pipelines.paused` and
  `runner.pipelines.unsupported`.
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

#### Scenario: A failure record keeps the identity

- **WHEN** a pipeline-scoped execution fails and its failure is logged by the scheduler and by the failure
  handler
- **THEN** each of those records carries `pipeline_name`

### Requirement: Work deferred for capacity is counted

The runner SHALL count group work the readiness sweep declined to enqueue because the pipeline was at its
depth ceiling, separately from work it declined because an item for that group was already in flight.

The two are opposite conditions that produce the same silence. Work already in flight is the sweep doing
exactly what it should and is the number an operator wants driven to zero; work deferred for capacity says
the pipeline has more ready groups than the pool can hold, which is normal during a backlog drain and a
symptom when it persists. A pipeline enqueueing nothing because it has nothing ready is a third state
again. Folding any two of them into one counter makes the pass's outcome unreadable.

#### Scenario: A deferral is distinguishable from a suppression

- **GIVEN** a pipeline at its depth ceiling with both already-queued groups and deferred ones
- **WHEN** the sweep runs
- **THEN** the deferred groups are counted on their own series and are not added to the suppressed count

### Requirement: An auto-pause is alertable on its own

The runner SHALL expose the number of pipelines paused **by the circuit breaker** separately from the
total number paused.

The total cannot carry an alert. An operator's pause is a legitimate steady state, so a rule written
against the total is either permanently lit or permanently ignored — and it was the total that existed
while a pipeline sat auto-paused for fourteen hours with every surface reading healthy. A breaker-origin
pause has no legitimate steady state: it is by construction a temporary inference about an upstream, so
"one of these has persisted" is exactly the alert condition, and the separate series is what makes it
expressible.

Both series SHALL be untagged counts. The condition is about the runner's state, not about any one
pipeline's identity, and the listing is where a specific pipeline is named.

#### Scenario: An operator pause does not light the auto-pause alarm

- **GIVEN** a pipeline an operator has paused
- **WHEN** the metrics are scraped
- **THEN** the total paused gauge counts it and the auto-paused gauge does not

#### Scenario: A resumed auto-pause clears its series

- **GIVEN** an auto-paused pipeline whose pause window has elapsed
- **WHEN** the metrics are scraped after it resumes
- **THEN** the auto-paused gauge reads zero

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

### Requirement: A pipeline state reset is logged and counted

When the sync resets a pipeline recreated under the same name, the runner SHALL log one WARN line naming the pipeline,
the `created_at` it replaced and the new one, how many group rows and queued work items it deleted and how many
backfill runs it cancelled, and whether an operator pause was kept; and SHALL count the reset in
`runner.pipeline.state.resets`. The counter SHALL be untagged: a reset describes the registry rather than one
pipeline's activity, the line names the pipeline, and a per-pipeline series would be evicted with the pipeline it
counts. The line is the runner's, not an operator action: it SHALL NOT be written through the operator-action log.

#### Scenario: A reset is logged and counted

- **WHEN** the sync resets a recreated pipeline
- **THEN** one WARN line names the pipeline, both `created_at` values and what was removed, and
  `runner.pipeline.state.resets` increases by one

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

### Requirement: A legacy pre-fold work item is counted, not only logged

The runner SHALL count every legacy pre-fold work item it recognises — one whose payload names
`evaluatorVersion` rather than `pipelineGeneration` — as
`runner.workitem.prefold`, tagged by `lane` (`chunk`, `group`, `upsert`). The series SHALL be registered
at startup for all three lanes rather than on first use, for the same reason the cursor-stall counters
are: this fires only in the deploy window that follows the fold, and an alert cannot be written against a
series that springs into existence at the moment it is already too late.

The tag SHALL be the lane and SHALL NOT be the pipeline. The condition is a property of the **roll-out** —
a job queue that outlived the build that filled it — and no pipeline owns it; what the lane says is what
became of the item, which is the only question the signal is asked. `chunk` and `upsert` mean an item
normalised onto the pipeline's live `generation` and run, and `group` means an item discarded because the
readiness sweep re-derives it unaided.

This counter SHALL be the **only** record of the event, which is what makes registering it non-optional.
Nothing is dead-lettered for a pre-fold item on any lane, so the dead-letter queue does not move and
there is nothing to list afterwards; the log line, though at WARN, is a line. The counter SHALL be
expected to **rise during the fold's deploy window and then stay flat on every lane permanently**, so the
alertable condition is an increment after that window rather than any non-zero value.

#### Scenario: A tolerated pre-fold item increments its lane's counter

- **GIVEN** a work item enqueued before the fold, still queued when the post-fold build starts
- **WHEN** it becomes due and is adopted or discarded rather than dropped as stale
- **THEN** `runner.workitem.prefold` increases for that item's lane, and no dead-letter row is written

#### Scenario: All three lanes are scrapeable before any item arrives

- **WHEN** the Prometheus endpoint is scraped on a freshly started process
- **THEN** `runner.workitem.prefold` is present for each of `chunk`, `group` and `upsert`, reading zero

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

### Requirement: Log levels change at runtime from a mounted file

The runner SHALL poll a JSON level file at `observability.log-levels.path` (`LOG_CONFIG_PATH`, default
`/app/log-config/logging.levels.json`) every `observability.log-levels.interval` (`LOG_CONFIG_INTERVAL`, a
duration, default `10s`, minimum `1s`). It SHALL apply the file's levels to running loggers without a restart.

The file SHALL have the form `{"loggerLevels": {"<logger>": {"defaultLevel": "<level>", "configuredLevel":
"<level>", "validTill": <epoch ms>}}}`. An entry's effective level SHALL be `configuredLevel` while `validTill` is
in the future or absent, and otherwise `defaultLevel`. An entry with no applicable level SHALL revert its logger
to its baseline. This is
what makes a diagnostic level **time-boxed**: it reverts by itself, so a forgotten DEBUG costs no more than its
window.

A level SHALL be applied to the **exact** logger name. Applying one SHALL NOT change any other logger's own
configuration, so a pinned descendant keeps its pin. Before a logger is first changed, the runner SHALL record its
configured baseline, from `log4j2-spring.xml` or from `logging.level.*`. When an entry disappears or expires with
no `defaultLevel`, the runner SHALL restore that baseline. It SHALL NOT make the logger inherit from its parent.

The runner SHALL re-evaluate only when the file's content changes or an entry's `validTill` passes, so a level set
by other means is not silently overwritten every poll. File states:
- A file absent at startup SHALL produce one INFO naming the path, and change nothing.
- A file removed after it was applied SHALL revert every logger it changed.
- An unparsable file SHALL keep the last applied state, with one WARN per distinct content.
- An entry naming an unknown level SHALL be skipped, with one WARN.

A level below INFO SHALL be refused for a logger pinned by the body-handling constraints (see the
`service-runtime` capability) and for any of its descendants, with one INFO naming the refused entry.

Every applied change SHALL be logged at INFO as `log level {logger}: {old} -> {new} (source=file,
until={validTill|-})`, so the log itself records when verbosity was raised. An entry setting a level below INFO
without a `validTill` SHALL additionally produce one WARN.

#### Scenario: A time-boxed level reverts by itself

- **GIVEN** an entry setting `com.epam.aidial.enrichment.class1` to DEBUG until a time T, with `defaultLevel` INFO
- **WHEN** the file is polled before T, and again after T
- **THEN** the logger is at DEBUG before T and at INFO after T, and each change is logged with its old and new
  level

#### Scenario: A removed entry restores the pinned baseline

- **GIVEN** a logger declared in `log4j2-spring.xml` at a level different from its parent's
- **WHEN** an entry changes it and is later removed from the file
- **THEN** the logger returns to its declared level, not to its parent's

#### Scenario: A pinned logger cannot be lowered

- **WHEN** the file sets `org.springframework.web.client.RestClient` to TRACE
- **THEN** the entry is refused with one INFO naming it, and the logger stays at INFO

#### Scenario: A broken file keeps the last good state

- **GIVEN** a file that has been applied
- **WHEN** it is replaced with unparsable content and polled repeatedly
- **THEN** the applied levels are unchanged and exactly one WARN is logged for that content

#### Scenario: No file changes nothing

- **WHEN** the runner starts with no file at the configured path
- **THEN** one INFO names the path and every logger keeps its configured level

### Requirement: Annotated components can be traced on demand

The runner SHALL provide a `@LogExecution` annotation for types and methods. It SHALL trace every annotated
method invoked through its Spring bean, and on an annotated type every public method except those declared by
`Object`. Each trace line SHALL be logged through the **target class's own logger**, and only while that logger
is at TRACE:

- `enter {Class}.{method}()` before the call;
- `exit {Class}.{method}() in {ms} ms` on return;
- `exception {Class}.{method}() in {ms} ms: {classified cause}` on a throw, after which the exception propagates
  unchanged.

Trace lines SHALL NOT carry arguments, return values, stack traces or response bodies. The identifiers an
operator needs are in the MDC. At any level above TRACE, tracing SHALL add no log output. Tracing SHALL be
switchable with `observability.execution-trace.enabled` (`EXECUTION_TRACE_ENABLED`, default `true`).

Combined with runtime levels, this lets an operator turn on call tracing for one class, time-boxed, on a live pod.
Tracing covers only calls made through the bean. A private or self-invoked step produces no line, and
`docs/configuration.md` SHALL list which steps of the annotated classes that hides.

#### Scenario: Tracing is silent until TRACE

- **GIVEN** an annotated executor whose logger is at INFO
- **WHEN** it runs
- **THEN** no trace line is logged and the method runs exactly once

#### Scenario: TRACE shows entry, exit and duration

- **GIVEN** the same executor's logger set to TRACE
- **WHEN** it runs
- **THEN** one enter line and one exit line with a duration are logged, carrying the execution's MDC

#### Scenario: A failing call is traced without its body

- **GIVEN** a traced client whose call fails with an error response carrying a marker string in its body
- **WHEN** the call throws
- **THEN** an exception line names the class, the method, the duration and the classified cause, the marker
  appears in no record, and the caller receives the original exception

### Requirement: Every ADAS and DIAL call is timed

The runner SHALL log every ADAS and DIAL call at DEBUG with its method, path (no query string), status and
duration. It SHALL log at WARN a call that took more than half of its client's read timeout, a call answered with
`429` or any `5xx`, and a call that failed in transport. A transport WARN SHALL name the read-timeout property it
ran against (`adas.read-timeout-ms` or `dial.read-timeout-ms`). No call line SHALL carry a request or response
body.

#### Scenario: A slow call warns before it times out

- **GIVEN** an ADAS call that completes in more than half of `adas.read-timeout-ms`
- **WHEN** it returns
- **THEN** one WARN names the method, path, status and duration

#### Scenario: A DIAL timeout names its setting

- **WHEN** a DIAL call exceeds `dial.read-timeout-ms`
- **THEN** a WARN names the call, the elapsed time and `dial.read-timeout-ms`

### Requirement: A scheduled execution carries its identity through its failure

The runner SHALL set the MDC keys `task`, `task_instance`, `pipeline_name` and, for backfill work, `run_id`, for
the **whole** of every db-scheduler execution: the handler, the library's completion and failure processing, and
the failure handler. A key set for the execution SHALL NOT be cleared by the code that runs inside it.

A failed execution SHALL produce exactly one failure line from the runner, naming the task, the instance, the
duration and the classified cause. For a task whose failure handler decides retry or archive (chunk, group,
upsert and backfill batch), that handler's line is the one line, and the scheduler listener logs its own only at
DEBUG; for any other task, the listener's line is the one line. The library's own failure line SHALL be switched off: it prints the thrown
exception's message, which for an upstream rejection embeds the response body, and the runner's line carries
everything it would. Each failure SHALL be logged with at most one stack trace in total, and none when its
classified cause is a curated message that already names what failed, such as a transport failure naming the
call and its timeout. A dead execution SHALL be logged at WARN.

An execution that fails only because its work is **held** (an incompatible ADAS, a pipeline cache not yet
synced) SHALL NOT produce a failure line above DEBUG. It is rescheduled without consuming an attempt, and the
held-work summary reports it (see the `runner-ops` capability); a line per held item per delay would be the
flood that summary exists to prevent.

Retry lines from the failure handlers SHALL name the classified cause, the stage and the pipeline. The classified
cause of an HTTP rejection SHALL name ADAS, as `ADAS <status> <code>`, only for a response that carries the
contract echo, since only such a response came from ADAS. Any other rejection, from DIAL or from an ingress,
SHALL be named `HTTP <status>`, with nothing read from its body.

#### Scenario: A failed scan names its pipeline

- **WHEN** a pipeline's scan execution throws
- **THEN** every record of that failure carries `pipeline_name`, one runner line names the task, instance,
  duration and cause, and the scheduler logs no failure line of its own

#### Scenario: A retried chunk says why

- **WHEN** a chunk fails and is rescheduled
- **THEN** its retry line names the classified cause, the stage and the pipeline, and no second stack trace
  is logged for the same failure
- **AND** it is the only WARN for that failure

#### Scenario: A held execution is not a failure line

- **GIVEN** ADAS classified incompatible
- **WHEN** a chunk execution is held and rescheduled
- **THEN** no WARN or ERROR failure line is logged for it

#### Scenario: A DIAL rejection is not labelled ADAS

- **WHEN** a chunk's DIAL call is answered `429` and the chunk is rescheduled
- **THEN** its retry line names `HTTP 429` and the `dial_call` stage, and not `ADAS 429`

### Requirement: Correlation fields are visible in the console and exported as attributes

The console log pattern SHALL show `pipeline_name`, `pipeline_generation` and `task` on every record that carries
them, and SHALL omit each one, key included, from a record where it is empty. The trace and span ids SHALL follow the
same rule. The OTLP log
appender SHALL export exactly these MDC keys as log-record attributes, under the same names:
`pipeline_name`, `pipeline_generation`, `chunk_id`, `task`, `task_instance` and `run_id`.

`grain_key` and `group_key` SHALL NOT be exported, because either may be a user identifier. The appender SHALL
NOT export every MDC key, which would also duplicate the trace context each record already carries.

#### Scenario: An exported record is filterable by pipeline

- **GIVEN** OTLP log export enabled
- **WHEN** a worker logs while processing a chunk
- **THEN** the exported record carries a `pipeline_name` attribute and no `grain_key` or `group_key` attribute

#### Scenario: A record outside any pipeline carries no empty correlation keys

- **GIVEN** a record logged at startup, with no pipeline, task or trace in the MDC
- **WHEN** it is written to the console
- **THEN** the line contains none of `pipeline=`, `gen=`, `task=`, `trace_id=` or `span_id=`

### Requirement: Each pipeline reports its status periodically

The runner SHALL log one status line per synced pipeline every `observability.status-report.interval`
(`STATUS_REPORT_INTERVAL`, default `5m`). Each line SHALL carry:
- the lane and the generation;
- the state: active, paused with its origin, over budget, backpressured, or held;
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

A pipeline's line SHALL be logged at WARN when the pipeline is paused. It SHALL also be logged at WARN when the
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

#### Scenario: A fresh start does not cry stall

- **GIVEN** a runner started less than its stall threshold ago with a lagging pipeline
- **WHEN** the status report runs
- **THEN** that pipeline's line is not a WARN for missing writes, and its last write is `unknown`

### Requirement: A scan fire reports what it did

The runner SHALL log one INFO summary per scan fire. It SHALL carry the pipeline, the pages and rows read, the
chunks enqueued (or, on the group lane, the dirty marks written), the rows sampled out, the rows dead-lettered at input mapping, the lag, whether the cursor
advanced, and the duration. A chunk whose rows are dead-lettered SHALL produce a WARN naming the pipeline, the
count and the stages. That holds even when no row of the chunk was written.

#### Scenario: A chunk that dead-letters every row is visible

- **WHEN** every row of a chunk is dead-lettered
- **THEN** a WARN names the pipeline, the number of rows and their stages
