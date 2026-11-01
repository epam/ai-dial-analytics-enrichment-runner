## MODIFIED Requirements

### Requirement: Runner metrics exposed via Prometheus

The runner SHALL expose its own operational metrics on the Prometheus scrape endpoint, covering every lane it
executes rather than only the row path:

- gauges for pipelines cached, **pipelines stopped**, pipelines refused as unexecutable, **pipelines paused and
  pipelines paused by the circuit breaker**, DLQ size, and **queue depth per task name** — a single summed depth
  cannot answer which lane is backed up, which is the only question depth is asked for. The cached count includes
  stopped pipelines, so cached minus stopped is the number the runner is running. With the operator pause gone
  the two paused gauges count the same set, every pause being the breaker's; both SHALL remain, so dashboards and
  alerts written against either keep working;
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

- **GIVEN** one pipeline auto-paused by the breaker
- **WHEN** the Prometheus endpoint is scraped
- **THEN** the paused total and the auto-paused gauge each count it once

#### Scenario: A stopped pipeline is counted apart from the running ones

- **GIVEN** three cached pipelines, one of them disabled in ADAS
- **WHEN** the Prometheus endpoint is scraped
- **THEN** the cached gauge reads 3 and the stopped gauge reads 1, and neither paused gauge counts the stopped one

#### Scenario: A stuck backfill is visible in the metrics

- **GIVEN** a backfill batch that fails repeatedly for a permanent reason
- **WHEN** the Prometheus endpoint is scraped
- **THEN** that pipeline's backfill batch-failure count is rising while its run still reports running

### Requirement: Pipeline-cache introspection

The runner SHALL expose a read-only endpoint listing the pipelines it currently has cached, with each pipeline's
name, trigger kind, enabled flag, and generation. A stopped pipeline is cached, so it SHALL be listed with
`enabled: false`; a pipeline the runner refused or ADAS no longer serves is not cached and is not listed. The
entry carries **one** identifier: the name is the pipeline's identity in the ADAS registry, in this runner's
state tables and in its work-item ids alike, so there is no second id to report.

The entry SHALL report **one** declaration revision as well: the `generation` is what answers "which
declaration is this pipeline running?", and no second version number is reported beside it.

For a group pipeline the runner SHALL additionally expose its **group state** — per group: the group version, the
computed version, whether the last evaluation's input was truncated, and the evaluations charged against the
pipeline's daily ceiling — so "why has this group not been evaluated?" is answerable without database access. The
response SHALL carry identifiers, versions, timestamps and counts only, never member rows or labels.

The runner SHALL also let an operator **re-drive a single group**, re-enqueueing it through the same
deterministic identity the sweep uses, so the override cannot duplicate an item already queued for that
version. This is an explicit operator action and deliberately bypasses the readiness windows; it SHALL NOT
bypass the cost ceiling in effect, because the evaluation it triggers is charged like any other.

Because the re-drive is built at the group's *current* version, re-driving a **clean** group evaluates it at
the version already stamped. That SHALL be treated as an ordinary evaluation rather than as a losing race: its
`computed_at` and `computed_truncated` replace the previous evaluation's, and it SHALL NOT be reported as
superseded.

#### Scenario: Cached pipelines are listed

- **GIVEN** a pipeline is present in the runner's cache
- **WHEN** `GET /v1/pipelines/cache` is requested
- **THEN** the response lists that pipeline with its name, trigger kind, and generation

#### Scenario: A stopped pipeline is listed as stopped

- **GIVEN** a pipeline disabled in ADAS and cached as stopped
- **WHEN** `GET /v1/pipelines/cache` is requested
- **THEN** the response lists it with `enabled: false`

#### Scenario: A group pipeline's state is inspectable

- **GIVEN** a group pipeline with marked groups
- **WHEN** an operator requests that pipeline's group state
- **THEN** each group is reported with its version, computed version, truncation flag and evaluation count,
  and no member content

#### Scenario: An operator re-drives one group

- **GIVEN** a group that the sweep is not currently enqueueing
- **WHEN** an operator requeues that group
- **THEN** one group work item exists for its current version, and requeueing again adds none

### Requirement: Single-replica topology that tolerates a rolling update

The runner SHALL be deployed as a single active replica, and SHALL nonetheless tolerate the transient overlap
of two instances that a rolling update produces, so a deploy costs no restart gap.

Tolerance is a property of where the work is serialized, not of a lock the runner takes: every unit of work is
a db-scheduler execution claimed by exactly one node, the per-pipeline scan is a recurring task with one instance
per pipeline, and every enqueue SHALL be idempotent on a deterministic instance id (pipeline name, the
pipeline's `generation`, and the grain or group version), so two instances racing to enqueue the same work
collapse to one row. The loops that are not db-scheduler executions — pipeline sync, the group readiness sweep,
the DLQ and group-state sweeps, the metrics gauge refresh — SHALL likewise be idempotent under concurrent
execution.

Deriving the id from `generation` bounds when that collapse holds: two overlapping instances collapse only
while both hold the **same** cached generation for a pipeline, and every deterministic id changes on **any**
declaration edit — prompt, expressions, `filter`, trigger or `advanced`. An overlap that straddles a declaration
edit, with one instance's cache refreshed and the other's not, therefore enqueues the same work twice, once per
generation; it is bounded by the overlap being transient and by the enrichment target's ReplacingMergeTree, not
by `scheduleIfNotExists`.

What is **not** shared SHALL be documented rather than implied, because it is what bounds the overlap to a
transient one: the DIAL rate limiter, the pipeline-pause registry (which holds breaker pauses only) and the DLQ
breaker counters are per-process, so a second instance splits or doubles them. Sustained N>1 is therefore
unsupported until breaker state is shared and the rate limiter is shared. An operator's stop is not among them: it
is ADAS's `enabled` flag, durable in the registry and read by every instance from the same feed.

#### Scenario: Two instances overlap during a rolling update

- **GIVEN** an outgoing and an incoming instance running concurrently against the same PostgreSQL state
- **WHEN** both reconcile pipelines and sweep groups in the same interval
- **THEN** each work item is executed once, duplicate enqueues collapse on their deterministic instance ids,
  and no scan cursor is advanced past unprocessed rows

#### Scenario: A paused pipeline does not survive a replacement instance

- **GIVEN** a pipeline auto-paused by the DLQ circuit breaker
- **WHEN** the instance holding that pause is replaced
- **THEN** the replacement starts with the pipeline unpaused, since pause state is in-memory — documented as a
  consequence of the topology rather than treated as durable state

#### Scenario: A stopped pipeline stays stopped across a replacement instance

- **GIVEN** a pipeline disabled in ADAS and cached as stopped
- **WHEN** the instance is replaced
- **THEN** the replacement's first sync caches it as stopped, and none of its work runs

### Requirement: A paused pipeline says why, since when, and until when

The paused-pipeline listing SHALL report, for each paused pipeline, its **origin** (always `breaker`, the
circuit breaker's pause being the only one the runner holds), the **reason** recorded when it was paused, the
**instant** the pause began, and — for a pause that expires — the instant it resumes. A stopped pipeline is not
paused and SHALL NOT be listed: its stop is ADAS's `enabled: false`, which the cache listing and the runtime view
report.

A listing of bare names cannot answer the question it is opened to answer. A list of one name tells an
operator only what they already suspected and nothing about why the breaker tripped or when it lets go. The
origin stays on the wire although it has one value, so a client written against the listing keeps reading it.

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

The health endpoint SHALL NOT report a paused or stopped pipeline as unhealthy. A pause is an operational fact
and a stop is deliberate, while the runner's readiness group and this deployment's liveness wiring would turn
a DOWN-capable contributor into a pod taken out of service or restarted.

#### Scenario: The listing distinguishes a decision from a stuck breaker

- **GIVEN** one pipeline auto-paused by the breaker and one disabled in ADAS
- **WHEN** the paused pipelines are listed
- **THEN** only the auto-paused one is listed, with origin `breaker`, its reason, when it was paused and when it
  resumes

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

### Requirement: Operator actions are logged with the acting principal

The runner SHALL log at INFO every operator action that changes what it does: lifting a breaker pause early,
backfill start, pause, resume and cancel, and DLQ requeue. Each line SHALL name the action, the pipeline, the
affected run or item where there is one, and the acting principal. When no principal exists (`SECURITY_MODE=none`)
the line SHALL carry `principal=-` rather than omitting the field. The line SHALL NOT carry a request body.

An operator decision and a machine decision call for opposite responses (see the paused-pipeline requirement), and
a log-only reader can distinguish them only if the decision announces who took it.

#### Scenario: An early resume names who resumed

- **GIVEN** an authenticated operator and a pipeline the breaker has paused
- **WHEN** they lift the pause through the admin endpoint
- **THEN** one INFO line names the action, the pipeline and the operator's principal

#### Scenario: An unauthenticated deployment still logs the action

- **GIVEN** `SECURITY_MODE=none`
- **WHEN** a backfill is cancelled
- **THEN** its line names the action, the pipeline and the run, with `principal=-`

### Requirement: Per-pipeline runtime view across all lanes

The runner SHALL serve `GET /v1/pipelines/{name}/runtime`, a read-only view of what one cached pipeline is doing
right now, in a single shape for the row, group and Class-1 lanes.

The response SHALL always carry the following, for every lane:
- `pipeline_name`, `generation`, and `lane` (`row`, `group` or `class1`, spelled as the status heartbeat spells it);
- `status`: `state`, one of `stopped`, `active`, `paused`, `held`, `over_budget` or `backpressured`. `stopped` means
  the pipeline is disabled in ADAS. For a pause, it also carries the pause's `origin` (always `breaker`), `reason`,
  `since` and, for an expiring pause, `resumes_at`;
- `schedule`: `last_scan_at`, `next_run_at`, `running_now`, `consecutive_failures`, `last_error` and
  `last_error_at`;
- `progress`: `lag_seconds`, `has_more`, `caught_up_at` and `last_write_at`.

`schedule.last_scan_at` SHALL be the instant a live fire last committed a page or window. `next_run_at` SHALL be
omitted while `running_now` is true. `consecutive_failures` SHALL count the consecutive fires that failed. A fire
skipped by a gate completes normally and resets it to zero, so it MAY be zero while `last_error` still names the
last fire that ran. For the group lane, `progress` SHALL describe the dirty-mark scan, not evaluation.

For the row and group lanes, the response SHALL also carry:
- `queue`: work items `computing` and `awaiting_write`. These are the live work items backpressure counts, and a
  backfill run's own chunks are not among them;
- `spend_today`: successful `calls`, `tokens` and `failed_calls` for the current UTC day;
- `failures`: the live lane's `total` and `requeueable_total`, equal to what the DLQ listing reports for this
  pipeline and lane `live`. The dead letters themselves are read from that listing, not from this view.

For the group lane only, the response SHALL also carry `groups`: `pending`, `ready`, `waiting_idle` and
`ceiling_blocked`.

A section that does not apply to the pipeline's lane SHALL be omitted rather than filled with zeros. A field that
applies but that the runner does not currently know SHALL be omitted rather than estimated, as the runner omits
every null property. Since the section's presence already says it applies, a field missing from a present section
means unknown, unless this requirement gives the absence a meaning: not paused, a pause that does not expire, no
error, or running now.

The status SHALL be derived by the same logic as the status heartbeat, so the two never disagree about one pipeline
at one instant:
- Precedence is stopped, then paused, then held, then over_budget, then backpressured, then active. Stopped comes
  first because it is the one state a user chose: a stopped pipeline that the breaker also paused runs nothing
  whatever the breaker decides, and the pause is still listed by the paused-pipeline listing.
- A Class-1 pipeline is never over_budget or backpressured.

The view SHALL NOT report the heartbeat's stall flag. Stalled is a heuristic, and the view reports state, not a
health verdict.

`queue` SHALL be reported alongside lag for the row and group lanes. The scan advances the cursor when work is
enqueued, not when it is written, so a lag of zero does not mean that the pipeline's rows are written.

For a group pipeline, `pending` SHALL count the dirty groups. `ready` and `ceiling_blocked` SHALL be counted with the
same readiness predicate the sweep selects with. `ready` SHALL count the pending groups that are ready and within
the cost ceiling, and `ceiling_blocked` those that are ready but over it. `waiting_idle` SHALL count the pending
groups that are not yet ready. So `pending` SHALL equal `ready + ceiling_blocked + waiting_idle`. A group whose
work is already queued is still counted as ready. A poison group that dead-lettered is stamped computed, so it is not
counted as pending. It SHALL be counted only under `failures`. A group archived after exhausting its retries stays
pending and is counted under both.

A declaration the runner cannot parse, such as a malformed readiness window, SHALL omit only the sections that
depend on it, and SHALL NOT fail the read. Such a declaration already fails the pipeline's own fires and sweeps,
and the view is what an operator opens to find out why.

A cache that has never synced SHALL be answered with the existing 503 `pipeline_cache_cold`, before any lookup. A
pipeline that is not in a synced cache SHALL be answered with 404 `not_found`. A stopped pipeline is cached, so
it SHALL be answered with 200 and its full view, its persisted progress and queue included: a stop keeps that
state, and the view is where an operator checks what a restart will continue from.

#### Scenario: A row pipeline reports its queue next to its lag

- **GIVEN** a row pipeline whose scan has caught up while chunks are still queued for the model
- **WHEN** its runtime view is read
- **THEN** `progress.lag_seconds` is 0, `progress.has_more` is false and `queue.computing` is above zero
- **AND** `groups` is absent

#### Scenario: A group pipeline reports readiness counts

- **GIVEN** a group pipeline declaring a daily cost ceiling, with three dirty groups: one ready, one still inside
  its idle window, and one ready but at its daily cost ceiling
- **WHEN** its runtime view is read
- **THEN** `groups.pending` is 3, `groups.ready` is 1, `groups.waiting_idle` is 1 and `groups.ceiling_blocked` is 1

#### Scenario: A Class-1 pipeline reports its failure without a DLQ

- **GIVEN** a Class-1 pipeline whose last two materialize fires failed
- **WHEN** its runtime view is read
- **THEN** `schedule.consecutive_failures` is 2 and `schedule.last_error` names the failure
- **AND** `queue`, `spend_today`, `failures` and `groups` are absent

#### Scenario: A successful fire clears the last error

- **GIVEN** a pipeline whose previous fire failed
- **WHEN** its next fire completes and its runtime view is read
- **THEN** `schedule.last_error` is absent and `schedule.consecutive_failures` is 0

#### Scenario: Status agrees with the heartbeat

- **GIVEN** a row pipeline over its daily spend budget
- **WHEN** its runtime view is read and the status heartbeat runs
- **THEN** the view's `status.state` is `over_budget` and the heartbeat line reads `over budget`

#### Scenario: A stalled pipeline is not flagged in the view

- **GIVEN** a row pipeline that the status heartbeat reports as stalled
- **WHEN** its runtime view is read
- **THEN** `status` carries its `state` and no `stalled` property

#### Scenario: A pause is reported with its origin

- **GIVEN** a pipeline auto-paused by the circuit breaker, with breaker pauses set to expire
- **WHEN** its runtime view is read
- **THEN** `status.state` is `paused`, `status.origin` is `breaker` and `status.resumes_at` is set

#### Scenario: A stopped pipeline reports its state rather than not-found

- **GIVEN** a row pipeline disabled in ADAS and cached as stopped, with chunks held in its queue
- **WHEN** its runtime view is read
- **THEN** the response is 200, `status.state` is `stopped`, and `queue` counts the held chunks

#### Scenario: Failure totals match the DLQ listing

- **GIVEN** a row pipeline with live dead letters, some of them requeueable
- **WHEN** its runtime view is read
- **THEN** `failures.total` and `failures.requeueable_total` equal the `total` and `requeueable_total` that
  `GET /v1/dlq` reports for that pipeline and lane `live`

#### Scenario: An unparseable readiness window omits only the group counts

- **GIVEN** a group pipeline whose `ready_when.idle` cannot be parsed
- **WHEN** its runtime view is read
- **THEN** the response is 200, `groups` is absent, and `status`, `schedule` and `queue` are present

#### Scenario: An uncached pipeline is not found

- **WHEN** the runtime view of a pipeline absent from a synced cache is read
- **THEN** the response is 404 with error code `not_found`

#### Scenario: A cold cache is not reported as a missing pipeline

- **GIVEN** a runner that has not completed its first pipeline sync
- **WHEN** the runtime view of any pipeline is read
- **THEN** the response is 503 with error code `pipeline_cache_cold`
