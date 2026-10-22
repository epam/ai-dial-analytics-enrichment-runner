## 1. Contract check fix and call timing (must ship in the 0.2.x build)

- [x] 1.1 Add `exception/AdasTransportException extends ResourceAccessException`. The message names the call, the pipeline where known, the elapsed time, `adas.read-timeout-ms` and the remedy. Add it to the curated list in `ErrorMessages.classify`.
- [x] 1.2 In `AdasContractInterceptor`: time the call, then call `response.getStatusCode()` before reading headers. Translate `IOException` into `AdasTransportException`. Skip the echo verdict, leaving the compatibility state unchanged, for 502, 503 and 504, and for any 5xx without the echo.
- [x] 1.3 In `AdasContractInterceptor`, log DEBUG `ADAS {method} {path} -> {status} in {ms}ms`. Log WARN when the call takes more than half of `adas.read-timeout-ms`, returns 429 or 5xx, or fails in transport. Never log bodies or query strings.
- [x] 1.4 Add a `DialCallTimingInterceptor` to `DialClientConfiguration` with the same lines, naming `dial.read-timeout-ms`. It has no echo check.
- [x] 1.5 WireMock tests:
  - A POST delayed past the timeout gives `AdasTransportException`, no `AdasContractMismatchException`, and the state stays compatible.
  - A 502 without the header leaves the state unchanged.
  - Chunk, group and upsert timeouts still map to `TransientChunkException` with the DIAL, GROUP_FETCH and UPSERT stages.
  - The half-timeout WARN fires.
  - Update `ErrorMessagesTest`.
- [x] 1.6 Carry over from the earlier materialize-timeout logging work: the Class-1 per-window duration and the drain position in its failure line.

## 2. Logging configuration baseline

- [x] 2.1 In `log4j2-spring.xml`:
  - Root is a fixed `INFO`, and the shadowing `<Properties>` default is removed so `com.epam` reads `APP_LOG_LEVEL` from the environment. `APP_LOG_LEVEL` stays the application-package level, as documented.
  - Add INFO pins for `org.hibernate.SQL`, `org.hibernate.orm.jdbc.bind` and `org.springframework.jdbc.core`, next to the existing web-client and converter pins.
  - No `monitorInterval`.
- [x] 2.2 Console pattern: add `pipeline=%X{pipeline_name} gen=%X{pipeline_generation} task=%X{task}`, keeping existing message prefixes.
- [x] 2.3 OTLP appender: add `captureContextDataAttributes="pipeline_name,pipeline_generation,chunk_id,task,task_instance,run_id"`.
- [x] 2.4 Test: an exported log record carries `pipeline_name` and no `grain_key` or `group_key`. Extend the existing appender test.

## 3. Dynamic log levels

- [x] 3.1 Add `observability/loglevel/LogLevelProperties`, a `@ConfigurationProperties` record for `observability.log-levels`: `path`, and `interval` as a `Duration` with a minimum of 1 s. Add the bare lines to `application.yml`.
- [x] 3.2 Add the `LogLevelSource` interface and `FileLogLevelSource`, which reads the bytes, hashes them and parses the level-file format into DTOs. Use Lombok.
- [x] 3.3 Add `LogLevelUpdater` (`@Scheduled` on the interval):
  - Compute effective levels (`configuredLevel` while `validTill` is in the future or absent, otherwise `defaultLevel`, otherwise baseline).
  - Re-evaluate only on a hash change or when the next `validTill` passes.
  - Apply through `LoggingSystem.setLogLevel` on the exact name.
  - Snapshot `getConfiguredLevel()` before the first change, and restore it (or `null`) on revert.
- [x] 3.4 File states and validation:
  - Missing at startup: one INFO.
  - Removed after it was applied: revert all.
  - Unparsable: keep state, one WARN per hash.
  - Unknown level: one WARN, skip.
  - Denylist: refuse below INFO for pinned names and their descendants, one INFO.
  - Change line `log level {logger}: {old} -> {new} (source=file, until=…)`.
  - WARN for a below-INFO entry without `validTill`.
- [x] 3.5 Tests:
  - A time box applies, then reverts.
  - A removed entry restores the XML pin, not the parent's level.
  - An ancestor set to DEBUG leaves pins at INFO.
  - Denylisted descendants are refused.
  - Missing file: nothing changes. Removed file: everything reverts. Bad JSON: one WARN, state kept.
  - An unchanged hash is a no-op.
  - An interval below 1 s is rejected.

## 4. `@LogExecution` tracing

- [x] 4.1 Add `observability/trace/LogExecution` (RUNTIME retention; TYPE and METHOD targets).
- [x] 4.2 Add `LogExecutionInterceptor`:
  - Use the target class's logger. When TRACE is off, return `proceed()` straight away.
  - Log enter, exit and exception with durations. The exception line carries `ErrorMessages.classify(ex)`, never the throwable or the arguments, and the exception is rethrown.
- [x] 4.3 Add `LogExecutionConfiguration`: a `DefaultPointcutAdvisor` over the class-level `AnnotationMatchingPointcut` (inherited) unioned with the method-level one, excluding `Object` methods. Mark the advisor `@Role(ROLE_INFRASTRUCTURE)` and gate it on `observability.execution-trace.enabled` (`EXECUTION_TRACE_ENABLED:true`).
- [x] 4.4 Annotate:
  - `PipelineScanExecutor`, `Class1MaterializeExecutor`, `GroupReadinessSweep`, `WorkerService`, `GroupWorkerService` and `BackfillExecutor`
  - the ADAS clients and the DIAL client
  - `UpsertService` and `PipelineSyncService`
  - the chunk, group, upsert and backfill failure handlers
- [x] 4.5 Tests:
  - At INFO: no lines, and exactly one invocation.
  - At TRACE: enter and exit lines with MDC.
  - Exception line: a body marker is absent and the original exception propagates.
  - `Object` methods are not traced.
  - The switch-off removes the advisor.
  - An annotated bean is an AOP proxy.

## 5. Per-execution MDC and failure lines

- [x] 5.1 Add `ExecutionMdcListener extends AbstractSchedulerListener`:
  - Set `task`, `task_instance`, `pipeline_name` and `run_id` in `onExecutionStart`. Derive the pipeline from the instance id, or from the backfill payload.
  - In `onExecutionComplete`, log one WARN failure line (task, instance, ms, classified cause) and remove exactly its own keys.
  - Log a WARN in `onExecutionDead`.
- [x] 5.2 In `DbSchedulerConfiguration`, register the listener and set `.failureLogging(LogLevel.OFF, false)`.
- [x] 5.3 In the scan, Class-1, worker, group-worker, upsert and sweep code, remove only the MDC keys each one adds (`MDC.putCloseable`). Never clear a listener-owned `pipeline_name`.
- [x] 5.4 Add a `LogSafe.loggable(Throwable)` helper (null when the throwable or any cause is a `RestClientResponseException`), and apply it everywhere a failure is logged:
  - Sites: `ChunkFailureHandler`, `GroupFailureHandler`, `UpsertFailureHandler`, `BackfillBatchFailureHandler`, `UpsertService` and `Class1MaterializeExecutor`.
  - Retry lines become `… failed: {classified} stage={} pipeline={}`.
  - One stack per failure, from the deciding handler only.
- [x] 5.5 Tests:
  - A failed scan's records all carry `pipeline_name`, with one runner failure line and one stack.
  - A retried chunk's line names the cause, stage and pipeline.
  - A DIAL error body marker is absent from every record on each failure path.

## 6. Pipeline status heartbeat

- [x] 6.1 Add the `observability/status/PipelineActivity` in-memory map (last write, rows and DLQ since the last report). Update it on upsert success, Class-1 write success and DLQ archive.
- [x] 6.2 Record the last successful sync instant in `ReadinessState`, for `sync_age`.
- [x] 6.3 Add `PipelineStatusReporter` (`@Scheduled` at `observability.status-report.interval`, `STATUS_REPORT_INTERVAL:5m`):
  - One line per pipeline, sourced as in the design's heartbeat field table.
  - WARN when paused (origin, reason, age), or when there is no write for more than 3× cadence while lagging, after a 3× cadence startup grace.
  - `-` for unknown values.
  - One runner-wide line.
- [x] 6.4 Remove `PipelinePauseRegistry.reportPaused` and `dlq.pause-report-interval` (`DLQ_PAUSE_REPORT_INTERVAL`).
- [x] 6.5 Tests:
  - A stall warns.
  - A healthy pipeline reports at INFO.
  - The startup grace suppresses the stall WARN.
  - A paused pipeline gets exactly one WARN per interval.

## 7. Transition-only, operator and per-fire lines

- [x] 7.1 Add a `TransitionLog` helper. Log the spend gate (`calls={}/{} tokens={}/{}`) and backpressure (`inflight={} > scan.max-inflight={}`) only on change.
- [x] 7.2 `WorkHold`: log the first hold, then a count at most every 5 min.
- [x] 7.3 Log the `GroupReadinessSweep` pause skip at INFO when a pause begins and ends, and at DEBUG otherwise, since the sweep runs every pass.
- [x] 7.4 Operator action lines with the principal (or `principal=-`): pipeline pause and resume (`PipelineAdminController`), backfill start, pause, resume and cancel (`BackfillService`), and DLQ requeue.
- [x] 7.5 Per-fire scan summary line. WARN when a chunk dead-letters rows, with pipeline, count and stages, including the all-DLQ case (`WorkerService`, `ChunkBuilder`).
- [x] 7.6 Backfill: a per-batch progress line. WARN for a RUNNING run with no `backfill-run` execution, reported by the status report every interval (`backfills_orphaned=N` on the runner line), not by the listing endpoint.
- [x] 7.7 Tests: the spend gate logs once per transition; held work is summarised; the pause line names the principal; an all-DLQ chunk warns.

## 8. Sync and residual cleanup

- [x] 8.1 `PipelineSyncService`:
  - Log added, removed and rescheduled pipelines (lane, generation `from->to`) only on change; otherwise DEBUG.
  - Deduplicate failures: the first, then every Nth, then "recovered after n in {duration}".
  - `e.toString()` instead of `getMessage()`.
- [x] 8.2 Log swallowed exceptions: the malformed envelope in `TransformInvoker`, and `GroupReadinessSweep` and `GroupStateEvictor` with the classified cause.
- [x] 8.3 Demote Class-1 zero-row windows and repeated sweep lines to DEBUG.

## 9. Docs and specs

- [x] 9.1 `docs/configuration.md`:
  - New keys (`LOG_CONFIG_PATH`, `LOG_CONFIG_INTERVAL` with a note on duration syntax, `EXECUTION_TRACE_ENABLED`, `STATUS_REPORT_INTERVAL`) and the removed `DLQ_PAUSE_REPORT_INTERVAL`.
  - The level-file format and time boxing, the pinned loggers and denylist, `LOGGING_LEVEL_*` for static levels (packages only; it bypasses the denylist).
  - The ConfigMap directory mount (no `subPath`, `optional: true`, 60-90 s kubelet delay).
  - `@LogExecution` proxy limits and the self-invoked steps they hide.
  - A note that `grain_key` and `group_key` may be user identifiers.
- [x] 9.2 Write the chart hand-over (ConfigMap name, key `logging.levels.json`, mount at `/app/log-config`, `LOG_CONFIG_PATH`, starting file `{"loggerLevels": {}}`) and note it in the release-0.2 upgrade guide.
- [x] 9.3 Confirm the delta specs match what shipped, and run `openspec validate improve-logging-observability --strict`.
- [x] 9.4 ~~Check whether any dashboard panel matches on message text, before release.~~ Dropped by the maintainers: existing message prefixes are kept, so it is not needed for this change.
- [x] 9.5 `./gradlew clean build` passes (use `TESTCONTAINERS_RYUK_DISABLED=true` if Ryuk can't start).
