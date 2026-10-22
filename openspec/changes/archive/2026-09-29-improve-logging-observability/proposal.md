## Why

An operator who can read only logs can't yet answer "is pipeline X writing, and if not, since when and why?" or
"why did this call time out, and on which side?". Scan-lane failures don't name the pipeline. Successful ADAS and
DIAL calls leave no duration. MDC never reaches exported logs. Several error paths write HTTP response bodies
into the log. Diagnosing further means a restart with a different log level.

There is also a correctness bug. On a POST, `AdasContractInterceptor` reads the headers without reading the
status first. A read timeout therefore comes back as "no contract echo", which holds all paid work as contract
skew. A gateway 502/503/504, which carries no echo, does the same. Neither path is reachable on 0.1.x builds;
both become reachable with the 0.2.x upgrade, so the fix has to be in that build.

This change is the Runner half. The ADAS half is the change of the same name in
`analytics-data-access-service`.

## What Changes

- **Contract check fix.** Read the response status before the headers. A transport failure never becomes a
  contract verdict, and neither does a 502/503/504 or any 5xx without the echo. A transport failure surfaces as
  `AdasTransportException`, which extends `ResourceAccessException`, so the existing transient classification and
  its stage are kept. Its message names the call, the pipeline, the elapsed time, `adas.read-timeout-ms` and the
  remedy.
- **Call timing for ADAS and DIAL.** A DEBUG line per call (status and duration). A WARN when a call takes more
  than half its read timeout, returns 429 or 5xx, or fails in transport. Bodies are never logged.
- **Dynamic per-logger levels.** A JSON file (mounted from a ConfigMap) is polled every `LOG_CONFIG_INTERVAL`
  (default `10s`). Its entries can be time-boxed with `validTill`. Levels are applied through Spring's
  `LoggingSystem` on the exact logger name, and reverted to a recorded baseline. Every change is logged. Loggers
  that can print bodies or SQL are pinned in `log4j2-spring.xml` and denylisted below INFO for themselves and all
  their descendants.
- **`@LogExecution` tracing.** A marker annotation on selected executors, clients and services. A small
  interceptor logs enter, exit and exception lines with durations, through the target class's logger, only when
  that logger is at TRACE. It never logs arguments, return values, stacks or bodies. It is switched by
  `EXECUTION_TRACE_ENABLED` (default on; free at INFO).
- **MDC per execution.** A db-scheduler `SchedulerListener` sets `task`, `task_instance`, `pipeline_name` and
  `run_id` for the whole execution, failure handling included. It writes one classified failure line per failed
  execution, and a WARN for a dead execution. The library's own failure line is switched off, because the runner's line replaces it.
  The executors stop clearing keys they didn't set.
- **Log pattern and export.** The console pattern gains `pipeline`, `gen` and `task`. The OTLP appender exports an
  explicit list of MDC keys as attributes (`pipeline_name,pipeline_generation,chunk_id,task,task_instance,run_id`).
  Grain and group keys are left out.
- **Pipeline status heartbeat.** One INFO line per pipeline every `STATUS_REPORT_INTERVAL` (default `5m`) with
  lane, state, last write, rows and DLQ since the last line, lag, last scan and next fire. It WARNs when a pipeline
  has stopped writing while lagging, or when it is paused. One runner-wide line reports sync age, compatibility,
  queue depths and running backfills.
- **Retry and failure lines** name the classified cause, the stage and the pipeline, with one stack per failure
  instead of up to three. No line passes a throwable that carries an HTTP response body.
- **Transition-only gate lines** with their values: spend gate, backpressure, operator pause and resume with the
  principal. Also a summary line per scan fire, a WARN when a chunk dead-letters rows, operator-action lines for
  backfill and DLQ requeue, progress lines per backfill batch, a rate-limited `WorkHold`, and deduplicated sync
  logging. Swallowed exceptions are logged. Noise moves to DEBUG.
- **Root logger.** Root is a fixed `INFO`. `APP_LOG_LEVEL` keeps its documented meaning, the application package, so raising it never makes every library verbose.
- **BREAKING (config):** `DLQ_PAUSE_REPORT_INTERVAL` (`dlq.pause-report-interval`) is removed. The recurring
  paused-pipeline WARN is now part of the heartbeat, driven by `STATUS_REPORT_INTERVAL`. A deployment that still
  sets the old variable has it ignored.

## Capabilities

### New Capabilities

None. Every addition extends an existing capability.

### Modified Capabilities

- `pipeline-sync`: the requirement on the contract echo check now states that the status is read first. A
  transport failure, a gateway status (502/503/504) or a 5xx without the echo is not a contract verdict, and it
  leaves the compatibility state unchanged.
- `observability`:
  - *Added:* dynamic log levels from a mounted file; `@LogExecution` TRACE tracing; call timing for ADAS and DIAL;
    per-execution MDC for db-scheduler with one failure line; MDC exported as OTLP attributes from an explicit
    list; the pipeline status heartbeat.
  - *Modified:* "Pipeline identity is spelled the same way in every signal" also covers failure-path records.
- `service-runtime`: "Body-handling constraints" extends to response bodies carried in exception messages and
  stack traces. It also names the pinned loggers and the denylist that protects them from dynamic levels.
- `class1-enrichment`: added — each committed materialize window logs its duration (WARN past half the read
  timeout), and a failed fire names the window it failed on and how many windows were committed.
- `runner-ops`: the recurring paused-pipeline report is carried by the heartbeat. Added: operator actions (pause,
  resume, backfill lifecycle, DLQ requeue) are logged with the principal. Added: gate conditions are logged on
  transitions, with values.

## Impact

- **Code:**
  - `adas/AdasContractInterceptor`, `dial/DialClientConfiguration`, `exception/`
    (`AdasTransportException`, `ErrorMessages`)
  - `configuration/DbSchedulerConfiguration`
  - the executors under `scan/`, `class1/`, `sweep/`, `worker/` and `backfill/`
  - the failure handlers, `sync/PipelineSyncService`, `pause/`, `backfill/BackfillService`, the DLQ requeue
  - `web/PipelineAdminController`
  - new `observability/loglevel/`, `observability/trace/` and `observability/status/` packages
- **Resources:** `log4j2-spring.xml` (pins, Root level, pattern, OTLP attributes) and `application.yml` (new
  `observability.*` keys; `dlq.pause-report-interval` removed).
- **Docs:** `docs/configuration.md` gets the new keys, the removed key, the ConfigMap mount and its sync delay, the
  denylist, `LOGGING_LEVEL_*`, and a note that grain and group keys may be user identifiers.
- **Dependencies:** none new. `@LogExecution` uses Spring AOP pointcuts only, with no AspectJ expressions.
- **Deployment:** a chart addition for the dial-extension chart: a ConfigMap with key `logging.levels.json`,
  mounted as a directory at `/app/log-config` (not `subPath`), `optional: true`, not immutable.
- **Cross-repo:** pairs with ADAS `improve-logging-observability` on:
  - the `observability.*` config names and the level-file format
  - the `pipeline_name` MDC spelling
  - the read-timeout pairing that ADAS's single-deadline fix changes
  - ADAS sending the echo on every response it produces, which is what lets the runner treat an echo-less 5xx as
    a gateway error
