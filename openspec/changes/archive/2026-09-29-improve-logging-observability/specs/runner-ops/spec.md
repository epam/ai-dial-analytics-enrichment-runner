## MODIFIED Requirements

### Requirement: A paused pipeline says why, since when, and until when

The paused-pipeline listing SHALL report, for each paused pipeline, its **origin** (operator or circuit
breaker), the **reason** recorded when it was paused, the **instant** the pause began, and — for a pause
that expires — the instant it resumes.

A listing of bare names cannot answer the question it is opened to answer. A listing that returns one name
tells an operator only what they already suspected, and nothing about whether the pause was a decision
somebody has taken or a breaker that has failed to let go. Those two call for opposite responses.

The runner SHALL additionally report the condition **recurrently** — a periodic warning naming every
paused pipeline with its origin and how long it has been paused — not only at the moment it arises. A
pause was previously announced once, on the line that created it, after which every subsequent skipped
fire logged at debug; an operator who arrived after that line had scrolled had no way to find the
condition at all. An event that is logged once and a condition that persists are different things and
SHALL be reported differently.

The recurring report SHALL be carried by the pipeline status heartbeat (see the `observability`
capability): a paused pipeline's heartbeat line is logged at WARN and carries its origin, its reason and its
age, at `STATUS_REPORT_INTERVAL`. A separate paused-pipeline reporter and its own interval
(`DLQ_PAUSE_REPORT_INTERVAL`) SHALL NOT exist alongside it, so one condition is not announced by two lines
on two clocks.

The health endpoint SHALL NOT report a paused pipeline as unhealthy. A pause is an operational fact and is
sometimes deliberate, while the runner's readiness group and a deployment's liveness wiring would turn
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

#### Scenario: A pause is reported by exactly one recurring line

- **GIVEN** a paused pipeline
- **WHEN** one status-report interval elapses
- **THEN** exactly one WARN names it as paused, and that line is its heartbeat line

## ADDED Requirements

### Requirement: Operator actions are logged with the acting principal

The runner SHALL log at INFO every operator action that changes what it does: pipeline pause and resume,
backfill start, pause, resume and cancel, and DLQ requeue. Each line SHALL name the action, the pipeline, the
affected run or item where there is one, and the acting principal. When no principal exists (`SECURITY_MODE=none`)
the line SHALL carry `principal=-` rather than omitting the field. The line SHALL NOT carry a request body.

An operator decision and a machine decision call for opposite responses (see the paused-pipeline requirement), and
a log-only reader can distinguish them only if the decision announces who took it.

#### Scenario: A pause names who paused

- **GIVEN** an authenticated operator
- **WHEN** they pause a pipeline through the admin endpoint
- **THEN** one INFO line names the action, the pipeline and the operator's principal

#### Scenario: An unauthenticated deployment still logs the action

- **GIVEN** `SECURITY_MODE=none`
- **WHEN** a backfill is cancelled
- **THEN** its line names the action, the pipeline and the run, with `principal=-`

### Requirement: Gate conditions are logged on transition with their values

The runner SHALL log the spend gate, backpressure and the held-by-incompatible-ADAS condition when they
**change**, not on every fire. Each line SHALL carry the values that decided it: calls and tokens against
their limits for the spend gate, and in-flight work against `scan.max-inflight` for backpressure. For a held
work item, the runner SHALL log the first hold, and then SHALL log at most one count of held items per five
minutes while the condition lasts. The group-sweep pause skip, which the sweep evaluates every pass, SHALL be logged
at INFO when a pause begins and ends, and otherwise at DEBUG.

A condition re-logged every tick buries the transition that started it. The persistent state is reported by the
heartbeat instead (see the `observability` capability).

#### Scenario: A closing spend gate logs once

- **GIVEN** a pipeline whose daily calls reach their limit
- **WHEN** several scan fires follow
- **THEN** one line reports the gate closing with calls and tokens against their limits, and later fires log
  nothing about it until it opens

#### Scenario: Held work is summarised, not repeated per item

- **GIVEN** ADAS classified incompatible and many work items deferred
- **WHEN** ten minutes pass
- **THEN** the log carries the first hold and at most two count lines, not one line per item per delay
