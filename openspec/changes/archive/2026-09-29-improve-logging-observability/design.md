## Context

This design was checked against the library sources in use: Spring Boot 4.0.7, Spring Framework 7.0.8, log4j 2.25.4, db-scheduler 15.6.0 and the OTel
log4j appender 2.28.1-alpha. Facts this design relies on:

- **HTTP clients.** ADAS and DIAL clients use `SimpleClientHttpRequestFactory`. For a request with a body,
  `SimpleClientHttpRequest.executeInternal` never calls `getResponseCode()`. `getHeaders()` on a timed-out
  connection then swallows the `SocketTimeoutException` and returns no headers.
- **Transport failures.** They are recognised only by `catch (RestClientException)` →
  `TransientChunkException(stage)` in `WorkerService`, `GroupWorkerService` and `UpsertService`.
- **Topology.** The runner is a single active replica (runner-ops). Per-process state is acceptable, provided it
  survives the overlap of a rolling update.
- **Existing MDC.** `WorkerMdc` defines `pipeline_name`, `chunk_id`, `pipeline_generation`, `grain_key` and
  `group_key`. Row-parallel work copies MDC onto its pool threads by hand. The executors' `finally MDC.remove`
  clears `pipeline_name` before db-scheduler logs a failure.
- **db-scheduler.** It is built by hand in `DbSchedulerConfiguration` (no starter). Its `ExecutionInterceptor`
  chain wraps the handler only. The failure logger and `FailureHandler.onFailure` run after the chain.
  `SchedulerListener.onExecutionStart` and `onExecutionComplete` bracket the whole execution on one thread.
- **Pinned loggers.** `log4j2-spring.xml` pins `org.springframework.web.client` and
  `org.springframework.http.converter` at INFO. Root read `${APP_LOG_LEVEL}` from `<Properties>`, not from the
  environment. Root is now pinned at `INFO`, and `APP_LOG_LEVEL` governs only the application package, as
  documented; a library's level is raised through the runtime level file.
- **AspectJ.** `aspectjweaver` is on the classpath only transitively (JPA → `spring-aspects`). Without it, Boot
  applies only `ROLE_INFRASTRUCTURE` advisors.
- **Response bodies.** In Spring 7 a `RestClientResponseException`'s message embeds the full response body.

## Goals / Non-Goals

**Goals:**
- A timeout or gateway error is never reported as contract skew.
- From logs alone, an operator can tell that a pipeline stopped writing, when, and in which state.
- Every failure line names the pipeline, the stage and a classified cause, once, and never carries a body.
- Levels change per logger without a restart, time-boxed, with every change visible in the log.
- On-demand TRACE of entry, exit and duration for the classes that answer "where is it stuck or slow".

**Non-Goals:**
- An actuator `loggers` endpoint, and changing levels over HTTP.
- Logging arguments or return values; a per-method opt-in (`arguments = true`).
- An ArchUnit rule requiring the annotation.
- Configurable trace message templates.
- Removing response bodies from the DLQ `error` column (a storage question, not logging).
- Anything on the ADAS side, which is the paired change in `analytics-data-access-service`.

## Decisions

### Status first, and gateway errors are not verdicts
`AdasContractInterceptor` calls `response.getStatusCode()` before it reads any header. On a POST timeout this
throws `SocketTimeoutException`, which `DefaultRestClient` wraps as `ResourceAccessException`, as on the GET path
today. The echo is then checked only for a response that is not a gateway error:

| Response | Verdict | Compatibility state |
|---|---|---|
| 502, 503 or 504 | none | unchanged |
| any other 5xx without the echo | none | unchanged |
| any other response | checked | set by the check |

ADAS sets the header on every response it produces under `/v1/pipelines/*`, error dispatch included. A 5xx
without it therefore came from the ingress. *Alternative:* keep the current verdict and only reorder the reads.
Rejected, because a gateway 502 would still hold all paid work.

### `AdasTransportException extends ResourceAccessException`
The interceptor catches `IOException` and rethrows `AdasTransportException`, which extends
`ResourceAccessException` (so it stays a `RestClientException`). The existing
`catch (RestClientException)` arms keep producing `TransientChunkException` with the right stage. It is added to
`ErrorMessages.classify`'s curated list so its message survives classification.

*Alternatives:*
- A plain `RuntimeException`, as in the superseded materialize-timeout plan. Rejected: it skips those arms and
  loses the stage.
- Throwing an `IOException` subclass. Viable, but the message would then be prefixed by RestClient's
  "I/O error on POST…".

### Timing lives in the interceptors
The ADAS interceptor gains the timing. A second, echo-less interceptor is added to the DIAL client. Each logs:
- DEBUG `ADAS|DIAL {method} {path} -> {status} in {ms}ms`
- WARN when the call took more than half its read timeout, or returned 429 or 5xx
- WARN on a transport failure, naming the timeout property

The path has no query string, and the line never includes a body.

### Dynamic levels: poll a file, apply through `LoggingSystem`
`observability/loglevel/`:
- `LogLevelProperties`, a record bound to `observability.log-levels.*`: `path` and `interval` (a `Duration`,
  minimum 1 s).
- `LogLevelSource`, the interface; `FileLogLevelSource` reads the bytes and hashes them.
- `LogLevelUpdater`, a `@Scheduled(fixedDelayString = "${observability.log-levels.interval}")` job.

The file format: `{"loggerLevels": {name: {defaultLevel, configuredLevel, validTill}}}`. The level for an
entry is `configuredLevel` while `validTill` is in the future or absent, otherwise `defaultLevel`. This rule is
shared with ADAS. When the chosen level is
absent, the entry reverts to its baseline.

Rules:
- **Apply through `LoggingSystem.setLogLevel(name, level)`, exact name only.** Never
  `Configurator.setAllLevels`: it string-prefix-matches configured loggers, which overwrites the pins and catches
  sibling names.
- **Baseline snapshot.** Before the first change to a name, record
  `getLoggerConfiguration(name).getConfiguredLevel()`. Revert restores that level, or calls
  `setLogLevel(name, null)` when the snapshot was null, which removes Boot's own config. Never
  `Configurator.setLevel(name, null)`: on an XML-declared logger it drops the pin, and the logger inherits from
  its parent.
- **Idempotence.** Re-evaluate only when the content hash changes or the earliest pending `validTill` passes.
- **Validation.** A level is parsed case-insensitively into Spring `LogLevel`. A bad entry gets one WARN and is
  skipped.
- **File states:**

  | State | Behaviour |
  |---|---|
  | missing at startup | one INFO naming the path |
  | removed after it was applied | revert everything, one INFO |
  | unparsable | keep the last applied state, one WARN per content hash |
- **Denylist.** Any level below INFO is refused for a sensitive logger or any descendant. Each refusal gets one
  INFO. Ancestors are allowed: every sensitive logger is **pinned in the XML** with its own LoggerConfig, which an
  exact-name change on an ancestor doesn't touch. Pins and denylist cover `org.springframework.web.client` and
  `org.springframework.http.converter` (both existing), plus `org.hibernate.SQL`, `org.hibernate.orm.jdbc.bind`
  and `org.springframework.jdbc.core` (new).
- **Audit.** INFO `log level {logger}: {old} -> {new} (source=file, until={validTill|-})`. A WARN when an entry
  below INFO has no `validTill`.

*Alternatives:*
- Actuator `loggers`: it is lost on restart, needs a new authenticated endpoint, and the target operator has no
  HTTP access.
- log4j2 `monitorInterval`: it reloads the whole configuration, with no time box and no audit line. It stays out
  of the XML so the two mechanisms don't compete.

### `@LogExecution` with our own interceptor
`observability/trace/`: the `LogExecution` annotation (RUNTIME retention, TYPE and METHOD targets),
`LogExecutionInterceptor` and `LogExecutionConfiguration`.

The interceptor:
- resolves `LoggerFactory.getLogger(AopUtils.getTargetClass(target))`;
- returns `proceed()` straight away when TRACE is off;
- otherwise logs `enter {Class}.{method}()`, then `exit … in {ms} ms` or
  `exception … in {ms} ms: {ErrorMessages.classify(ex)}`, with no throwable argument, and rethrows.

The advisor is a `DefaultPointcutAdvisor` over this pointcut:
```
ComposablePointcut(AnnotationMatchingPointcut(LogExecution, true))
    .union(AnnotationMatchingPointcut.forMethodAnnotation(LogExecution))
    ∩ not declared by Object
```
It is declared `@Role(ROLE_INFRASTRUCTURE)` and gated on `observability.execution-trace.enabled`.

*Alternatives:*
- Spring's `CustomizableTraceInterceptor`. Rejected: `$[exception]` renders `toString()`, and its
  default stack logging carries the response body.
- An AspectJ expression pointcut. Rejected: it depends on a jar that arrives only transitively.

Annotated at first:
- the scan, Class-1, group-sweep, worker, group-worker and backfill executors
- the ADAS and DIAL clients
- `UpsertService` and `PipelineSyncService`
- the failure handlers

Self-invoked private steps (`drain`, `processRow`, `mapRows`, `sweepPipeline`, `enqueueReady`) aren't traced.
That is documented.

### Per-execution MDC via `SchedulerListener`
An `ExecutionMdcListener`, extending `AbstractSchedulerListener`, is registered with `addSchedulerListener`.

`onExecutionStart` puts `task`, `task_instance`, `pipeline_name` and `run_id`:

| Task instances | Id format | `pipeline_name` from |
|---|---|---|
| scan, class1 | the pipeline name | the instance id |
| chunk, group, upsert, requeue | `<pipeline>#…` | the id prefix |
| backfill | `backfill-run#<runId>#…` or `#bf#<runId>#…` | the payload's `getPipelineName()` |

`run_id` comes from the payload or `DeterministicId`.

`onExecutionComplete`:
- on failure of a task with no failure handler of its own (the scan and Class-1 ticks), logs one WARN
  `execution failed: task={} instance={} ms={} cause={classified}`, with the stack unless the classified cause is
  a curated message such as an `AdasTransportException`'s;
- on failure of a chunk, group, upsert or backfill batch, logs that line at DEBUG only: the task's failure handler
  already logs the retry or archive line with cause, stage and pipeline, and a second WARN would repeat it;
- on a **held** failure (`HeldWorkException` in the chain), logs DEBUG only: the item is rescheduled untouched and
  `WorkHold` summarises holds, so a WARN per held item per 30 s would undo that summary;
- in every case, removes exactly the keys it put.

The scheduler is built with `.failureLogging(LogLevel.OFF, false)`: the library line prints the thrown message, which embeds a response body, and the listener line replaces it. The thrown exceptions, and therefore the DLQ `error` text, stay unchanged. `onExecutionDead` logs a WARN. Each executor
removes only the keys it added itself: `chunk_id`, `pipeline_generation`, `grain_key` and `group_key`, never
`pipeline_name` when the listener set it. The simplest form is `MDC.putCloseable` for each key it owns.

*Alternative:* an `ExecutionInterceptor`. Rejected, because the failure path runs outside its chain.

### Pattern and OTLP attributes
- Console pattern: `… [%t] %c{1.} pipeline=%X{pipeline_name} gen=%X{pipeline_generation} task=%X{task}: %msg%n%throwable`.
- OTLP `<OpenTelemetry … captureContextDataAttributes="pipeline_name,pipeline_generation,chunk_id,task,task_instance,run_id"/>`.
  An explicit list is used rather than `*`, which would export `grain_key` and `group_key` and duplicate the trace
  ids that the context-data autoconfigure injects.
- Existing message prefixes stay the same.

### Heartbeat
`observability/status/PipelineStatusReporter`, a `@Scheduled` job at `observability.status-report.interval`:

| Field | Source |
|---|---|
| `lag_s`, `last_scan_at` | `pipeline_cursor` |
| `next_fire`, `overdue` | `scheduled_tasks` |
| `over_budget` | `SpendPauseGate` |
| `backpressured` | `BackpressureGate` |
| pause origin and age | `PipelinePauseRegistry` |
| `held` | `AdasCompatibilityState` |
| `last_write`, `rows_since`, `dlq_since` | a new in-memory `PipelineActivity` map |

`PipelineActivity` is updated on upsert success, Class-1 write success and DLQ archive.

- **Levels.** A line is WARN when the pipeline is paused, or when it has had no write for more than 3× its cadence
  while lag > 0. Otherwise it is INFO.
- **Restart grace.** The no-write WARN is suppressed for 3× the cadence after startup, because the map starts
  empty.
- **Runner line.** One per run: `sync_age`, `adas_compatible`, queue depth per task, overdue executions, the
  longest executing, DLQ size, and backfills running, paused and orphaned (each orphaned run also gets a WARN every report; the check is one bounded query and never runs on a read endpoint). `sync_age` needs `ReadinessState` to record the
  instant of the last success.
- **Pause report.** `PipelinePauseRegistry.reportPaused` and `dlq.pause-report-interval` are retired. The WARN
  line for a paused pipeline carries origin, reason and age, which keeps runner-ops' recurring paused-pipeline
  report.

*Alternative:* derive `last_write` from the database. There is no write timestamp to read, and the single-replica
topology makes in-memory state acceptable.

### Body-safe failure logging
A `LogSafe.loggable(Throwable)` helper returns the throwable when neither it nor any cause is a
`RestClientResponseException`, and returns null otherwise. Every failure path logs the classified form, and passes
`LogSafe.loggable(ex)` as the throwable.

Sites:
- `ChunkFailureHandler`, `GroupFailureHandler`, `UpsertFailureHandler` and `BackfillBatchFailureHandler`
- `UpsertService`
- `Class1MaterializeExecutor`
- the per-execution MDC listener (`ExecutionMdcListener`)

Only one site per failure passes the stack: the handler that decides retry or DLQ. The rest log the classified
cause only.

`ErrorMessages.classify` names an HTTP rejection `ADAS <status> <code>` only when the response carries the
contract echo, which only ADAS sets. Any other rejection — a DIAL answer, an ingress 5xx — is `HTTP <status>`, so a
DIAL 429 on a retry line no longer reads as an ADAS failure. A backfill run's `last_error` is unaffected for every
response ADAS produced; an echo-less gateway error there now reads `HTTP 502` instead of `ADAS 502`. The DLQ
`error` column is not classified and does not change.

### Transition-only and rate-limited lines
A small `TransitionLog` records the last reported state per key and logs only on change. A per-pipeline key is
forgotten when the pipeline leaves the synced set, with the other per-pipeline maps. It is used for:
- the spend gate and backpressure;
- the "held by incompatible ADAS" condition in `WorkHold`: the first hold, then a count every 5 min;
- sync failure deduplication: the first failure, then every Nth, then "recovered after n in {duration}".

Operator actions log the principal from the security context, or `-` under `SECURITY_MODE=none`.

## Risks / Trade-offs

- **Log volume.** A TRACE left on with no `validTill` on the row lane floods the log. → A WARN on load; the
  runbook says always time-box.
- **Stale traces.** A `@LogExecution` proxy on a class that is self-invoked internally shows less than an operator
  expects. → The proxy limits are documented in `docs/configuration.md`; ADAS per-stage durations cover the
  important case.
- **Denylist bypass.** Static levels (`LOGGING_LEVEL_*`, `-Dlogging.level.*`) bypass the denylist. → The XML pins
  still hold for descendants, and it is documented. A deployment that overrides the logging config is already out
  of contract (service-runtime).
- **Removed config.** Removing `DLQ_PAUSE_REPORT_INTERVAL` is a config change for operators. → A release note; an
  unknown env var is ignored, so nothing breaks at startup.
- **Dashboard panels.** A panel matching on message text could go blank if a prefix changes. → Existing prefixes are
  kept.
- **Heartbeat after restart.** The map is empty, so the first lines after a restart under-report `last_write`. →
  The grace window, and `last_write=-` rather than a fabricated value.
- **Client type change.** `AdasTransportException` changes the exception class that callers see for ADAS transport
  failures. → It is a `ResourceAccessException` subclass, and a test pins stage classification for each lane.

## Migration Plan

1. Ship in the 0.2.x upgrade build. The status-first contract check and
   `AdasTransportException` must not ship later than the version bump.
2. The chart adds the ConfigMap: key `logging.levels.json`, directory mount at `/app/log-config`, `optional: true`,
   not immutable, starting content `{"loggerLevels": {}}`. The runner works without it.
3. Rollback: redeploy the previous image. No state or schema changes are involved.

## Open Questions

None. Existing message prefixes are kept stable, so dashboard panels that match on message text keep working.
