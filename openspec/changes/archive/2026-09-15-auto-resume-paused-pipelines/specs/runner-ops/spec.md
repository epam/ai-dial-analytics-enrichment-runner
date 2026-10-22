## ADDED Requirements

### Requirement: A paused pipeline says why, since when, and until when

The paused-pipeline listing SHALL report, for each paused pipeline, its **origin** (operator or circuit
breaker), the **reason** recorded when it was paused, the **instant** the pause began, and — for a pause
that expires — the instant it resumes.

A listing of bare names cannot answer the question it is opened to answer. For a stuck auto-pause it
returns one name, which tells an operator only what they already suspect and nothing about whether the pause was a decision somebody had taken or a breaker that
had failed to let go. Those two call for opposite responses.

The runner SHALL additionally report the condition **recurrently** — a periodic warning naming every
paused pipeline with its origin and how long it has been paused — not only at the moment it arises. A
pause was previously announced once, on the line that created it, after which every subsequent skipped
fire logged at debug; an operator who arrived after that line had scrolled had no way to find the
condition at all. An event that is logged once and a condition that persists are different things and
SHALL be reported differently.

The health endpoint SHALL NOT report a paused pipeline as unhealthy. A pause is an operational fact and is
sometimes deliberate, while the runner's readiness group and a typical liveness wiring would turn
a DOWN-capable contributor into a pod taken out of service or restarted.

#### Scenario: The listing distinguishes a decision from a stuck breaker

- **GIVEN** one pipeline paused by an operator and one auto-paused by the breaker
- **WHEN** the paused pipelines are listed
- **THEN** each reports its origin, its reason and when it was paused, and only the auto-paused one reports
  a resume time

#### Scenario: The condition keeps announcing itself

- **GIVEN** a pipeline that has been paused for far longer than the interval at which the runner reports
- **WHEN** an operator reads the log from any point after the pause began
- **THEN** the pipeline is named as paused, with its origin and its age

#### Scenario: A pause does not take the pod out of service

- **GIVEN** any paused pipeline
- **WHEN** the health endpoint is read
- **THEN** readiness and liveness are unaffected

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
  raises no error, so without it a pipeline can stop making progress with every other signal reading healthy.

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
