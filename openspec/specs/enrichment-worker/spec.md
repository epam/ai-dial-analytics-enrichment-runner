# enrichment-worker Specification

## Purpose
Deriving a label from a unit of work and writing it: rendering the transform's request template, calling
DIAL Core, validating the response with a bounded repair retry, mapping outputs to enrichment columns with
provenance, and writing them in a separate phase so a failed write never re-calls the model.
Grain-agnostic — one pipeline serves a row and a group.
## Requirements
### Requirement: Row enrichment via DIAL

For each unit of work — a row of a chunk, or a whole group — the runner SHALL render the pipeline's
`transform.request_template` by substituting that unit's input variables, assemble the DIAL request
(`transform.params` + `response_format` derived from the entry's top-level `response_schema`), call DIAL
Core chat/completions, and read the model's structured output from the first choice's message content. A
variable whose value is a collection SHALL render as a JSON array, so a group's member list can be
interpolated; a scalar SHALL render as it does today. The render → call → validate → map pipeline SHALL be
shared by both grains rather than reimplemented per grain, so the rate-limiter acquisition, spend accounting
and retryable-status classification have one implementation.

The `response_schema` read here is the **derived top-level** member of the compiled feed entry — the
resolved schema, whether ADAS stored one on the declaration or composed it from the outputs — and SHALL NOT
be read from inside the authored `transform` block, where a member of the same name means only "the schema
as authored".

#### Scenario: A row is rendered and sent to DIAL

- **GIVEN** a chunk row with input variables and a pipeline whose `transform` declares a request template
- **WHEN** the row is processed
- **THEN** the rendered request is sent to `.../deployments/{model}/chat/completions` and the
  response content is read

#### Scenario: A collection variable renders as a JSON array

- **GIVEN** an input variable whose value is a list of objects
- **WHEN** the template is rendered
- **THEN** the variable is substituted with a JSON array, and scalar variables are unaffected

### Requirement: Response validation with one repair retry

The runner SHALL validate the model's structured output against the entry's derived top-level
`response_schema`. On a validation failure it SHALL re-request once with the validation error carried as a
corrective message; if the response is still invalid, that row SHALL be dead-lettered at stage `validate`
with the raw response, while the rest of the chunk proceeds.

A reply that is **not parseable JSON at all** — truncated by a token limit, or prose where JSON was asked
for — SHALL be reported as an ordinary validation failure rather than raised, and SHALL be so reported even
for a pipeline whose entry carries **no** `response_schema`, since the reply is parsed for output mapping
either way. Raising it instead would skip the repair retry and dead-letter the unit at a stage that has
nothing to do with validation, carrying no raw response — losing the evidence precisely for the reply that
most needs it.

The no-schema case narrows rather than disappears: because ADAS composes `response_schema` from the outputs
when the declaration stores none, an entry with no `response_schema` at all is now the only shape that
reaches this path unvalidated.

#### Scenario: Invalid output after repair is dead-lettered

- **GIVEN** a row whose model response fails schema validation on both the initial and repair attempt
- **WHEN** the row is processed
- **THEN** a `dlq_item` at stage `validate` is recorded and no enrichment row is written for it

#### Scenario: A reply that is not JSON gets the repair retry

- **GIVEN** a row whose model reply is truncated or is not JSON, with or without a `response_schema` on the
  entry
- **WHEN** the row is processed
- **THEN** the repair retry is attempted, and a reply still unparseable after it is dead-lettered at stage
  `validate` with the raw response attached

### Requirement: Output mapping and provenance

The runner SHALL map the validated response to physical enrichment columns using the pipeline's
**`outputs`** list — one resolved entry per output, in binding order, carrying the model response
property `name`, the physical `column` written, and an optional `jsonata` transform. For each entry the
runner SHALL evaluate `jsonata` over the response when present and otherwise read `response[name]`
directly, writing the value under `column`. An absent `jsonata` is the only spelling of "no transform"
(an identity expression is refused at registration), and it is the ordinary case.

The runner SHALL NOT join two lists by name to obtain this mapping, and SHALL NOT infer either of `name`
and `column` from the other. ADAS resolves the mapping once and serves it whole: `name` is the target
column's *exposed* name and the property the composed response schema declares, while `column` is the
physical column the value is written to. They are often equal in practice and equal by
construction on none, so collapsing them writes to a column that does not exist or reads a property that
was never declared.

The runner SHALL attach provenance columns to every written row. The permitted provenance columns SHALL
be `pipeline_generation`, `model` and `enriched_at` for every grain, plus `group_version` and `truncated`
for a group-grain row — the version of the input the label was derived from and whether that input was
trimmed. `pipeline_generation` SHALL carry the `generation` of the pipeline declaration the label was
derived from, typed `long`. A provenance column the target enrichment does not physically carry SHALL be
tolerated rather than avoided, since ADAS drops it silently. An output-mapping evaluation error SHALL
dead-letter the unit of work — the row at stage `output_map`, the group at stage `group_map`.

The runner SHALL NOT send the retired `evaluator_version` column on a written row. It is not a permitted
provenance column: ADAS refuses a row naming it with HTTP 422, so — unlike a provenance column the target merely
lacks, which ADAS drops silently — sending it fails the whole write rather than being ignored.

#### Scenario: A row is written with mapped columns and provenance

- **WHEN** a row is enriched successfully
- **THEN** the written row carries the grain key, the mapped output columns, and the provenance
  columns `pipeline_generation`, `model`, `enriched_at`

#### Scenario: A written row never names the retired provenance column

- **WHEN** a row of any grain is enriched successfully
- **THEN** the upsert payload carries no `evaluator_version` column, and no write is attempted that ADAS
  would refuse with HTTP 422 for naming it

#### Scenario: An output whose exposed name differs from its column

- **GIVEN** a pipeline with an output whose `name` and `column` differ
- **WHEN** a response carrying that property is mapped
- **THEN** the value is read from the response under `name` and written under `column`, and neither name
  appears in the other's place

#### Scenario: An output with no transform is read straight from the response

- **GIVEN** a pipeline with an output declaring no `jsonata`
- **WHEN** the response is mapped
- **THEN** the value is `response[name]`, with no expression evaluated

#### Scenario: A group row additionally carries its input version and truncation

- **WHEN** a group is enriched successfully
- **THEN** the written row additionally carries `group_version` and `truncated`

### Requirement: Expression evaluation is bounded

Every JSONata evaluation SHALL run under a fixed bound on elapsed time and on nesting depth — an input
mapping over source rows and an output transform over a model reply alike — and an evaluation that exceeds
either SHALL fail as a mapping error of its stage, isolated to the unit of work it belongs to. The input is data the
runner does not control, and evaluation of one expression is serialized across worker threads, so an
unbounded evaluation of one pathological input would stall every worker evaluating that expression. Each
evaluation SHALL start its own clock, so a long-lived compiled expression does not run out of time across
calls.

The depth bound SHALL measure nesting, not input size: an ordinary mapping over a large array SHALL count the
same few levels as over a small one. It SHALL sit below the depth at which a worker thread's stack overflows,
so the bound, not the stack, ends a runaway recursion. A stack overflow the bounds did not anticipate SHALL
still fail as a mapping error of the one input rather than as an error that fails its whole unit of work.

#### Scenario: A large array is not mistaken for deep nesting

- **GIVEN** an output transform that maps each of several thousand array elements to an object
- **WHEN** it is evaluated
- **THEN** it succeeds within the bounds

#### Scenario: A runaway recursion fails on the depth bound

- **GIVEN** an expression that recurses without terminating
- **WHEN** it is evaluated
- **THEN** the evaluation fails as a mapping error rather than running until the stack is exhausted

#### Scenario: A long evaluation fails on the time bound

- **GIVEN** an evaluation that runs longer than the time bound
- **WHEN** the timeout elapses
- **THEN** the evaluation fails as a mapping error and the worker thread is released

### Requirement: A row's partition value is carried from the scan to the write-back unchanged

The runner SHALL carry a scanned row's `__partition_v` member onto that row's write-back row unchanged, as the
string the scan returned, and SHALL NOT send the member on a write-back row whose scanned row did not carry it
or carried it as `null`.

ADAS returns `__partition_v` (the source row's partition value, ISO-8601) in every scan row of a row-trigger
pipeline whose target enrichment is aligned — partitioned like its source — and requires it, non-null, on every
write-back row of such a pipeline (HTTP 422 `validation_error` otherwise). For every other pipeline it neither
returns the member nor accepts it (HTTP 422 `write_column_not_allowed`). ADAS alone decides which targets are
aligned; the runner holds no notion of alignment and reads none from the feed. It echoes what the scan gave it.

The value SHALL be taken where the scanned row is turned into a chunk row — the one place the full scanned row
exists, shared by the live scan and the backfill — and SHALL travel inside the queued chunk to the compute phase,
which puts it on the write row. It SHALL NOT be parsed, reformatted or used for anything else. A chunk row
without a value SHALL serialize exactly as it did before the member existed, and a chunk or archived payload
queued before this contract version (with no value) SHALL deserialize and process with the member omitted. Such
a row reaching an aligned target is refused by ADAS and takes the ordinary permanent-write-failure path; ADAS
does not relayout a table that has an LLM writer, so none of those rows targets an aligned table.

The group lane SHALL NOT send the member: ADAS refuses to register a group-trigger pipeline onto an aligned target,
and its group assembly never returns the member.

#### Scenario: A scanned partition value is echoed on the write row

- **GIVEN** a scan row carrying `__partition_v` = `2026-10-15T10:00:00.000Z`
- **WHEN** the row is enriched successfully
- **THEN** its write row carries `__partition_v` = `2026-10-15T10:00:00.000Z`, the same string

#### Scenario: A row scanned without a partition value is written without one

- **GIVEN** a scan row with no `__partition_v`, or with it `null`
- **WHEN** the row is enriched successfully
- **THEN** its write row does not name `__partition_v`

#### Scenario: A chunk queued before the partition echo still runs

- **GIVEN** an `enrich-chunk` payload whose rows carry no partition value, as queued by a contract-3 build
- **WHEN** it is deserialized from the queue and processed
- **THEN** it processes as before and its write rows do not name `__partition_v`

### Requirement: Pipeline-scoped upsert of a chunk's rows

The runner SHALL write enriched rows in a **separate work item** from the one that computed them, so
retrying a failed write never re-calls the model. The compute phase SHALL end by enqueueing an upsert item
carrying the computed rows; that item SHALL upsert them through the pipeline-scoped ADAS endpoint
`POST /v1/pipelines/{name}/rows`, never naming a table, carrying the grain key and only the pipeline's declared
output and permitted provenance columns.

The enqueue SHALL commit in the same transaction as the compute phase's dead-letter writes, so a computed
result is never lost between the phases: either the upsert item exists or the compute is retried. The upsert
item's instance id SHALL derive from the compute item's, so a repeated compute cannot enqueue a second write
of the same result.

The write SHALL be idempotent by construction — the item carries fixed rows keyed by grain, so re-writing
them collapses on the target's `ReplacingMergeTree` read with `final=1`. The payload SHALL carry extracted
output columns only and never source rows, which is a privacy bound as much as a size one.

This applies to both grains. It does **not** apply to Class-1, where ADAS computes and writes inside one
call and the runner never holds an intermediate result.

#### Scenario: A chunk's rows are upserted through the pipeline

- **WHEN** a chunk produces one or more enriched rows
- **THEN** they are posted to `/v1/pipelines/{name}/rows`

#### Scenario: An upsert retry does not re-call the model

- **GIVEN** a chunk that computed successfully and whose write then fails transiently
- **WHEN** the write is retried
- **THEN** the rows are re-posted from the item's payload and no further model call is made

#### Scenario: A computed result is not lost at the phase boundary

- **WHEN** the compute phase commits
- **THEN** its upsert item and its dead-letter writes are persisted together, or neither is

#### Scenario: A repeated compute enqueues one write

- **GIVEN** a compute item that is executed twice
- **THEN** the deterministic upsert id dedups and exactly one upsert item exists

#### Scenario: The upsert payload carries no source rows

- **WHEN** an upsert item is serialized
- **THEN** it carries the computed output columns and grain keys only

### Requirement: Failure isolation taxonomy

Transient/infrastructure failures SHALL propagate so the failing **phase** retries — for compute: DIAL
429/5xx, DIAL connection/IO errors and rate-limit exhaustion; for the write: an ADAS upsert failure. A
transient write failure SHALL retry only the write, never the compute that produced its rows. Permanent
per-row failures (a non-retryable DIAL 4xx, schema-invalid output after repair, an output-mapping error)
SHALL be isolated to the offending row via the DLQ so one bad row never blocks its chunk; the chunk's other
rows are still enqueued for writing. Any other unexpected runtime error raised while processing a single row
(for example an empty or unparseable model reply, or a template-render/request-build failure) SHALL likewise
be isolated to that row's DLQ rather than failing the whole chunk.

A write the target rejects non-retryably — a column the pipeline no longer declares, after a mid-flight pipeline
change — SHALL dead-letter at stage `upsert` carrying its payload, so it is requeueable once the pipeline is
fixed rather than requiring the whole chunk to be recomputed to reach the same failure.

Provenance SHALL be stamped at compute time, so a row that took several write attempts to land reports when
its label was derived rather than when the write finally succeeded. The stale-declaration guard SHALL be
re-checked at write time, so a pipeline whose declaration changed between the phases does not have a row
written against the superseded `generation`. Because `generation` moves for every declaration edit, that
re-check drops computed rows for any declaration edit, including a pure execution-knob edit — see
*Stale-declaration and pipeline-lifecycle guards*.

#### Scenario: A non-retryable row error does not block its chunk

- **GIVEN** a chunk where one row hits a permanent error and the others succeed
- **WHEN** the chunk is processed
- **THEN** the failing row is dead-lettered and the successful rows are still upserted

#### Scenario: An unexpected per-row runtime error is isolated

- **GIVEN** a chunk where one row raises an unexpected runtime error (e.g. a template that cannot be
  rendered for that row's inputs) and the others succeed
- **WHEN** the chunk is processed
- **THEN** the failing row is dead-lettered and the successful rows are still upserted

#### Scenario: A rejected write dead-letters without recomputing

- **GIVEN** an upsert item whose rows the target rejects non-retryably
- **WHEN** the item executes
- **THEN** it dead-letters at stage `upsert` with its payload retained, and no model call is made

#### Scenario: A pipeline re-declared between phases does not get a stale row

- **GIVEN** an upsert item computed against one `generation`
- **WHEN** the pipeline's declaration changes and its `generation` bumps before the write executes
- **THEN** the write is dropped rather than writing a row stamped with the superseded `generation`

### Requirement: Stale-declaration and pipeline-lifecycle guards

Before writing, the runner SHALL drop a work item whose pipeline's currently-served `generation` differs
from the `generation` it was built against, and SHALL drop a **pre-compute** item whose pipeline is no
longer present in the cache (disabled/deleted).

`generation` bumps on **any** declaration change — prompt, filter, trigger, `cadence`, `rate_rpm`,
`batch_scan_limit` — so a work item is dropped even across a pure execution-knob edit, costing a re-derivation on the row and group lanes and an
archived paid-for result on the write lane, for an edit that changed nothing about how the label is
computed.

A work item SHALL distinguish a pipeline cache that is **populated and does not hold this pipeline** from one
that has **never been populated**. Only the first is an answer. The second means the process has not yet
synced the registry — the cache is empty at startup and the first sync is a scheduled poll behind it, while
the job scheduler begins picking due work as soon as the context starts — and reading it as "disabled or
deleted" acts on a fact the runner does not have. A work item that becomes due while the cache has never
been populated SHALL be **held**: rescheduled without consuming an attempt and without being archived, and
SHALL execute normally once the first sync lands. "Held" SHALL be defined in one place that all three lanes
use, so they cannot disagree about what it means — and SHALL NOT be expressed as an ordinary retry, which
consumes the item's attempt budget and archives it at the maximum, so a registry that stays unreachable for
longer than the backoff ladder would dead-letter exactly the work this rule exists to keep. The cache SHALL
count a reconcile to **empty** as populated, because an empty registry is a real answer about it.

This matters most on the row lane, where the drop is irreversible: the scan cursor advances in the same
transaction that enqueues the chunk, so a chunk dropped on a cold cache is a set of rows nothing revisits.
On the group lane the same drop costs a re-derivation rather than the data. On the write lane it archives
a paid-for result for an operator to requeue by hand. None of the three is the right response to "ask me
again in a moment".

A work item whose pipeline has left a **populated** cache SHALL be handled by **what it holds**, not by the
lookup alone. A **pre-compute** item — a scan, a chunk, a group item — SHALL be dropped: nothing has been
spent, and the work is re-derived when the pipeline returns. A **post-compute upsert item** SHALL instead be
**archived to the DLQ with its payload and its task name**, because it carries a result the model was
already paid for whose only copy is that payload; dropping it destroys the work rather than deferring it,
and no later sweep recreates it. A requeue then re-attempts the write once the pipeline is back, without
recomputing anything.

The runner SHALL NOT distinguish a disabled pipeline from a deleted one at this point, because ADAS's pipeline feed
omits both identically — which is precisely why the post-compute item must be preserved: the far more common
cause is a pause, and a pause SHALL NOT destroy computed work.

Archiving for this cause SHALL NOT stamp the group and SHALL NOT count against the DLQ circuit breaker,
unlike a write ADAS permanently rejects. The write never reached ADAS, so nothing about the group's
evaluation state changed; and pausing a pipeline that is already absent would leave a pause entry that outlives
its return and silently holds it back.

A stale-declaration upsert item SHALL still be dropped rather than archived: its rows were computed against
a superseded `generation` and must never be written, so preserving them would preserve a result that is
no longer valid.

A work item whose payload carries a **pipeline generation of `0`** SHALL be treated as a distinct
**pre-fold** case on every lane, and SHALL NOT be dropped by the stale-declaration guard. `0` is the value
a legacy item deserializes to: its payload names `evaluatorVersion` rather than `pipelineGeneration`, and
the job scheduler's serializer ignores unknown properties rather than failing on them, so the retired member is discarded and the new one left at its default. `0` is never a
generation ADAS serves, so the reading is unambiguous.

Such an item SHALL be **tolerated rather than dead-lettered**, and SHALL NOT write a `dlq_item` row on any
lane. The runner SHALL NOT require the work queue to have been drained before the cut-over: a drain would
have to be performed by the build that is being replaced, and an execution killed at shutdown returns as a
pre-fold item on the next start regardless — so a carried-over item is a state the roll-out produces and
the runner SHALL handle it. Each occurrence SHALL be reported once at WARN naming the pipeline and the
lane, and counted on a metric of its own. It SHALL NOT count against the DLQ circuit breaker.

On the **row** and **write** lanes the runner SHALL **adopt** the pipeline's live `generation` onto the
payload and process the item. The adoption SHALL be an assignment to the payload, not merely a skipped
staleness comparison, because the payload's generation is read again downstream: it builds the derived
write item, it stamps the dead-letter row identity, it keys failure spend, and it is a log correlation
field — and a retained `0` is a distinct defect in each, the most serious being a dead-letter row the
requeue path refuses permanently. The adoption SHALL be evaluated **after** the pipeline has been resolved
from the cache, because there is no live generation to adopt before then, and **before** the
stale-declaration guard, which is what would otherwise drop the item.

On the **write** lane the runner SHALL additionally **remove the retired `evaluator_version` provenance
column** from every row before the write, because ADAS refuses a write naming it. The runner SHALL NOT
write `pipeline_generation` in its place: those rows were computed under a declaration revision the item
does not record, and
stamping them with the adopted generation would assert a lineage that did not produce them. A provenance
column is optional on ADAS's side, so an absent stamp is accepted and is the honest record. A row derived
by the **row** lane after adoption SHALL still carry the live `pipeline_generation`, because that label
really was produced by the current declaration.

On the **group** lane the runner SHALL **discard** the item instead, ahead of every other guard, and SHALL
NOT adopt it. A group work item carries keys only; the group remains dirty because a discarded item stamps
nothing, so the readiness sweep re-enqueues it at the current generation unaided. Adopting it would be
worse than discarding it: the sweep's in-flight suppression is keyed on a group instance id that embeds
the generation, so a pre-fold group item does not suppress the sweep's own item and the group would be
evaluated — and paid for — twice. The report for a discarded group item SHALL say that the sweep will
re-derive it.

The runner SHALL accept, as the bound on this tolerance, that a carried-over row lane item holds input
values extracted under the previous declaration: where the current template names a variable those inputs
do not bind, the render assertion fails the row **before** any model call and with no spend recorded, and
the row is dead-lettered at `input_map`. Where that happens to every row of a chunk, the resulting
dead-letter rate MAY trip the DLQ circuit breaker and auto-pause the pipeline until the breaker's pause
window elapses.

#### Scenario: A stale chunk is dropped without side effects

- **GIVEN** a chunk built against `generation` N and a pipeline whose served `generation` has since moved
- **WHEN** the chunk is processed
- **THEN** it is dropped without calling DIAL or upserting, and nothing is archived

#### Scenario: A chunk enqueued before the fold adopts the live generation and runs

- **GIVEN** an `enrich-chunk` payload naming `evaluatorVersion` and no `pipelineGeneration`, still queued
  when the new build starts, and its pipeline present in the cache
- **WHEN** it becomes due
- **THEN** it deserializes with a generation of 0, the payload's generation is set to the pipeline's live
  `generation`, the chunk is processed rather than dropped, no `dlq_item` is written for the adoption, and
  the pre-fold metric is counted for the row lane

#### Scenario: The write derived from an adopted chunk carries the adopted generation

- **GIVEN** such an adopted chunk that produces at least one computed row
- **WHEN** the compute phase enqueues its write item
- **THEN** that write item carries the adopted `generation` and not `0`, so the write lane does not in
  turn read it as a pre-fold item

#### Scenario: A write enqueued before the fold lands without the retired provenance column

- **GIVEN** an `upsert-rows` payload naming `evaluatorVersion`, holding computed label rows that carry an
  `evaluator_version` column, and its pipeline present in the cache
- **WHEN** it becomes due
- **THEN** the payload adopts the pipeline's live `generation`, every row is written with
  `evaluator_version` removed and no `pipeline_generation` added in its place, and nothing is
  dead-lettered

#### Scenario: A group item enqueued before the fold is discarded to the readiness sweep

- **GIVEN** a `group-chunk` payload naming `evaluatorVersion`
- **WHEN** it becomes due
- **THEN** no group fetch and no model call are made, **no `dlq_item` is written**, the group's
  `computed_version` is untouched, and the pre-fold metric is counted for the group lane — so the
  readiness sweep re-evaluates the group at the current generation

#### Scenario: An adopted chunk whose old inputs cannot render is failed before the model call

- **GIVEN** an adopted pre-fold chunk whose rows carry input values the current template's placeholders do
  not bind
- **WHEN** each row is processed
- **THEN** no DIAL call is made and no spend is recorded, and the row is dead-lettered at `input_map`
  naming the placeholder, stamped with the adopted generation rather than `0`

#### Scenario: A computed write survives its pipeline being disabled mid-flight

- **GIVEN** an upsert item holding a computed label, and its pipeline disabled in ADAS before the write executes
  (so the runner has evicted it from the cache)
- **WHEN** the write item runs
- **THEN** the item is archived to the DLQ with its payload and `upsert-rows` task name, nothing is written to
  ADAS, the group's `computed_version` is unchanged, and the pipeline is not paused

#### Scenario: The archived write is recoverable without a model call

- **GIVEN** such an archived item and the pipeline enabled again
- **WHEN** the item is requeued
- **THEN** the stored rows are re-posted to ADAS and no model call is made

#### Scenario: Work due before the first sync is held, not dropped

- **GIVEN** a freshly started process whose pipeline cache has never been populated, and chunk, group and
  write items already queued
- **WHEN** each becomes due
- **THEN** none is dropped, archived, or counted as a failed attempt, and each remains queued

#### Scenario: Held work runs once the first sync lands

- **GIVEN** such held items
- **WHEN** the first pipeline sync populates the cache
- **THEN** each item executes normally against its pipeline, with its full attempt budget intact

#### Scenario: An empty registry is an answer, not a cold cache

- **GIVEN** a process whose first sync reconciles to an empty pipeline set
- **WHEN** a queued work item becomes due
- **THEN** the cache counts as populated and the item takes the ordinary absent-pipeline path for its phase

#### Scenario: A restart does not silently discard scanned rows

- **GIVEN** chunks enqueued by a scan whose cursor has already advanced past their rows
- **WHEN** the process restarts and those chunks become due before the first sync
- **THEN** they are held and later enriched, rather than dropped for rows no cursor will read again

### Requirement: Write-back outranks compute in the work queue

A pending write-back SHALL be scheduled at a strictly higher queue priority than any compute item, so that
a free worker thread takes the write before it starts new derivation.

Without this the ordering is decided by age, and a write item is younger than the compute that created it
and younger than every compute enqueued alongside it — so it can never reach the front while compute
remains. That is not a race the write occasionally loses; it is an ordering it cannot win, and a trigger
that replenishes the compute backlog faster than the pool drains it turns "cannot win" into "never runs".
The observed outcome is a pipeline that calls the model, charges for every call, and writes nothing.

The rule is *finish before you start*. A write-back is bounded work — one request over rows already held,
no model call — it is idempotent, and it is the only step that turns spend into a stored label. It cannot
starve what sits below it, because the set of pending writes is bounded by the set of computes that have
already completed: write-back generates no write-back. The converse does not hold, which is the whole
asymmetry.

Every lane's write-back SHALL share that one priority, the backfill's included. A backfill's *compute* is
deliberately ranked below live compute; its result, once paid for, is worth what a live result is worth
and carries the same exposure while it sits queued. Ranking the two write lanes against each other would
reproduce this defect one level down.

A **requeued** item SHALL be scheduled in the lane its task belongs to, not at the default priority. A
requeue is work an operator asked for by hand; leaving it unranked places it below every automatically
enqueued item, which is the opposite of what the request meant.

The priorities SHALL be defined in one place that every enqueue site reads. Three tasks and five call
sites share them, and the value that was wrong was wrong because it was borrowed from the task next to it.

#### Scenario: A computed result is written before the next group is evaluated

- **GIVEN** a compute backlog deeper than the worker pool and one pending write-back
- **WHEN** a worker thread frees
- **THEN** it takes the write-back, and the computed row is written rather than queued behind the backlog

#### Scenario: A sustained backlog does not stop write-back

- **GIVEN** a trigger that replenishes the compute backlog on every pass
- **WHEN** the pipeline runs for longer than the pool would need to drain the backlog
- **THEN** results are written as they are computed, and the count of pending write-backs does not grow
  without bound

#### Scenario: A backfill's result is not ranked below live compute

- **GIVEN** a backfill compute item and a backfill write-back item pending alongside live compute
- **WHEN** a worker thread frees
- **THEN** the write-back is taken before the live compute, and the backfill compute after it

#### Scenario: A requeued write-back is not the lowest-priority item in the queue

- **GIVEN** an operator requeues an archived write-back while compute work is pending
- **WHEN** a worker thread frees
- **THEN** the requeued write is taken ahead of the pending compute

### Requirement: No model call and no write-back while ADAS is incompatible

The worker SHALL NOT call DIAL, and the write-back SHALL NOT be attempted, while the runner has classified
ADAS as incompatible (see the `pipeline-sync` capability). A work item that becomes due in that state
SHALL be left for a later attempt rather than executed, failed or dead-lettered, and SHALL execute
normally once compatibility returns.

The gate SHALL have two entry points, because the classification is derived from an ADAS *response*. A
work item becoming due while the condition is already recorded SHALL be held without making any call; a
work item whose own ADAS call is what discovers the skew SHALL also be held, rather than failed, on that
call's refusal. Without the second arm the first item to meet a fresh skew is precisely the one that pays
for it — the recorded state is still *compatible* when it starts.

Holding SHALL NOT be implemented by letting the item take its lane's ordinary retry path. That path
increments the item's consecutive-failure count and archives it to the DLQ at `WORKER_MAX_ATTEMPTS`, so a
hold expressed as a failure would both consume the budget and — worse — leave the count elevated once the
skew cleared, making the next genuine failure archive on its first attempt. The hold SHALL reschedule the
item so that its failure count is reset, and SHALL NOT archive it however long the condition lasts.

The gate belongs **before** the model call, not at the write. A chunk enqueued before the skew began is
still in the queue when the skew starts, and the lane's shape is derive-then-write: without this gate the
derivation happens first, the DIAL call is made and charged, and only the write-back is refused — so the
retries and the eventual dead-letter all sit downstream of money that has already been spent. Holding the
item costs nothing and loses nothing, because the queue is durable and the derivation is not yet due.

Holding an item is also the right outcome for the write-back specifically: a refusal by a mismatched ADAS
is permanent to the classifier, so the existing retry-then-archive path would consume its attempts and
archive a computed result for a reason that is about to stop being true. What the DLQ exists for is a row
this runner cannot process; a deploy in progress is not that.

#### Scenario: A due chunk is held rather than derived

- **GIVEN** ADAS classified incompatible and a chunk work item already enqueued
- **WHEN** the item becomes due
- **THEN** no DIAL call is made, the item is not failed or dead-lettered, and it remains due for a later
  attempt

#### Scenario: The item that discovers the skew is held too

- **GIVEN** ADAS has become incompatible but no call has yet classified it
- **WHEN** a due work item's own ADAS call meets the missing or mismatched echo
- **THEN** the item is held rather than failed, its attempt count is not consumed, and it is not archived

#### Scenario: A held item does not spend its attempt budget

- **GIVEN** an item held across many due times while the skew lasts
- **WHEN** compatibility returns and the item then fails for an ordinary transient reason
- **THEN** it retries with its full attempt budget, because the holds consumed none of it

#### Scenario: A pending write-back is not attempted

- **GIVEN** ADAS classified incompatible and a computed write-back item
- **WHEN** the item becomes due
- **THEN** no write is attempted, its attempt count is not consumed, and it is not archived

#### Scenario: Held work resumes on its own

- **GIVEN** work held during a skew
- **WHEN** a sync poll receives a matching contract echo
- **THEN** the held items execute on their next due time with no operator action

### Requirement: An unresolvable request template fails the work item before the model is called

Before a DIAL request is assembled, the runner SHALL require that every `{{placeholder}}` the transform's
`request_template` carries names a variable actually bound for that unit of work. A placeholder nothing bound
SHALL fail that work item, naming the offending placeholder(s), and the item SHALL be dead-lettered by the
existing unexecutable-item path — at stage `input_map` on the row grain and `group_map` on the group grain.

The runner SHALL NOT render an unsubstituted placeholder through to the model, and SHALL NOT substitute the
empty string for an unbound variable. Both turn a detectable fault into a charged call and a stored label:
the model answers a question with a hole in it, the reply passes schema validation as readily as any other,
and the answer is written as an enrichment value. Nothing about that outcome looks wrong afterwards, which
is what makes it the most expensive failure available on this lane.

The check SHALL happen **before** the DIAL call, so no spend is recorded and — on the group grain — no
evaluation is charged against the pipeline's `ready_when.cost_ceiling`. The existing accounting already
records nothing for a unit of work that never called; what this requires is that the failure not be
classified as one that did.

The failure SHALL be scoped to the **work item**, not to the lane. One malformed row must not stop a
pipeline that is succeeding on the rest; a broken declaration fails every item, and the dead-letter queue
filling with one item per grain is what makes that obvious immediately.

**A bound variable whose value is null is not this failure.** That is an absent optional the declaration
asked for by binding the variable, and it renders as empty as it does today. The fault this catches is a
*name* nothing bound.

The check SHALL read the **template** and compare the names it asks for against the keys bound, and SHALL
NOT be implemented by scanning the rendered output for surviving braces. A scanned row's own payload may
legitimately contain `{{...}}` — it is source content the runner passes through untouched — and a check on
the output would dead-letter a sound row for its data's punctuation.

Substitution SHALL be **single-pass over the template**, so "passed through untouched" holds for every
value and not only for names nothing bound. Substituting each variable in turn over the accumulated result
re-scans text that is already data: a row whose value contains `{{other}}` had it replaced as soon as
`other` came later in the variable map, so the same row rendered differently depending on iteration order
and on which other variables the declaration happened to bind. One pass visits each position in the
template exactly once and never reads what it wrote.

The variables a group assembly supplies itself — the member collection, the truncation flag, the included
and omitted counts and the pre-composed caveat — are bound on every group evaluation, so a template
interpolating them satisfies this requirement without an exemption for them.

**This requirement is not defence in depth for the contract-version check.** That catches "wrong build";
this catches "right build, wrong declaration", which is reachable with no version skew at all: ADAS
validates placeholders at arm, at preview and on a declaration write to a pipeline that is *already
enabled*, but deliberately not on a write that leaves the pipeline disabled — so "stored" no longer implies
"renderable", only "enabled" does. A variable can also fail to resolve for one particular row, which no
static check can see.

#### Scenario: A template naming an unbound variable fails the item without calling DIAL

- **GIVEN** a pipeline whose `request_template` interpolates a variable its `transform.inputs` does not bind
- **WHEN** a row of that pipeline is processed
- **THEN** no DIAL call is made, no spend is recorded, and the row is dead-lettered at stage `input_map` with
  the offending placeholder named in the error

#### Scenario: A broken declaration fails every item and stops no lane

- **GIVEN** the same pipeline and a chunk of several rows
- **WHEN** the chunk is processed
- **THEN** every row is dead-lettered, the chunk itself does not fail, and nothing is queued for writing

#### Scenario: A group whose template cannot be rendered is not charged

- **GIVEN** a group pipeline whose `request_template` names a variable nothing binds
- **WHEN** a group work item executes
- **THEN** the group is dead-lettered at stage `group_map`, no model call is made, and no evaluation is
  charged against the pipeline's cost ceiling

#### Scenario: A bound variable that resolves to nothing still renders

- **GIVEN** a pipeline whose `transform.inputs` binds a variable that resolves to null for this row
- **WHEN** the template is rendered
- **THEN** the variable renders as empty and the item proceeds to the model call

#### Scenario: A bound value containing another variable's placeholder is left alone

- **GIVEN** a template naming `{{prompt}}` and `{{topic}}`, and a row whose `prompt` value itself contains
  the text `{{topic}}`
- **WHEN** the template is rendered
- **THEN** the `prompt` value reaches the model exactly as the row held it, whatever order the variables
  were bound in

#### Scenario: Braces in a row's own data are data

- **GIVEN** a row whose bound value contains the text `{{something}}`
- **WHEN** the template is rendered
- **THEN** the value is substituted verbatim and the item proceeds, because the check reads the template
  rather than the rendered body
