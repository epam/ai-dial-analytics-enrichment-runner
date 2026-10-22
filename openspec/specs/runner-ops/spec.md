# runner-ops Specification

## Purpose
The operational surface: the metrics an operator reads to tell working from stuck, the readiness gate, the
introspection endpoints for cached pipelines and group state, the per-group override, and the logging contract
— including what must never be logged.

## Requirements

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

### Requirement: Readiness gates on a successful pipeline sync

The runner's readiness SHALL be UP only after at least one successful ADAS pipeline sync has completed
(in addition to PostgreSQL reachability); liveness SHALL reflect process health independently. The
readiness contributor SHALL report DOWN until the first successful sync.

#### Scenario: Readiness turns UP after the first sync

- **GIVEN** the runner has completed a successful pipeline sync
- **WHEN** `GET /actuator/health/readiness` is requested
- **THEN** the status is UP

### Requirement: Pipeline-cache introspection

The runner SHALL expose a read-only endpoint listing the pipelines it currently has cached, with each
pipeline's name, trigger kind, enabled flag, and generation. The entry carries **one** identifier: the name
is the pipeline's identity in the ADAS registry, in this runner's state tables and in its work-item ids
alike, so there is no second id to report.

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

#### Scenario: A group pipeline's state is inspectable

- **GIVEN** a group pipeline with marked groups
- **WHEN** an operator requests that pipeline's group state
- **THEN** each group is reported with its version, computed version, truncation flag and evaluation count,
  and no member content

#### Scenario: An operator re-drives one group

- **GIVEN** a group that the sweep is not currently enqueueing
- **WHEN** an operator requeues that group
- **THEN** one group work item exists for its current version, and requeueing again adds none

### Requirement: Group state listing pagination, ordering and count

The group-state listing (`GET /v1/pipelines/{name}/groups`) SHALL page by keyset in a total order over
`last_activity_at`, then `group_key`, in one of two directions. The `order` query parameter selects it:
- `oldest`, the default, is ascending: the groups that have been quiet longest come first.
- `newest` is descending: the most recently active groups come first.

`order` SHALL be matched case-insensitively, and a blank value SHALL count as absent. Any other value SHALL be refused
with HTTP 400.

A page SHALL hold at most `limit` groups; `limit` SHALL default to `100` and be clamped to `1..500`. The response
SHALL carry:
- `groups`
- `has_more`: whether further groups exist past this page
- `next_cursor`: an opaque string to pass back as the `cursor` query parameter for the next page. It is absent when
  `has_more` is false (the API omits null fields).

A request without `cursor`, or with a blank one, SHALL return the first page.

A `cursor` the listing did not issue SHALL be refused with HTTP 400 and error code `invalid_cursor` rather than
answered with the first page. That covers a cursor that is undecodable, of an unknown version, or has malformed or
out-of-range fields. A cursor issued under one `order` and passed with the other SHALL be refused the same way:
under the other direction, "past this group" names a different set of groups.

A walk SHALL return every group exactly once, provided the group exists for the whole walk and its
`last_activity_at` does not change during it. Moving `last_activity_at` forward is ordinary group activity, and it
moves the group's position:
- under `oldest`, a group already returned can appear again;
- under `newest`, a group not yet returned can be passed over.

A group evicted during the walk SHALL simply not be returned.

Every page SHALL also carry `total`, the number of groups the pipeline has. `total` SHALL ignore the cursor and SHALL
be read on the same snapshot as the page's groups.

#### Scenario: Walking the listing to exhaustion

- **GIVEN** a group pipeline with five groups, two of which share one `last_activity_at`
- **WHEN** the caller lists with `limit=2`, following `next_cursor` each time
- **THEN** the pages hold 2, 2 and 1 groups, oldest activity first
- **AND** `has_more` is true, true and false, and the last page carries no `next_cursor`
- **AND** every group appears exactly once
- **AND** every page reports `total` 5

#### Scenario: Newest first

- **GIVEN** a group pipeline with several groups of distinct activity times
- **WHEN** the caller lists with `order=newest`
- **THEN** the groups are returned most recent `last_activity_at` first

#### Scenario: Oldest first is the default

- **GIVEN** a group pipeline with several groups of distinct activity times
- **WHEN** the caller lists without `order`
- **THEN** the groups are returned least recent `last_activity_at` first, as with `order=oldest`

#### Scenario: A cursor replayed under the other order is refused

- **GIVEN** a `next_cursor` from a listing with `order=oldest`
- **WHEN** the caller lists with that cursor and `order=newest`
- **THEN** the system responds HTTP 400 with error code `invalid_cursor`

#### Scenario: A cursor the listing did not issue is refused

- **WHEN** the caller lists a pipeline's groups with a `cursor` the listing did not issue
- **THEN** the system responds HTTP 400 with error code `invalid_cursor`

### Requirement: A group key containing a slash is addressable

The per-group endpoints SHALL accept a group key containing `/` when that slash is percent-encoded as `%2F` in the
`{groupKey}` path segment. The runner SHALL bind the decoded key, so `a%2Fb` addresses the group `a/b`. Group keys
are arbitrary `group_by` values from the source table, so a listed key SHALL be addressable for requeue unless it is
one of the exceptions below.

Route and authorization matching SHALL see an encoded slash as part of one path segment, so an encoded slash SHALL
NOT change which route or which authorization rule a request matches. The request firewall SHALL keep all of its
default checks except the one refusing an encoded slash, and SHALL additionally refuse a request whose
percent-decoded request URI contains a `.` or `..` segment. In particular, an encoded slash that would climb out of
its segment (`x%2F..%2Fcache`) and a double-encoded slash (`%252F`) SHALL be refused before routing, with HTTP 400.
In `oidc` mode that refusal's error dispatch carries no credential, so the client sees HTTP 401 instead; it is never
routed.

These keys SHALL remain unaddressable however they are encoded, because the firewall or the servlet container refuses
the request with HTTP 400 before routing:
- the empty key, and the keys `.` and `..`;
- a key with a `.` or `..` segment between slashes, such as `a/../b`, `./a` or `a/.`;
- a key containing `//`, `%`, `;` or `\`;
- a key containing a line feed, a carriage return, U+2028 or U+2029.

A slash at the start or end of a key, or a key that is `/` alone, SHALL be addressable. A `.` in the key SHALL be sent
literally, since the firewall refuses an encoded period (`%2E`).

#### Scenario: A group whose key contains a slash is re-driven

- **GIVEN** a group pipeline with a group whose key is `team/a`
- **WHEN** an operator requests `POST /v1/pipelines/{name}/groups/team%2Fa/requeue`
- **THEN** the system responds HTTP 202
- **AND** one group work item exists for group `team/a` at its current version

#### Scenario: A listed slash key round-trips to the requeue endpoint

- **GIVEN** a group pipeline with a group whose key is `team/a`
- **WHEN** the operator lists the pipeline's groups
- **THEN** that group is reported with `group_key` `team/a`
- **AND** percent-encoding that value as a path segment addresses the same group for requeue

#### Scenario: An encoded slash cannot escape its segment

- **GIVEN** the runner in `none` security mode
- **WHEN** an operator requests a requeue whose `{groupKey}` segment is `x%2F..%2F..%2Fcache` or `team%252Fa`
- **THEN** the system responds HTTP 400 before routing
- **AND** no group work item is enqueued

#### Scenario: A key outside the addressable set is refused rather than routed

- **GIVEN** a group pipeline with a group whose key is `a/../b`
- **WHEN** an operator requests a requeue with that key percent-encoded as `a%2F..%2Fb`
- **THEN** the system responds HTTP 400 before routing
- **AND** the group is still reported by the listing

### Requirement: Structured chunk-boundary logging

The runner SHALL log at work-item boundaries with correlation context in the MDC — pipeline name and item id for
every grain, plus the group key for a group item — and SHALL NOT log full request/response payloads at INFO.

The logging contract SHALL also state what must never be logged **at any level**: assembled group input,
member rows, and rendered prompts. Unlike the INFO restriction, this is not a verbosity choice but a data
boundary, so it holds for `debug` and `trace` too — which is where a careless future line would otherwise
land. It SHALL be verified by a test rather than left to review.

#### Scenario: A chunk logs a correlated boundary line

- **WHEN** the worker begins processing a chunk
- **THEN** it logs a boundary line carrying the pipeline name and chunk id, without full payloads

#### Scenario: A group item logs its group key as correlation

- **WHEN** the group worker begins processing a group item
- **THEN** its log records carry the pipeline name, item id and group key in the MDC

#### Scenario: Source content is absent from logs at every level

- **GIVEN** logging configured down to its most verbose level
- **WHEN** a group is evaluated
- **THEN** no record contains the members' content

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
transient one: the DIAL rate limiter, the pipeline-pause registry (operator and breaker-driven) and the DLQ
breaker counters are per-process, so a second instance splits or doubles them. Sustained N>1 is therefore
unsupported until pause is durable and the rate limiter is shared.

#### Scenario: Two instances overlap during a rolling update

- **GIVEN** an outgoing and an incoming instance running concurrently against the same PostgreSQL state
- **WHEN** both reconcile pipelines and sweep groups in the same interval
- **THEN** each work item is executed once, duplicate enqueues collapse on their deterministic instance ids,
  and no scan cursor is advanced past unprocessed rows

#### Scenario: A paused pipeline does not survive a replacement instance

- **GIVEN** a pipeline paused by an operator or auto-paused by the DLQ circuit breaker
- **WHEN** the instance holding that pause is replaced
- **THEN** the replacement starts with the pipeline unpaused, since pause state is in-memory — documented as a
  consequence of the topology rather than treated as durable state

### Requirement: A paused pipeline says why, since when, and until when

The paused-pipeline listing SHALL report, for each paused pipeline, its **origin** (operator or circuit
breaker), the **reason** recorded when it was paused, the **instant** the pause began, and — for a pause
that expires — the instant it resumes.

A listing of bare names cannot answer the question it is opened to answer. A list of one name tells an
operator only what they already suspected and nothing about whether the pause was a decision somebody had
taken or a breaker that had failed to let go. Those two call for opposite responses.

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
sometimes deliberate, while the runner's readiness group and this deployment's liveness wiring would turn
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

### Requirement: Per-pipeline runtime view across all lanes

The runner SHALL serve `GET /v1/pipelines/{name}/runtime`, a read-only view of what one cached pipeline is doing
right now, in a single shape for the row, group and Class-1 lanes.

The response SHALL always carry the following, for every lane:
- `pipeline_name`, `generation`, and `lane` (`row`, `group` or `class1`, spelled as the status heartbeat spells it);
- `status`: `state`, one of `active`, `paused`, `held`, `over_budget` or `backpressured`. For a pause, it also
  carries the pause's `origin` (`operator` or `breaker`), `reason`, `since` and, for an expiring pause, `resumes_at`;
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
- Precedence is paused, then held, then over_budget, then backpressured, then active.
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
pipeline that is not in a synced cache SHALL be answered with 404 `not_found`.

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

### Requirement: Runtime view fields held in memory are reported as unknown after a restart

The runtime view SHALL read `schedule.last_error`, `schedule.last_error_at`, `progress.has_more` and
`progress.caught_up_at` from process memory. They are recorded once per fire that runs, and a fire skipped by a gate
records nothing. A backfill run's fires do not record them. A fire that reads no page or window SHALL NOT read as
caught up. After a restart these fields SHALL be omitted until the pipeline's next fire records them. They SHALL NOT
be estimated.

`progress.last_write_at` SHALL also be read from process memory, but it is recorded on every write, a backfill run's
writes included, not once per fire. After a restart it SHALL be omitted until the pipeline next writes.

There is one exception, so that a failing pipeline is never shown as one whose error was cleared. Memory can miss a
failure in two ways: after a restart, before the next fire, and when a failure escapes the executor's own handling (a
JVM error, or a failure outside the executor). Whenever the recorded outcome names no error, the view SHALL fall
back to the durable record of the pipeline's recurring execution:
- When its consecutive failure count is above zero, `schedule.last_error_at` SHALL be that execution's last failure
  instant, and `schedule.last_error` SHALL be omitted, because the message is not stored.
- When the count is zero, both SHALL be omitted.

When the recorded outcome names an error, memory is the source for both fields.

`schedule.last_scan_at`, `schedule.next_run_at`, `schedule.running_now`, `schedule.consecutive_failures`,
`progress.lag_seconds`, `queue`, `groups`, `spend_today` and `failures` SHALL be read from durable state. A restart
SHALL therefore not blank them.

This relies on the runner's single-replica topology: the process that serves the read is the process that ran the
fire.

#### Scenario: A fresh process reports unknown, not caught up

- **GIVEN** a runner that restarted and whose pipeline has not fired since
- **WHEN** that pipeline's runtime view is read
- **THEN** `progress.has_more`, `progress.caught_up_at` and `schedule.last_error` are absent
- **AND** `progress.lag_seconds` and `schedule.consecutive_failures` carry their persisted values

#### Scenario: A failing pipeline keeps its failure time across a restart

- **GIVEN** a Class-1 pipeline whose last two fires failed, and a runner that restarted before its next fire
- **WHEN** its runtime view is read
- **THEN** `schedule.consecutive_failures` is 2, `schedule.last_error_at` is the last failure's instant, and
  `schedule.last_error` is absent

#### Scenario: A failure the outcome missed keeps its failure time

- **GIVEN** a pipeline whose last recorded outcome is a success, and whose recurring execution has since failed once
  with an error no executor caught
- **WHEN** its runtime view is read
- **THEN** `schedule.consecutive_failures` is 1, `schedule.last_error_at` is that failure's instant, and
  `schedule.last_error` is absent

#### Scenario: A gated fire records no outcome

- **GIVEN** a pipeline whose last fire was skipped because it is paused
- **WHEN** its runtime view is read
- **THEN** `progress.caught_up_at` and `schedule.last_error` are those of the last fire that ran

### Requirement: Runtime view carries no source content

The runtime view SHALL carry only identifiers, counts, timestamps, pause reasons and classified error messages. It
SHALL NOT carry source rows, member rows, model responses, labels or dead-letter items.

`schedule.last_error` SHALL be the same classified message the runner logs for that failure, never the raw
exception text of a call that may echo source content. Dead letters SHALL be reported only as the `failures` counts.

#### Scenario: A failing fire is reported by its classified message

- **GIVEN** a row pipeline whose last fire failed on an ADAS rejection whose response body quotes a source row
- **WHEN** its runtime view is read
- **THEN** `schedule.last_error` is the classified message the runner logged, naming the HTTP status and the ADAS
  error code, and does not carry the response body

#### Scenario: Dead letters are reported as counts only

- **GIVEN** a row pipeline whose newest dead letter has a stored payload and raw model response
- **WHEN** its runtime view is read
- **THEN** `failures` carries only `total` and `requeueable_total`, and the response carries neither the payload,
  the raw response, nor any field of the item

### Requirement: The paused-pipeline listing uses the snake_case wire format

The paused-pipeline listing SHALL name its properties in snake_case: `pipeline_name`, `origin`, `reason`, `since`
and `resumes_at`. Every other `/v1` response, the runtime view's `status` included, already uses this convention.
`origin` SHALL be written lowercase (`operator` or `breaker`), as it already is.

The listing was the one `/v1` response that emitted Java property names (`pipelineName`, `resumesAt`). As a result,
the field the runbook documented as `resumes_at` did not exist on the wire, and one pause read differently from the
two endpoints that report it.

#### Scenario: A breaker pause is listed in snake_case

- **GIVEN** a pipeline auto-paused by the circuit breaker
- **WHEN** `GET /v1/pipelines/paused` is requested
- **THEN** its entry carries `pipeline_name`, `origin` `breaker`, `reason`, `since` and `resumes_at`
- **AND** it carries no `pipelineName` or `resumesAt` property
