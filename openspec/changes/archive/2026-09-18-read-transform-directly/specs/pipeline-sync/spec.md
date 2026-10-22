## MODIFIED Requirements

### Requirement: Pipeline registry sync

The runner SHALL periodically poll the ADAS pipeline registry and maintain an in-memory cache of the
enabled `enrich`-kind pipelines keyed by pipeline **name**. Because the feed carries no ETag/304,
change-detection SHALL be done client-side using each pipeline's `generation`. A failed poll SHALL leave
the current cache and recurring tasks unchanged (no partial teardown). The cache SHALL be published as a
single atomic snapshot so a concurrent reader never observes a half-applied reconcile.

The poll SHALL request `GET /v1/pipelines?kind=enrich&enabled=true&view=compiled` and SHALL send **all
three** filters explicitly. `kind=enrich` is load-bearing rather than an optimisation: the registry also
holds `aggregate` pipelines, which ADAS executes itself and which carry none of the fields the runner
reads, so an unfiltered feed would offer the runner work it must not schedule and could not model.
`enabled=true` is sent for the same reason it always was: unfiltered, that endpoint is ADAS's operator
listing of the whole registry and carries disabled pipelines too. The runner SHALL nonetheless still skip
a pipeline whose `enabled` is false when admitting the fetched list, so reconciliation is correct for any
caller and does not depend on which filter the fetch happened to send.

`view=compiled` SHALL be sent on the sync feed and on any other pipeline read the runner makes, and is
**mandatory rather than advisory**. ADAS serves two projections of one stored pipeline, differing in
resolution and never in naming: `view=source` — the **default** — is the authored declaration, and
`view=compiled` is the same vocabulary with the derived values filled in. Only the compiled projection
carries the derived `response_schema`, the resolved `outputs`, the `grain_key`, the `version_column`
and the resolved `inputs`. Omitting the parameter therefore does not return a degraded feed; it returns a
different, cheaper object in which every pipeline is unrunnable and would be refused by the admission
gate. The projection SHALL be a fixed part of the request rather than a configurable value, because its
only wrong setting disables every pipeline at once.

The pipeline shape the runner reads SHALL be the folded, single-vocabulary one, now with the transform
on the declaration rather than in an inlined registry object. The transform's own parameters are
`transform.type`, `transform.model`, `transform.preset`, `transform.params` and
`transform.request_template`; what the transform **receives** is `transform.inputs`, a map keyed by
variable name whose value carries exactly one of a bound `column` or a `jsonata` expression; the response
schema the model is held to is the **top-level `response_schema`**, which ADAS serves resolved (the
declaration's stored schema where it has one, otherwise the schema composed from the target and the
transform's outputs); where each model response property lands is the pipeline's `outputs`, an ordered
list of `{name, column, jsonata?}`; and the execution knobs are nested under `advanced` (`scan_every`,
`rows_per_scan`, `rows_per_call`, `rate_rpm`, `sample_fraction`).

**`transform.inputs` and the top-level `inputs` are different facts and SHALL NOT be confused**, and the
collision is worth naming because the rename creates it. The top-level `inputs` is the pipeline's **read
source** — the table the runner is to scan, which ADAS resolves to the declared source or the target
enrichment's parent. `transform.inputs` is the **variable map** the request template renders from. They
are distinguishable by JSON type as well as position — the read source is an array of table names, the
variable map an object keyed by variable name — so a member read at the wrong level fails to bind rather
than binding to the wrong thing. The runner reads the scan table from the former and the template's
variables from the latter, and neither has a derived counterpart at the other's position.

The runner SHALL NOT read an inlined **`evaluator`** object: the feed no longer carries one. Every fact it
used to carry is on the entry under exactly one spelling — its `name` is the pipeline's `name`, its
`version` is the pipeline's `generation`, its `created_at` is the pipeline's `updated_at`, its transform
members are `transform`'s own, and its `response_schema` is the derived top-level member. The runner SHALL
NOT read a second spelling of any of these — `vars`, `input_bindings`, `output_bindings`, `cadence`,
`batch_scan_limit`, `batch_chunk`, `sampling`, a top-level `rate_rpm`, `input_vars` or `output_vars` —
none of which the feed carries any more.

The runner SHALL NOT retain a compatibility path for the pre-fold shape. A build reading a pre-fold ADAS
is a state the recommended roll-out does not produce, and one the contract-version check refuses and
holds when that sequence is not followed; and tolerance would
defeat the contract-version check, because a build able to read the old shape cannot distinguish a correct
feed from a stale producer. The skew is caught by the `Adas-Contract-Version` echo that this capability
already requires on every call — see "Every ADAS call declares a contract version and every response is
checked for its echo" — and **no query parameter is added to the feed** for it.

An absent `advanced`, or an absent member within it, SHALL mean "apply the runner's own configured
default", exactly as an absent `cadence` did. ADAS writes no default into the projection, because a
default echoed back would be indistinguishable from an operator having chosen it.

The feed's response body SHALL be read as the object envelope `{"pipelines": [...]}`, not as a bare JSON
array. A blank body SHALL be read as an empty list, as it is today.

Because the poll fetches the **full** enabled-pipeline list and never an incremental delta, a *successful*
empty response SHALL be reconciled as a genuine "zero enabled pipelines" — including the case of disabling
or deleting the last remaining pipeline, which under this filter leaves the feed rather than arriving as
`enabled: false`. Treating that as suspect would leave an orphan recurring task firing against a pipeline
the operator has disabled. A *failed* poll is the different signal, and is the one that changes nothing.

#### Scenario: Enabled pipelines are cached

- **WHEN** ADAS returns two enabled pipelines
- **THEN** the pipeline cache contains both, keyed by name, with their authored transforms and resolved
  response schemas

#### Scenario: The poll requests the compiled projection of the enabled enrich pipelines

- **WHEN** the runner polls the ADAS pipeline registry
- **THEN** the request carries `kind=enrich`, `enabled=true` and `view=compiled` — and no `contract`
  query parameter, the version travelling as the `Adas-Contract-Version` header on every call — so
  neither an `aggregate` pipeline nor a disabled one is ever what the runner reconciles against, and the
  pipelines it does receive carry their transform, resolved response schema, outputs, grain key and
  version column

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

- **WHEN** ADAS returns a pipeline with lowercase enum values (`trigger.kind: on_ingest`,
  `transform.type: llm`, `transform.preset: chat_completion`)
- **THEN** they bind to the corresponding runner enums

#### Scenario: The trigger is read from the nested object

- **WHEN** ADAS returns a pipeline whose `trigger` carries `kind`, `cron`, `group_by` and `ready_when`
- **THEN** the runner reads the trigger kind, cron expression, group key and readiness declaration from
  that nested object rather than from top-level fields

#### Scenario: Inputs, outputs and knobs are read under their folded names

- **WHEN** ADAS returns a pipeline carrying `transform`, a top-level `response_schema`, `outputs` and
  `advanced`
- **THEN** the transform's input variables come from `transform.inputs`, its type, model, preset, params
  and request template from `transform`'s own members, the schema the model is held to from the top-level
  `response_schema`, the output mapping from the `outputs` list, and the scan cadence, page size, chunk
  size, rate limit and sampling fraction from `advanced` — and no `evaluator` object is read

#### Scenario: An absent advanced member falls back to the runner's default

- **WHEN** ADAS returns a pipeline whose `advanced` is absent, or which omits one of its members
- **THEN** the runner applies its own configured default for each absent knob, and the pipeline is cached
  and scheduled normally

#### Scenario: A sql transform binds and needs no model fields

- **WHEN** ADAS returns a pipeline whose `transform.type` is `sql`, with no `transform.model`,
  `transform.preset`, `transform.params` or `transform.request_template`, no `transform.inputs`, no
  top-level `response_schema`, and the pipeline's own `outputs` naming the columns it derives
- **THEN** the transform type binds to the corresponding runner enum and the pipeline is cached without
  the absent model fields being treated as a defect

### Requirement: Recurring task reconciliation

For each enabled pipeline the runner SHALL maintain exactly one db-scheduler recurring task instance
(instance id = pipeline name) under the task name selected by the pipeline's **`transform.type`**:
`class1-materialize` for a `sql` transform and `pipeline-scan` otherwise. The instance's schedule SHALL be
derived from the pipeline's trigger: a fixed delay from `advanced.scan_every` (or the configured default)
for `on_ingest`, and the trigger's cron expression for `schedule`. When a pipeline's `generation` changes,
the runner SHALL reschedule its instance. When a pipeline is no longer present in the enabled feed
(disabled or deleted), or is no longer routed to a given task name, the runner SHALL cancel that instance
and — when the pipeline left the feed entirely — release its per-pipeline resources: the DIAL rate
limiter's bucket, its per-pipeline metric gauges, and the dead-letter breaker's failure count. Each is an
in-memory map keyed by pipeline name, so leaving an entry behind both grows without bound and hands a
re-enabled pipeline the failure count it left behind. Reconciliation SHALL therefore consider every task
name the runner schedules pipelines onto, so a pipeline whose `transform.type` changed does not leave a
stale instance under its former lane. That re-route is now reachable by an ordinary declaration patch
rather than by repointing a pipeline at a different evaluator, which makes it more likely, not less. A single pipeline's reconcile failure SHALL be isolated (logged and
skipped) so it cannot abort the whole reconcile and leave orphan tasks or a divergent cache.

#### Scenario: A single pipeline's failure does not abort reconcile

- **GIVEN** a batch of enabled pipelines where reconciling one pipeline fails (e.g. an invalid schedule)
- **WHEN** the sync reconciles
- **THEN** the failing pipeline is skipped and the remaining pipelines are still reconciled

#### Scenario: Enabled pipeline gets a recurring task

- **WHEN** an enabled pipeline is synced for the first time
- **THEN** a recurring task instance keyed by the pipeline name exists in the scheduler, under the task
  name its `transform.type` selects

#### Scenario: A declared scan cadence drives the fixed delay

- **WHEN** an `on_ingest` pipeline declares `advanced.scan_every`
- **THEN** its recurring instance runs on a fixed delay of that duration, and a pipeline declaring none
  runs on the configured default for its lane

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
the runner cannot execute — currently a pipeline carrying **no `transform`** at all, a pipeline of either
lane declaring no `outputs`, an `llm` transform carrying no top-level `response_schema`, a `sql` transform
that also declares a model (a contradiction: the lane makes no model call), a `sql` transform declaring any
`transform.inputs` (a Class-1 derivation renders no request, so there is nothing to substitute into — the
same rule that refused `vars` before the member moved into the block), an `llm` transform declaring no
`request_template` and one declaring no `model` (the symmetric pair of the two `sql` arms above), a `sql`
transform on the `group` trigger (the group lane's unit of work *is* a model call, so the two exclude each other), a group pipeline
with no group key, a group pipeline whose input
declares no `version_column` (so it has neither a version to compare nor an activity timestamp, and could
never be dirty-marked), and a group pipeline whose `ready_when` declares **none** of `signal`, `idle` or
`max_staleness` (which would be dirty forever and never ready, so the pipeline would silently do nothing)
— and SHALL NOT reject a pipeline merely for carrying a field the runner does not recognise. A refused
pipeline SHALL have no recurring task instance under any task name.

The empty-`outputs` refusal SHALL apply to **both** lanes off the same member, because the fold gave them
one: `outputs` is the resolved mapping for a model-calling pipeline and the derivation set for a Class-1
one. The stated reason SHALL still distinguish them, because the consequences differ. A Class-1 pipeline
with no outputs derives nothing. A pipeline that is **not** Class-1 — one the runner routes to a lane that
makes a model call, on either the row or the group grain — is the most expensive defect the gate guards
against, and the only silent one: with an empty mapping the output mapper returns no columns, the worker
still builds a write row from the grain key and the provenance columns alone, and that row is written.
Against the target enrichment's `ReplacingMergeTree` an all-NULL row at an existing grain key **replaces a
label that has already been paid for** — with no exception, no dead-letter item, no retry, and the model
call made and charged first. Every other outcome the gate prevents merely wastes work; this one destroys
data. The runner SHALL enforce it locally even where ADAS also refuses to serve such a pipeline, because a
remote invariant is not a safe guard for a local, irreversible loss.

The refusal SHALL test the pipeline's **`outputs` list** — the resolved mapping saying which physical
column each model response property is written to — and SHALL NOT be driven by the presence or absence of
a per-output `jsonata` transform. An output taken straight from the model response under its own name
declares no transform, and that is the ordinary case, not a defect.

The gate SHALL NOT extend to validating the *content* of a non-empty mapping — that an output's `column`
exists on the target, or that its `name` is declared by the response schema. ADAS validates both at
registration, a second validator here would drift from the one that gates the registry, and neither
failure is silent at run time.

The runner SHALL likewise refuse a pipeline whose `transform.type` is `llm` and which carries no
top-level `response_schema`, naming the unconstrained model call. The runner sets `response_format = json_schema` on
the DIAL request only when a schema is present, so such a pipeline asks the model an unconstrained
question — and pays for it before anything can go wrong. What happens next depends on what the model
happens to reply, and one of the two outcomes is silent: a reply that is not JSON is rejected and the row
dead-letters at `validate`, but a reply that *is* well-formed JSON of the model's own shape passes (with no
schema the response validator only parse-checks it), maps to none of the declared output properties, and
writes the grain key, the provenance columns and NULLs — the same replace-a-paid-for-label loss the
empty-`outputs` refusal prevents. The refusal SHALL be scoped to a transform that is *explicitly* `llm`
rather than to "anything that is not Class-1", so a pipeline whose `transform` the feed did not carry at
all keeps whatever verdict the other arms give it — which is a refusal in its own right, since the
declaration *is* the transform (see "Admission refuses a pipeline missing any field its lane
dereferences"). As with `outputs`, ADAS also refuses to serve such a
pipeline, and the runner SHALL enforce it locally anyway.

The `llm` arms and the `sql` arms SHALL be **symmetric**: a `sql` transform is refused for declaring
members it must not carry, and an `llm` transform SHALL be refused for omitting the two it cannot run
without. Neither omission is survivable and neither was caught: an `llm` transform with no
`request_template` reaches the renderer with nothing to render, and one with no `model` names no DIAL
deployment to call, so every row of such a pipeline dies with an unclassified runtime error dead-lettered
at `output_map` — a stack trace per row, naming nothing an operator can act on, after the pipeline was
cached as healthy. A named refusal at sync says which member is missing, once. Both arms SHALL be scoped
to a transform that is *explicitly* `llm`, so a `sql` transform — which legitimately carries neither
member — is unaffected, and an entry with no transform at all keeps the missing-transform arm's verdict.
ADAS refuses these shapes at write time as well; the runner SHALL enforce them locally anyway, for the
same reason the `outputs` arm gives.

A `ready_when` declaring `signal` SHALL NOT be a reason to refuse: ADAS computes the predicate inside the
scan and projects the verdict per row, so honouring it needs no predicate support in the runner.

The runner SHALL NOT re-validate the pipeline name's charset. ADAS validates it at registration
(`^[a-z][a-z0-9_-]{0,63}$`), and a second validator on this side would drift from the one that actually
gates the registry. What the runner depends on is stated where it is relied upon — the deterministic
work-item identity requirements in `incremental-scan` and `group-enrichment`.

#### Scenario: An unexecutable pipeline is skipped

- **WHEN** a sync returns an enabled pipeline whose `transform.type` and trigger the runner cannot
  execute
- **THEN** the pipeline is absent from the pipeline cache, no recurring task instance is created for it,
  and the reason is logged and counted

#### Scenario: A row pipeline that would write no output column is refused

- **WHEN** a sync returns an enabled `on_ingest` pipeline with an `llm` transform and an empty `outputs`
  list
- **THEN** the pipeline is refused naming the missing outputs, is absent from the pipeline cache, and has
  no recurring task instance — so no model call is ever made for it and no all-NULL row is ever written
  over an existing label

#### Scenario: A group pipeline that would write no output column is refused

- **WHEN** a sync returns an enabled `group` pipeline with an `llm` transform, a valid readiness
  declaration, and an empty `outputs` list
- **THEN** the pipeline is refused naming the missing outputs, is absent from the pipeline cache, and has
  no recurring task instance

#### Scenario: A Class-1 pipeline with no outputs is refused with its own reason

- **WHEN** a sync returns an enabled pipeline with a `sql` transform and an empty `outputs` list
- **THEN** the pipeline is refused, and the reason names the `sql` lane rather than the model-calling one

#### Scenario: An output with no transform expression is a valid mapping

- **WHEN** a sync returns an `llm` pipeline whose every output is taken directly from the model response
  under its own name, declaring no `jsonata`
- **THEN** the pipeline is admitted, cached and scheduled — the absent transform is the ordinary spelling
  of "no transform", not a missing mapping

#### Scenario: A model-calling pipeline with no response schema is refused

- **WHEN** a sync returns an enabled pipeline whose `transform.type` is `llm` and which carries no
  top-level `response_schema`
- **THEN** the pipeline is refused naming the unconstrained model call, is absent from the pipeline cache,
  and has no recurring task instance — so no unconstrained call is ever made and no all-NULL row is ever
  written from an unmatched response

#### Scenario: A pipeline with no transform is not caught by the response-schema arm

- **WHEN** a sync returns an otherwise executable pipeline whose `transform` the feed did not carry
- **THEN** the response-schema refusal does not apply, because the transform is not explicitly `llm` —
  the entry is refused by the missing-transform arm instead

#### Scenario: An llm transform with no request template is refused

- **WHEN** a sync returns an enabled pipeline whose `transform.type` is `llm` and whose
  `request_template` is absent or blank
- **THEN** the pipeline is refused naming the absent `request_template`, is absent from the pipeline
  cache, and has no recurring task instance — so no row of it is ever dead-lettered with an unclassified
  render error

#### Scenario: An llm transform with no model is refused

- **WHEN** a sync returns an enabled pipeline whose `transform.type` is `llm` and whose `model` is absent
  or blank
- **THEN** the pipeline is refused naming the absent model, is absent from the pipeline cache, and has no
  recurring task instance

#### Scenario: A sql transform is admitted with neither a template nor a model

- **WHEN** a sync returns an enabled pipeline whose `transform.type` is `sql`, carrying no
  `request_template` and no `model`
- **THEN** the pipeline is admitted, cached and scheduled — neither `llm` arm applies to the Class-1 lane,
  which legitimately declares neither

#### Scenario: A sql transform declaring inputs is refused

- **WHEN** a sync returns an enabled pipeline whose `transform.type` is `sql` and which declares any
  `transform.inputs` entry
- **THEN** the pipeline is refused, is absent from the pipeline cache, and has no recurring task
  instance, because a Class-1 derivation renders no request

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

### Requirement: Admission refuses a pipeline missing any field its lane dereferences

The admission gate SHALL refuse a feed entry that carries **no `transform`**, and one whose
**`grain_key` is blank**, naming each as its reason. Neither is a routable pipeline: the lane is chosen
from `transform.type`, and the row and group lanes both read the grain or group column out of every
scanned row by that key.

**The first arm is the same arm, retargeted.** Before the evaluator fold it tested the inlined
`evaluator` object; the declaration is now the transform, so an entry without one describes nothing the
runner can run, and the member whose absence has to be caught is `transform`. Retargeting it is not
optional housekeeping: this requirement was written *for* this change and says so — it observed that the
absent-`outputs` arm "does not fire on a projection that drops the evaluator and keeps `outputs`, which
is what the in-flight `fold-evaluator-into-pipeline` change produces". Left testing `evaluator`, the arm
would be permanently true after the fold and would refuse **every** pipeline; left testing nothing, the
gap it was built to close reopens under the new member name.

The reasoning it was built on carries over unchanged. An absent transform makes both the Class-1 and the
model-calling tests `false`, so every arm that depends on knowing the lane — the missing response schema,
the model declared on a `sql` transform — is skipped, and the entry routes to the scan lane as though it
were an ordinary row-grain pipeline. A blank `grain_key` is the single field whose absence produced a
silent cursor burn rather than a refusal. The gate is the agreed place for this, and the reason stands
whatever the cause of the missing field: the feed is remote, and a remote invariant is not a safe guard
for a local, irreversible loss.

#### Scenario: An entry with no transform is refused rather than routed

- **GIVEN** a feed entry carrying no `transform`, with `outputs` present so no other arm refuses it
- **WHEN** the synced set is admitted
- **THEN** the pipeline is refused with a reason naming the absent transform, is not cached, and is
  scheduled on no lane

#### Scenario: An entry with a blank grain key is refused

- **GIVEN** a feed entry whose `grain_key` is absent or blank
- **WHEN** the synced set is admitted
- **THEN** the pipeline is refused with a reason naming the grain key, and the refusal is counted and
  logged once for that generation

### Requirement: Every ADAS call declares a contract version and every response is checked for its echo

The runner SHALL send an `Adas-Contract-Version` request header, naming the contract version this build
was written against, on **every** call to ADAS — the sync feed and all four data-plane endpoints — and
SHALL verify that each ADAS response carries the same value in a header of the same name. A response whose
echo is **absent** SHALL be treated as coming from an ADAS older than the contract; a response whose echo
**differs** SHALL be treated as coming from an ADAS on the other side of a version bump. Both SHALL be
classified as *incompatible* and SHALL NOT be interpreted further.

The version SHALL be a constant of the build, compared for equality, and SHALL NOT be configurable — the
same rule and the same reason as the hard-coded `view=compiled`: the only wrong setting disables every
pipeline at once and does so silently.

**The evaluator fold raises it to 2**, on both sides in the same window, and that raise is the whole of
the fold's deploy-order guard on this side. ADAS reshapes the compiled projection — the inlined
`evaluator` object removed, the top-level `vars` removed, a derived top-level `response_schema` added —
which is exactly the condition this version exists to be raised for. Because the echo is verified before
a response body is interpreted, an old runner meeting the new feed classifies it incompatible and never
reads it: the expensive failure the fold could otherwise cause — binding no variables, rendering literal
`{{placeholder}}` text, being charged for the call and storing the answer as a label — is unreachable
without any new mechanism.

**No `contract` query parameter SHALL be added to the feed request, and no `contract` member SHALL be
read from a response body.** An earlier draft of the fold specified both on ADAS's side and this
capability would have had to send and verify them; they are withdrawn as a duplicate of this header. The
header already covers the feed and all four data-plane endpoints, and it covers responses a body member
cannot reach — a `401`, a `403`, an unmapped-path `404` — which is the distinction that makes an absent
echo mean "older than the contract" rather than "malformed". Two integers with one bump condition would
be two spellings of one fact.

The header SHALL be applied and verified in **one** place on the shared HTTP client rather than in each
ADAS client class, so a client that is added later cannot omit it. That same place SHALL **record** the
verdict, because it is the only component that observes every response: a skew beginning between polls is
met first by whichever call comes first — usually a scan tick or a write-back, not the feed — and a
verdict recorded only by the poll would leave the paid-work gate (see the `enrichment-worker` capability)
reading a stale *compatible* for up to a poll interval.

ADAS carries the echo on responses under its `/v1/pipelines` prefix, where all five of this runner's call
sites lie. A call site added outside that prefix SHALL be treated as a change to both halves of the
contract, since it would receive no echo and be classified as skewed.

Verification is what makes the skew detectable at all: an unknown *request* header is silently ignored by
any Spring service, so nothing the runner sends can prove the far side understood it. An unknown *response*
header is the one signal an older ADAS cannot forge, so its absence is a fact rather than an inference.

#### Scenario: A response without the echo is incompatible

- **GIVEN** an ADAS that answers 200 with a well-formed envelope but no `Adas-Contract-Version` header
- **WHEN** the sync poll runs
- **THEN** the response is classified incompatible and its body is not interpreted

#### Scenario: A response echoing a different version is incompatible

- **GIVEN** an ADAS that echoes a contract version other than this build's
- **WHEN** any ADAS call is made
- **THEN** the call fails as incompatible, naming both versions

#### Scenario: A matching pair behaves exactly as before

- **GIVEN** an ADAS echoing this build's contract version
- **WHEN** the sync poll and the data-plane calls run
- **THEN** every behaviour specified elsewhere in this capability is unchanged
