## ADDED Requirements

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
