## MODIFIED Requirements

### Requirement: Runner metrics exposed via Prometheus

The runner SHALL expose its own operational metrics on the Prometheus scrape endpoint, covering every lane it
executes rather than only the row path:

- gauges for pipelines cached, pipelines refused as unexecutable, **pipelines paused and pipelines paused by
  the circuit breaker**, DLQ size, and **queue depth per task name** — a single summed depth cannot answer
  which lane is backed up, which is the only question depth is asked for; and the paused total cannot carry
  an alert on its own, because an operator pause is a legitimate steady state while a breaker pause, which
  expires by itself, is never one;
- per-pipeline gauges for scan lag and **current-day spend**, so an approaching budget is visible before it pauses
  a pipeline;
- group gauges for active-group count, dirty backlog, and groups declined on their cost ceiling — the signals
  that distinguish a group path that is stuck from one that is quiet;
- a timer for DIAL call latency with success and error counters, plus a **distinct rate-limit (429) counter**;
  a 429 SHALL also remain in the error total, since it is a failed call and removing it would make the error
  rate under-report;
- timers for the readiness sweep's duration and counters for its enqueue rate and for group-worker throughput;
- Class-1 counters for **rows written per pipeline** and tick failures, which are that lane's only throughput and
  failure signals since it has no queue and no dead-letter queue — a summed total cannot say which pipeline stopped
  writing;
- a **cursor-stall counter per lane**, for a drain that halted because the cursor did not advance: that halt
  raises no error, so without it a pipeline can stop making progress with every other signal reading healthy;
- a **backfill batch-failure counter per pipeline**, for the one lane that retries without an attempt budget:
  a batch that can never succeed re-issues indefinitely while the run reads `running` and holds that
  pipeline's conflict, and none of the counters above fires for an ADAS rejection or a serialization
  failure. A held batch is not counted, being a condition waited out rather than a failure.

#### Scenario: Runner gauges are scrapeable

- **WHEN** `GET /actuator/prometheus` is requested
- **THEN** the response includes the runner's own metrics (e.g. `runner_pipelines_cached`)

#### Scenario: Queue depth distinguishes the lanes

- **GIVEN** work items pending for more than one task
- **WHEN** the Prometheus endpoint is scraped
- **THEN** depth is reported per task name rather than as a single total

#### Scenario: Rate-limit pressure is distinguishable from model failure

- **WHEN** a DIAL call is rejected with 429
- **THEN** a rate-limit counter increments and the call is also counted as an error

#### Scenario: A breaker pause is countable on its own

- **GIVEN** one pipeline paused by an operator and one auto-paused by the breaker
- **WHEN** the Prometheus endpoint is scraped
- **THEN** the paused total counts both and the auto-paused gauge counts only the second

#### Scenario: A stuck backfill is visible in the metrics

- **GIVEN** a backfill batch that fails repeatedly for a permanent reason
- **WHEN** the Prometheus endpoint is scraped
- **THEN** that pipeline's backfill batch-failure count is rising while its run still reports running
