## ADDED Requirements

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

Combined with runtime levels, this lets an operator turn on call tracing for one class, time-boxed, on a running pod.
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

The console log pattern SHALL show `pipeline_name`, `pipeline_generation` and `task` on every record. The OTLP log
appender SHALL export exactly these MDC keys as log-record attributes, under the same names:
`pipeline_name`, `pipeline_generation`, `chunk_id`, `task`, `task_instance` and `run_id`.

`grain_key` and `group_key` SHALL NOT be exported, because either may be a user identifier. The appender SHALL
NOT export every MDC key, which would also duplicate the trace context each record already carries.

#### Scenario: An exported record is filterable by pipeline

- **GIVEN** OTLP log export enabled
- **WHEN** a worker logs while processing a chunk
- **THEN** the exported record carries a `pipeline_name` attribute and no `grain_key` or `group_key` attribute

### Requirement: Each pipeline reports its status periodically

The runner SHALL log one status line per synced pipeline every `observability.status-report.interval`
(`STATUS_REPORT_INTERVAL`, default `5m`). Each line SHALL carry:
- the lane and the generation;
- the state: active, paused with its origin, over budget, backpressured, or held;
- the last write instant, and the rows written and items dead-lettered since the previous line;
- the lag, the last scan instant, and the next fire, including whether it is overdue.

The runner SHALL additionally log one runner-wide line carrying:
- the sync age and ADAS compatibility;
- queue depth per task, overdue executions and the longest running execution;
- the DLQ size, and backfills running, paused and orphaned.

A backfill run is orphaned when it reads running but has no `backfill-run` execution, so nothing will advance it.
The runner SHALL log one WARN per orphaned run on every report, naming the run, its pipeline and the remedy (pause
and resume), because the condition persists until an operator acts. No read endpoint SHALL log it.

A pipeline's line SHALL be logged at WARN when the pipeline is paused. It SHALL also be logged at WARN when the
pipeline has written nothing for more than three times its cadence while lag is above zero. After a restart,
that second condition SHALL NOT be reported until three cadences have passed, because write activity is held in
process memory. Otherwise the line SHALL be logged at INFO. A value the runner cannot know SHALL be logged as
`-` rather than estimated.

Stopping is the failure a log-only operator cannot otherwise see: a pipeline that stops writing emits nothing.

#### Scenario: An orphaned backfill is reported by the status report

- **GIVEN** a running backfill run whose `backfill-run` execution is gone
- **WHEN** the status report runs twice
- **THEN** each report names the run and its pipeline at WARN, and the runner line counts it as orphaned
- **AND** listing backfills logs nothing about it

#### Scenario: A stalled pipeline warns

- **GIVEN** a pipeline with lag above zero and no write for more than three times its cadence
- **WHEN** the status report runs
- **THEN** its line is logged at WARN with its last write instant and its lag

#### Scenario: A healthy pipeline reports at INFO

- **GIVEN** a pipeline writing within its cadence
- **WHEN** the status report runs
- **THEN** its line is logged at INFO with rows written since the previous line

#### Scenario: A fresh start does not cry stall

- **GIVEN** a runner started less than three cadences ago with a lagging pipeline
- **WHEN** the status report runs
- **THEN** that pipeline's line is not a WARN for missing writes, and its last write is `-`

### Requirement: A scan fire reports what it did

The runner SHALL log one INFO summary per scan fire. It SHALL carry the pipeline, the pages and rows read, the
chunks enqueued (or, on the group lane, the dirty marks written), the rows sampled out, the rows dead-lettered at input mapping, the lag, whether the cursor
advanced, and the duration. A chunk whose rows are dead-lettered SHALL produce a WARN naming the pipeline, the
count and the stages. That holds even when no row of the chunk was written.

#### Scenario: A chunk that dead-letters every row is visible

- **WHEN** every row of a chunk is dead-lettered
- **THEN** a WARN names the pipeline, the number of rows and their stages

## MODIFIED Requirements

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
with ADAS, which no pipeline owns.

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
