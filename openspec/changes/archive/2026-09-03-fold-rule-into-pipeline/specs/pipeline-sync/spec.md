## ADDED Requirements

### Requirement: Pipeline registry sync

The runner SHALL periodically poll the ADAS pipeline registry and maintain an in-memory cache of the
enabled `enrich`-kind pipelines keyed by pipeline **name**. Because the feed carries no ETag/304,
change-detection SHALL be done client-side using each pipeline's `generation`. A failed poll SHALL leave
the current cache and recurring tasks unchanged (no partial teardown). The cache SHALL be published as a
single atomic snapshot so a concurrent reader never observes a half-applied reconcile.

The poll SHALL request `GET /v1/pipelines?kind=enrich&enabled=true` and SHALL send **both** filters
explicitly. `kind=enrich` is load-bearing rather than an optimisation: the registry now holds `aggregate`
pipelines too, which ADAS executes itself and which carry none of the fields the runner reads, so an
unfiltered feed would offer the runner work it must not schedule and could not model. `enabled=true` is
sent for the same reason it always was: unfiltered, that endpoint is ADAS's operator listing of the whole
registry and carries disabled pipelines too. The runner SHALL nonetheless still skip a pipeline whose
`enabled` is false when admitting the fetched list, so reconciliation is correct for any caller and does
not depend on which filter the fetch happened to send.

The feed's response body SHALL be read as the object envelope `{"pipelines": [...]}`, not as a bare JSON
array. A blank body SHALL be read as an empty list, as it is today.

Because the poll fetches the **full** enabled-pipeline list and never an incremental delta, a *successful*
empty response SHALL be reconciled as a genuine "zero enabled pipelines" — including the case of disabling
or deleting the last remaining pipeline, which under this filter leaves the feed rather than arriving as
`enabled: false`. Treating that as suspect would leave an orphan recurring task firing against a pipeline
the operator has disabled. A *failed* poll is the different signal, and is the one that changes nothing.

#### Scenario: Enabled pipelines are cached

- **WHEN** ADAS returns two enabled pipelines
- **THEN** the pipeline cache contains both, keyed by name, with their resolved (inlined) evaluators

#### Scenario: The poll filters the registry to enabled enrich pipelines

- **WHEN** the runner polls the ADAS pipeline registry
- **THEN** the request carries `kind=enrich` and `enabled=true`, so neither an `aggregate` pipeline nor a
  disabled one is ever what the runner reconciles against

#### Scenario: The feed is read as an envelope

- **WHEN** ADAS returns `{"pipelines": [ ... ]}`
- **THEN** the pipelines are parsed from the envelope's `pipelines` member

#### Scenario: A bare array is not accepted as the feed

- **WHEN** the registry endpoint returns a bare JSON array instead of the envelope
- **THEN** the poll fails rather than silently reconciling to zero pipelines, and the previously cached
  pipelines and their recurring tasks remain unchanged

#### Scenario: A disabled pipeline reaching the runner is not scheduled

- **WHEN** a pipeline with `enabled: false` is present in a fetched list
- **THEN** it is neither cached nor given a recurring task instance, whatever filter the fetch sent

#### Scenario: A failed poll is non-destructive

- **WHEN** a poll fails because ADAS is unreachable
- **THEN** the previously cached pipelines and their recurring tasks remain unchanged

#### Scenario: A successful empty feed reconciles to empty

- **GIVEN** one pipeline is currently cached and scheduled
- **WHEN** a poll succeeds and returns an empty pipeline list
- **THEN** the pipeline leaves the cache and its recurring task instance is cancelled

#### Scenario: ADAS enum wire values bind case-insensitively

- **WHEN** ADAS returns a pipeline with lowercase enum values (`trigger.kind: on_ingest`, evaluator
  `type: llm`, `preset: chat_completion`)
- **THEN** they bind to the corresponding runner enums

#### Scenario: The trigger is read from the nested object

- **WHEN** ADAS returns a pipeline whose `trigger` carries `kind`, `cron`, `group_by` and `ready_when`
- **THEN** the runner reads the trigger kind, cron expression, group key and readiness declaration from
  that nested object rather than from top-level fields

#### Scenario: A sql evaluator type binds and needs no model fields

- **WHEN** ADAS returns a pipeline whose inlined evaluator has `type: sql`, output vars carrying `sql`
  expressions, and no `model`, `preset`, `params`, `request_template` or `response_schema`
- **THEN** the evaluator type binds to the corresponding runner enum and the pipeline is cached without
  the absent model fields being treated as a defect

### Requirement: Recurring task reconciliation

For each enabled pipeline the runner SHALL maintain exactly one db-scheduler recurring task instance
(instance id = pipeline name) under the task name selected by the pipeline's evaluator type:
`class1-materialize` for a `sql` evaluator and `pipeline-scan` otherwise. The instance's schedule SHALL be
derived from the pipeline's trigger: a fixed delay from `cadence` (or the configured default) for
`on_ingest`, and the trigger's cron expression for `schedule`. When a pipeline's `generation` changes, the
runner SHALL reschedule its instance. When a pipeline is no longer present in the enabled feed (disabled or
deleted), or is no longer routed to a given task name, the runner SHALL cancel that instance and — when the
pipeline left the feed entirely — release its per-pipeline resources: the DIAL rate limiter's bucket, its
per-pipeline metric gauges, and the dead-letter breaker's failure count. Each is an in-memory map keyed by
pipeline name, so leaving an entry behind both grows without bound and hands a re-enabled pipeline the
failure count it left behind. Reconciliation SHALL therefore consider every task name the runner schedules
pipelines onto, so a pipeline whose evaluator type changed does not leave a stale instance under its former
lane. A single pipeline's reconcile failure SHALL be isolated (logged and skipped) so it cannot abort the
whole reconcile and leave orphan tasks or a divergent cache.

#### Scenario: A single pipeline's failure does not abort reconcile

- **GIVEN** a batch of enabled pipelines where reconciling one pipeline fails (e.g. an invalid schedule)
- **WHEN** the sync reconciles
- **THEN** the failing pipeline is skipped and the remaining pipelines are still reconciled

#### Scenario: Enabled pipeline gets a recurring task

- **WHEN** an enabled pipeline is synced for the first time
- **THEN** a recurring task instance keyed by the pipeline name exists in the scheduler, under the task
  name its evaluator type selects

#### Scenario: Removed pipeline is cancelled

- **GIVEN** a pipeline with an existing recurring task instance
- **WHEN** a later sync no longer includes that pipeline
- **THEN** its instance is cancelled and the pipeline is dropped from the cache

#### Scenario: Changed pipeline is rescheduled

- **GIVEN** a cached pipeline with an existing recurring task instance
- **WHEN** a later sync returns the same pipeline name with a higher `generation`
- **THEN** its instance is rescheduled to reflect the new schedule

#### Scenario: A stale instance under the former lane is cancelled

- **GIVEN** a cached pipeline with an existing instance under one task name
- **WHEN** a later sync routes that pipeline to a different task name
- **THEN** the instance under the former task name is cancelled and exactly one instance exists for
  the pipeline

### Requirement: Pipeline admission gate

The runner SHALL refuse to cache or schedule a pipeline it provably cannot execute, logging a
human-readable reason and counting the refusal, so an unexecutable pipeline is visibly absent rather than
cached as healthy and failing on every fire. The gate SHALL be conservative: it rejects only combinations
the runner cannot execute — currently a `sql` evaluator carrying no output vars, a `sql` evaluator that
also declares a model (a contradiction: the lane makes no model call), a `sql` evaluator on the `group`
trigger (the group lane's unit of work *is* a model call, so the two exclude each other), a group pipeline
with no group key, a group pipeline whose input declares no `version_column` (so it has neither a version
to compare nor an activity timestamp, and could never be dirty-marked), and a group pipeline whose
`ready_when` declares **none** of `signal`, `idle` or `max_staleness` (which would be dirty forever and
never ready, so the pipeline would silently do nothing) — and SHALL NOT reject a pipeline merely for
carrying a field the runner does not recognise. A refused pipeline SHALL have no recurring task instance
under any task name.

A `ready_when` declaring `signal` SHALL NOT be a reason to refuse: ADAS computes the predicate inside the
scan and projects the verdict per row, so honouring it needs no predicate support in the runner.

The runner SHALL NOT re-validate the pipeline name's charset. ADAS validates it at registration
(`^[a-z][a-z0-9_-]{0,63}$`), and a second validator on this side would drift from the one that actually
gates the registry. What the runner depends on is stated where it is relied upon — the deterministic
work-item identity requirements in `incremental-scan` and `group-enrichment`.

#### Scenario: An unexecutable pipeline is skipped

- **WHEN** a sync returns an enabled pipeline whose evaluator type and trigger the runner cannot execute
- **THEN** the pipeline is absent from the pipeline cache, no recurring task instance is created for it,
  and the reason is logged and counted

#### Scenario: An unrecognised field does not disable a pipeline

- **WHEN** a sync returns an otherwise executable pipeline carrying a field the runner does not model
- **THEN** the pipeline is cached and scheduled normally

#### Scenario: A group pipeline with any readiness condition is admitted

- **WHEN** a sync returns a group pipeline declaring a group key and a `ready_when` with at least one of
  `signal`, `idle` or `max_staleness`
- **THEN** the pipeline is cached and a `pipeline-scan` recurring task instance is created for it

#### Scenario: A group pipeline with no readiness condition is refused

- **WHEN** a sync returns a group pipeline whose `ready_when` declares none of `signal`, `idle` or
  `max_staleness`
- **THEN** the pipeline is refused with that reason, is absent from the pipeline cache, and has no
  recurring task instance

#### Scenario: A group pipeline over an input without a version column is refused

- **WHEN** a sync returns a group pipeline carrying no `version_column`
- **THEN** the pipeline is refused naming `version_column`, is absent from the pipeline cache, and has no
  recurring task instance
