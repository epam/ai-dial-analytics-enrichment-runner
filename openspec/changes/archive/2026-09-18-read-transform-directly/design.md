## Context

This service holds no definitions. It polls ADAS's compiled pipeline projection, caches what it gets,
schedules one recurring task per pipeline onto the lane the transform's type selects, renders a request
template per unit of work, calls DIAL, and writes labels back through ADAS's pipeline-scoped data plane.

ADAS's `fold-evaluator-into-pipeline` changes four of the members that describes:

| Fact | Before | After |
|---|---|---|
| Transform type, model, preset, params, template | `evaluator.{…}` | `transform.{…}` |
| What the transform reads | top-level `vars` | `transform.inputs` |
| Response schema the model is held to | `evaluator.response_schema` | derived top-level `response_schema` |
| Which declaration produced a row | `evaluator.version` → stamped as `evaluator_version` | `generation` → stamped as `pipeline_generation` |

Nothing else about the feed moves. `outputs`, `advanced`, `grain_key`, `version_column`, `inputs`,
`trigger`, `filter`, `enabled` and `generation` keep their spellings and their meanings.

The fourth row is the one with depth. `evaluator.version` was not only a value this service forwarded as
provenance — it is an **argument to three of this service's own keys**:

- `DeterministicId.chunkInstanceId(String pipelineName, int evaluatorVersion, String cursorBefore, int chunkIndex)`,
  and the group and backfill variants beside it.
- `dlq_item_row_identity_uk (pipeline_name, grain_key, evaluator_version) WHERE grain_key IS NOT NULL`
  (`V2__runner_state.sql`), the partial unique index that collapses a repeated per-row failure into one
  row instead of accumulating duplicates.
- `spend_counter`'s primary key `(pipeline_name, evaluator_version, day)`.

So "read `transform` instead of `evaluator`" is the easy half. The half that needs a decision is what
those three keys hold once no evaluator version exists.

## Goals / Non-Goals

**Goals:**

- Read the new shape, with no compatibility branch for the old one.
- Raise the contract version, and add nothing else for deploy ordering — which is also what makes the
  ordering a recommendation rather than a requirement.
- Re-key the three state keys deliberately, with each consequence written down.
- Make an unrenderable template fail before a model call rather than after one.
- Keep every row of runner state, and keep `pipeline_cursor` and `group_state` untouched.

**Non-Goals:**

- Reading both shapes for a transitional release. The state a compatibility branch would serve — this
  build against a pre-fold ADAS — is one the recommended sequence does not produce, and one the
  contract check already **refuses and holds** when the sequence is not followed. A branch would defeat
  that check by making the old shape readable, which is worse than the stall it would avoid.
- Any behavioural change to scan paging, group-assembly bounds, readiness, DLQ retry policy, backfill
  windowing or spend *policy*.
- Carrying a local copy of the declaration, or validating it beyond what execution needs. ADAS owns the
  gate; this service refuses what it cannot execute and says why.
- Back-filling the pre-cut-over `dlq_item` and `spend_counter` rows to a generation. They are historical
  facts under a key nothing will produce again; rewriting them would invent a generation that never
  produced them.

## Decisions

### R1. No compatibility branch, and no alias

`PipelineDto` reads the new members only. A build of this service either understands the feed it is
given or refuses it.

*Why.* The recommended roll-out does not produce the state a compatibility branch would serve — this
build reading a pre-fold ADAS — and when the recommendation is not followed, R2 refuses that state and
*holds* the work rather than absorbing it, so the branch would guard a stall that clears itself. More
decisively, tolerance here **defeats R2**: a
build that can read the old shape cannot distinguish "correct feed" from "stale producer", and the whole
value of the version check is that the ambiguity is removed. The same argument retired the old knob
spellings in `read-folded-pipeline-feed`, and it is stronger this time because the members that moved
are the ones whose absence is silent rather than fatal.

### R2. The deploy guard is the contract version already shipped; raise it to 2 and add nothing

`AdasContract.VERSION` goes from 1 to 2. Nothing else.

*Why nothing else.* `2026-09-16-refuse-skewed-adas-contract` already shipped both halves of this
handshake, and the requirement it added — "Every ADAS call declares a contract version and every response
is checked for its echo" — already covers every property a new mechanism would have had to provide:

- **Absence means "producer too old".** The header is sent on every call and the echo verified on every
  response; an absent echo is classified as an ADAS older than the contract. An unknown *request* header
  is silently ignored by any Spring service, so nothing this service sends can prove the far side
  understood it — which is exactly why the check is on the **response** header, "the one signal an older
  ADAS cannot forge".
- **It is checked before the body is interpreted.** That is what makes the expensive failure unreachable
  rather than merely reported: an old build meeting the new feed never binds a variable, never renders a
  template, never calls the model.
- **It covers all five call sites, not just the feed**, and it is verified in one place on the shared
  HTTP client so a client added later cannot omit it.
- **It already stalls rather than steers.** "An incompatible ADAS stalls the runner instead of steering
  it" specifies keeping the cache and the recurring tasks, not reconciling, not marking the sync
  successful, logging at ERROR once per transition, and publishing the condition as a metric. Every
  property an earlier draft of this decision re-derived from first principles is there already.

*What is withdrawn, and why it was wrong.* An earlier draft specified a mandatory `contract=N` **query
parameter** on the feed request, an ADAS **426** `feed_contract_unsupported`, and a `contract` member
echoed in the response **body** which this service would verify. Each fails on inspection:

- The body echo duplicates the header echo, and is strictly weaker: a body member cannot reach a `401`,
  a URL-level `403` or an unmapped-path `404`, so it cannot distinguish "wrong API key" from "build that
  predates the contract" — the distinction the header exists to make.
- 426 was **considered and rejected in ADAS's shipped code**, whose `RunnerContractException` notes it
  "is unused in this service and in the runner's HTTP client", and chose 409 because this runner treats
  only 5xx and 429 as retryable. Reviving it would give one fact two status codes across two repos.
- Requiring a parameter on the compiled read contradicts ADAS's shipped rule that control-plane reads
  must not require the contract, so that an operator, the OpenAPI console and a management client keep
  reading the registry unchanged. `view=compiled` is an operator's read too.
- Two integers with one bump condition is two spellings of one fact.

*What this service must still not do.* It must not treat a matching version as proof that the
declaration is renderable. That is R4's job, and R2 does not imply it.

### R3. Provenance moves to `pipeline_generation`, and the old spelling is not sent "just in case"

The write-back stamps `pipeline_generation` from the entry's `generation` and sends no
`evaluator_version`.

*Why not send both.* It is refused. ADAS rejects any row naming `evaluator_version` with HTTP 422,
whether or not `pipeline_generation` is beside it — the refusal is of the spelling, not of a
disagreement between two values — so sending both fails the batch rather than hedging it.

*What that refusal is, and is not.* It is **not** the deploy detector, and an earlier draft of this
decision mis-sold it as one. `POST /v1/pipelines/{name}/rows` already requires `Adas-Contract-Version`,
decided before any pipeline lookup, so a build sending the pre-fold contract is refused **409** and never
reaches the 422. The 422 covers the caller the header does not reach the same way — a hand-written
request, an out-of-date `adas-mcp`, any holder of a `WRITE` grant declaring the current contract while
still naming the retired column. For this service it is simply a rule to obey.

*What is lost, and it is nothing on the wire.* `evaluator.version` was the pipeline's `generation`
already, from the moment ADAS's fold synthesized the object. The wire sends the same number under the
name of the column that actually holds it. What is **not** nothing is R6.

### R4. The placeholder assertion is render-time, not sync-time, and it fails the item rather than the lane

Before a call is made, every `{{placeholder}}` in the rendered template must have been substituted from
the variables bound for that unit of work. An unsatisfied placeholder fails the work item.

*Why this exists at all when ADAS validates it.* ADAS validates placeholders **at arm, at preview, and
on any declaration write to a pipeline that is already enabled** — but deliberately not on a write that
leaves the pipeline disabled, because an author legitimately saves a pipeline whose template is written
and whose `transform.inputs` are not yet bound. So "stored" no longer implies "renderable"; only
"enabled" does, and the gap between them is real: a pipeline can be armed while valid, then have a var
fail to resolve for a particular row. This assertion covers the residue no static check can see.

*Why render-time rather than sync-time.* A sync-time check would have to predict what every future row
binds. The actual failure is per-item — a var whose JSONata yields nothing on this row, a member field
absent from this group — and the only place the truth is known is immediately before the call.

*Why the item and not the lane.* Failing the lane would let one malformed row stop a pipeline that is
succeeding on every other row. The item goes to the DLQ with the offending placeholder named, which is
the existing path for an item that cannot be executed, and the lane continues. If the cause is the
declaration rather than the row, every item fails and the DLQ makes that obvious immediately.

*What it must not do.* It must not render an unsubstituted placeholder "best effort", and it must not
substitute empty string for an unbound variable. Both turn a detectable fault into a charged call and a
stored label. This is the failure R2 exists to prevent, arriving one layer lower.

### R5. The evaluator vocabulary is renamed, not deleted

`EvaluatorType` and `EvaluatorPreset` become `TransformType` and `TransformPreset`; `EvaluatorVersionDto`
is deleted outright; and `EvaluatorInvoker` becomes `TransformInvoker` — the one call site this decision
first missed, which matters because that class's whole job is to run the transform. `DialRequestBuilder`'s
`json_schema.name = "evaluator_output"` is deliberately **kept**: it is a label on the DIAL request and
nothing to do with the ADAS contract, so moving it would change what this service sends to DIAL Core for
no reason on either side.

*Why the distinction.* The enums describe values that still exist on the wire under
`transform.type` / `transform.preset` with the same members and the same lowercase spellings; only the
object that carried them is gone. The DTO describes an object that no longer exists. Renaming the first
and deleting the second keeps the code's vocabulary matching the wire's, which is the property that made
the previous fold's call sites mechanical to update.

### R6. The three state keys take `generation`, the columns are renamed, and the two behaviour changes are stated

`DeterministicId`'s version argument, `dlq_item.evaluator_version` and `spend_counter.evaluator_version`
all take the pipeline's `generation`. The two columns are **renamed to `pipeline_generation`** by a
migration, and their type widened from `INT` to `BIGINT`.

*Why rename rather than leave the name.* Leaving it reproduces, inside this service's own database, the
exact defect ADAS's fold refuses to allow on the wire: a column named for an evaluator version holding
generation numbers, with pre-cut-over rows carrying small evaluator-version integers and post-cut-over
rows carrying generations in the same column and no way to tell which space a value belongs to. ADAS declined
that for one provenance column and paid for a new one; declining it here costs one migration. The
migration renames and widens — it does **not** rewrite any row, because a pre-cut-over value is a
historical fact and inventing a generation for it would be a fabrication.

*Why widen the type.* `generation` is a 64-bit counter in ADAS (`BIGINT`), and ADAS types its
`pipeline_generation` provenance column `long` for that reason. `INT` columns and `int` parameters here
would silently truncate at 2^31, which no live pipeline will reach but which is a latent defect with no
upside. `DeterministicId`'s parameter widens to `long` with it.

*Behaviour change 1: every instance id changes at cut-over, and now changes on any knob edit.* An
instance id's basis includes the version, so the ids this service computes after the deploy differ from
the ones before it. That is harmless **when the window is quiesced** — nothing is in flight at zero
replicas — and it must be stated rather than assumed, because `scheduleIfNotExists` on these ids is
the "belt to the suspenders" of the atomic cursor transaction. Since the quiesce is a recommendation
rather than a requirement (R2), say what holds without it: the belt is loosened across the cut-over,
because ids computed on either side of it do not collide, while the primary exactly-once mechanism —
the atomic cursor transaction — is untouched. That is one more reason to scale to zero, not a reason
the deploy is unsafe if you do not. The lasting change is different and
sharper: `generation` bumps for **any** declaration edit — `cadence`, `filter`, `rate_rpm`, `trigger` —
where an evaluator version moved only for a prompt or expression change. So editing a knob now re-keys
every in-flight work item, and the residual-overlap dedup those ids provide no longer spans that edit.
The primary exactly-once mechanism is unaffected; the belt is briefly loosened.

*Behaviour change 2: a pre-cut-over DLQ row and a post-cut-over one stop being comparable — which is a
statement about the column's NAME, not its VALUES.* The partial unique index exists so a repeated per-row
failure upserts instead of accumulating. An earlier draft of this decision said the same failing grain
"produces a second row rather than updating the first, because the key value differs", and that overstates
its own invariant: generations and evaluator versions are drawn from the **same** small-integer space, both
starting at 1 and neither reserved. The migration header's own illustration (small values before the cut-over,
larger ones after) describes one long-lived pipeline; a recently registered pipeline has a single-digit
`generation` too. So the accurate statement is: usually a second row appears beside the first
and the 30-day sweep clears the older one, and where the two values coincide `ON CONFLICT ... DO UPDATE`
**overwrites** the pre-cut-over row — just as `SpendService`'s upsert merges a day's counters onto a
colliding pre-cut-over counter row. Neither is harmful. What is actually lost is the ability to read a
value in that column without knowing which era wrote it, which is precisely what the rename buys back.

A requeued *archived chunk* is the case to watch, because its `payload` is a serialized pre-fold work item:
such an item SHALL be refused on requeue rather than re-run, since it names a `generation` the feed never
served. Confirmed in code and shipped. Note that `dlq_item` is a table, not a queue — a deploy does not
empty it — so this is the one pre-fold shape that outlives the cut-over window entirely, surviving until
the 30-day retention sweep. R7 covers the queue's side of the same fold.

*What is explicitly safe, and worth saying because the fear is reasonable.* The soft daily spend-pause
gate does **not** reset. `SpendService`'s gate reads
`SELECT SUM(calls), SUM(usage_amount) FROM spend_counter WHERE pipeline_name = ? AND day = ?` — it sums
across every key value for that pipeline and day, so adding a new key value adds a row the sum already
includes. An operator who has exhausted a pipeline's daily budget does not get a fresh one by editing a
knob. This was checked in code rather than inferred, because the opposite would have been a money bug.

### R7. A pre-fold work item is tolerated, per lane, and never dead-lettered

`pipelineGeneration == 0` is its own case on all three lanes. It is **not** a stale declaration and must
not take the stale guard's silent drop, and it is **not** a failure and must not take the DLQ's. What each
lane does with it differs, and the difference is the decision:

- **chunk** — **adopt**. After the pipeline is resolved from the cache and before the stale guard,
  `payload.setPipelineGeneration(pipeline.generation())`, then carry on and run the item.
- **upsert** — **adopt**, in the same position, and additionally **strip** the retired
  `evaluator_version` key from every row before the write.
- **group** — **discard**, ahead of every guard, with a WARN and the metric and **no `dlq_item` row**.

Each is logged once at WARN naming the pipeline and the lane, and counted on
`runner.workitem.prefold{lane}`. Nothing is counted against the DLQ circuit breaker.

*Why tolerance rather than a drain step plus a terminal archive.* An earlier version of this decision
archived such an item at a dedicated `pre_fold` stage and told the roll-out to drain the queue first. That
is not available in the supported upgrade procedure, which is limited to deploy actions — deploy,
undeploy, scale and update services, never call an API, never run SQL — and a deploy must not block on a
queue emptying. A shutdown-drain was considered and rejected twice over: the drain would have to live in
the **previous 0.2 release, which does not have it**, and an execution SIGKILLed at shutdown is resurrected by the new build as a
pre-fold item regardless of any drain. So the queue **will** carry items across the fold, and the only
question is what the new build does with them.

*Why adoption rather than "skip the stale comparison for generation 0".* Because `pipelineGeneration` is
read from the payload in several places downstream, and a leaked `0` is a different defect in each:

- `WorkerService` builds the derived `UpsertPayload` from it. At `0` the write lane sees a pre-fold
  payload in turn and strips provenance from a result the compute phase just paid for.
- `UpsertService`, `ChunkFailureHandler`, `GroupFailureHandler` and `UpsertFailureHandler` stamp the DLQ
  row identity with it — the third component of `dlq_item_row_identity_uk`. At `0`, any later failure of
  the item produces a dead-letter row that `DlqRequeueService.foldEra` refuses to requeue **permanently**.
- `WorkerService` records failure spend keyed by it. Harmless for the daily pause gate, which sums across
  generations, and wrong for any per-generation reporting.
- It is the `pipeline.generation` MDC field on every line the rest of the execution logs.

Adoption fixes all four at one assignment, and the payload object it mutates is the same instance the
failure handler later reads: db-scheduler memoizes a picked execution's data supplier
(`JdbcTaskRepository`, verified in 15.6.0), so `execution.taskInstance.getData()` returns the object the
worker was handed.

*Why the arm sits after the cache lookup and before the stale guard.* Later is too late — the stale guard
is what dropped it. Earlier is impossible: there is no live generation to adopt until the pipeline has
been resolved. On the write lane this also means a pre-fold write whose pipeline is **absent** from the
cache takes the existing absent-pipeline archive instead, with its rows and at generation 0 — which is the
one remaining producer of a generation-0 DLQ row, and the reason `foldEra` is kept.

*Why the group lane does not adopt.* A `GroupPayload` carries keys only, the group is still dirty
(`group_version > computed_version`), and a discarded item stamps nothing — so the readiness sweep
re-enqueues it at the current generation by itself. Adopting would be worse than doing nothing: the
sweep's in-flight dedup keys on `DeterministicId.groupInstanceId(...)`, which embeds the generation, so a
pre-fold group item does **not** suppress the sweep's own item. Tolerating it would buy **two model
calls** for one group, on the most expensive lane there is.

*Why the group lane writes no DLQ row either.* A terminal, un-requeueable row for work the sweep has
already redone is misinformation, and it inflates `runner.dlq.size`, which alerts are written against.
The WARN — which says the sweep will re-derive it — and the lane-tagged counter are the whole record.

*Why an adopted write's rows lose `evaluator_version` and gain nothing.* ADAS refuses a write naming the
retired spelling with 422, so the key has to go or the rows this lane exists to save dead-letter. Putting
`pipeline_generation` in its place is the tempting symmetry and it would be false: these rows were
computed under the pre-fold declaration, and the adopted generation names a declaration that did not
produce them. An absent stamp is permitted — ADAS's `EnrichWriteValidator` requires only that every column
named is permitted and that at least one declared output column is present, and `EnrichWriteService`'s
`dropAbsentProvenance` only ever removes provenance columns, never adds them — and nothing in ADAS reads a
generation stamp back. An adopted **chunk**, by contrast, does stamp the live generation, because its
label really was produced by the current declaration.

*What bounds the downside, and it is already built.* A pre-fold chunk's rows carry input values extracted
under the previous `transform.inputs`, so the current template may name a variable they do not bind.
`TemplateRenderer.requireEveryPlaceholderBound` throws `UnresolvedPlaceholderException` **before** the
request is assembled, so no DIAL call is made and no spend is recorded, and the row dead-letters at
`input_map` naming the placeholder (R4). That is the ceiling on what tolerance can cost: a wasted
scheduler execution, never a wrong label paid for.

*The accepted residual risk, stated rather than discovered.* For items enqueued by 0.2 and still queued at
cut-over, rows are labelled under whatever declaration is **current when they run**. A declaration edited
*inside* the window could therefore mean a newer prompt, a same-name variable now reading a different
column, or a `filter` that would no longer have selected those rows. All three were **equally true of the
0.2 build that created those items**: 0.2's stale key was the evaluator version, while `vars`, `filter`
and `trigger` were already pipeline-level and a change to any of them never dropped a queued chunk. So
this is not a regression — with one exception, a re-pointed `target`, which is new and narrow, and which
only an edit inside the window can reach. The roll-out's constraint is therefore a sentence, not a step:
do not edit a pipeline declaration while the window is open. It costs nothing extra to honour, because
ADAS's half of this release carries a strictly stronger rule over the same period for a different
reason — **no pipeline write of any kind while ADAS is rolling**, since an outgoing ADAS pod rewrites a
folded `spec` without its `transform`. That one is a hard requirement of the pair's roll-out; this one
is the weaker caution that extends past the ADAS roll for as long as carried-over work is still
draining.

*The other cost, stated for the same reason.* A declaration whose inputs were renamed across the window
fails **every** row of a carried-over chunk at the render assertion, which can count enough dead-letters
to trip the DLQ circuit breaker and auto-pause that pipeline. The pause lifts itself after
`DLQ_BREAKER_PAUSE_FOR`. This is acceptable — it is the breaker doing its job on a pipeline that is
genuinely producing nothing but failures — but it must be expected rather than discovered mid-window.

*What it costs in schema.* Nothing. The earlier design's `pre_fold` `DlqStage` and its `V10` CHECK-widening
migration are both removed: no lane writes that stage any more, and `V10` was never released, so deleting
the file is strictly better than shipping a constraint widening for a value nothing writes.

### R8. The `llm` admission arms are the missing half of a symmetry, not new policy

`PipelineRouting.unsupportedReason` refuses a `sql` transform that declares a `model` and one that declares
`transform.inputs` — members the Class-1 lane must not carry. It refused nothing for an `llm` transform
that **omits** `request_template` or `model`, which are the two members that lane cannot run without.

*What the gap actually produced.* `TemplateRenderer.render`'s placeholder assertion returned early on a
null template, and the substitution loop then ran `result.replace(...)` on a null string — so every row
died with an NPE, dead-lettered at `output_map` with a message naming nothing. A null model reached the
DIAL client as the deployment segment of the URL. In both cases the pipeline was cached as healthy first,
which is exactly the posture the gate's own doc comment says it exists to prevent.

*Why the runner refuses it locally when ADAS refuses the shape at write time.* The same argument the
`outputs` arm already makes: a remote invariant is not a safe guard for a local failure, and this runner
must not depend on an ADAS-side check landing in the same release. Both arms are scoped to a transform
that is *explicitly* `llm`, so the Class-1 lane — which legitimately declares neither member — is
untouched, and an entry with no transform keeps the missing-transform arm's verdict.

### R9. Template substitution is single-pass

`render` looped over the bound variables, calling `String.replace` on the accumulated result each time. A
value containing `{{other}}` therefore had it substituted as soon as `other` came round in the map — so
whether a row's own content was preserved depended on iteration order and on which other variables the
declaration happened to bind.

*Why this is a correctness fix and not a tidy-up.* The class's own contract says row content carrying
`{{…}}` is "passed through untouched", and the existing test only covered a name **nothing** bound, which
is the case the placeholder assertion already made safe. The uncovered case is the one that occurs: a
conversation transcript or a request body quoting a template. One pass with `Matcher.appendReplacement`
visits each position in the template exactly once and never reads what it wrote, which makes the
documented property true. `Matcher.quoteReplacement` is required with it, or a `$` in a row's data would
be read as a group reference.


## Risks / Trade-offs

- **The echo check makes this service refuse a feed that is actually fine** → only if ADAS serves the
  contract and fails to echo it, which its own spec requires and tests. Unchanged by this change; noted
  because R2 now leans on it entirely rather than adding a second guard beside it.
- **A misordered deploy stops work instead of doing it wrongly** → the intent, and worth stating as a
  cost: the roll-out has a window in which this service does nothing, and an operator who sees
  incompatible-ADAS errors must read them as "ADAS is ahead, deploy the runner" rather than as an outage.
  Stopping is also *holding* — `AdasIncompatibleHoldException` reschedules without consuming an attempt —
  so the stall clears itself, which is what lets the deploy order be a recommendation rather than a
  requirement.
- **The render-time assertion can fail every item of a pipeline** → true, and intended, when the
  declaration is unrenderable. The DLQ names the placeholder, which points at the declaration rather
  than at the data. The alternative — calling the model anyway — is billed and writes wrong labels.
- **Two repositories describe one wire** → the shape lives in both this document and ADAS's, and they
  can drift. Mitigation: ADAS pins the serialized compiled projection in a test, and its
  `RunnerContractShapeTest` fails its own build if that shape moves without the version being raised.
  That test is the artefact the two agree through rather than the prose.
- **`transform.inputs` is a rename with no alias** → a spec or fixture in this repository that still
  says `vars` will bind to nothing and produce an empty variable map, which renders a template full of
  literal placeholders. R4 is what turns that from a charged call into a failed item, which is why R4 is
  not merely defence in depth for R2.
- **The state-key migration is the first this change admits to needing** → an earlier draft asserted "no
  data migration" and "state: none discarded", which was true of `pipeline_cursor` and `group_state` and
  false of `dlq_item` and `spend_counter`. The migration is a rename plus a widen with no row rewrite, so
  it is cheap; the risk was in not noticing it was needed.
- **A queued work item that predates the window is dropped silently, and "quiesced" does not cover it** →
  found while implementing, and **fixed in code alone**: see R7. There is no roll-out step behind it,
  because there cannot be one — a drain would have to live in the previous 0.2 release, which does not
  have it. The residual risk that replaces it is that a carried-over item is labelled under whatever
  declaration is current when it runs, which R7 states in full and which was equally true of the build
  that enqueued it.
- **Renaming a column in an applied migration is not an option** → the rename is a **new** migration, not
  an edit to `V2__runner_state.sql`. Flyway checksums applied migrations, and editing one aborts startup
  everywhere.

## Migration Plan

One migration in this service's Postgres, renaming and widening two columns and rewriting no row:

```sql
ALTER TABLE dlq_item      RENAME COLUMN evaluator_version TO pipeline_generation;
ALTER TABLE dlq_item      ALTER COLUMN pipeline_generation TYPE BIGINT;
ALTER TABLE spend_counter RENAME COLUMN evaluator_version TO pipeline_generation;
ALTER TABLE spend_counter ALTER COLUMN pipeline_generation TYPE BIGINT;
```

There is **no second migration**. An earlier version of R7 added one to widen `dlq_item`'s `stage` CHECK
constraint for a `pre_fold` value; no lane dead-letters a pre-fold item any more, so the stage and its
migration are both gone rather than shipped unused.

The `dlq_item_row_identity_uk` partial unique index and `spend_counter`'s primary key follow the renamed
column automatically; neither needs to be dropped and recreated. The migration is `V9`; a duplicate
Flyway version never conflicts in version control and instead fails every test at startup. Pre-cut-over rows keep their small integers, deliberately: they record which
evaluator version produced them, and no generation can be invented for them after the fact.

Deploy in one window; **every step is a deploy action**, and the steps are a **recommendation**:

1. Scale this service to **0**.
2. ADAS deploys `fold-evaluator-into-pipeline`.
3. Deploy this service, with `AdasContract.VERSION` at 2. `V9` runs at startup.
4. Scale back to **1**. The first poll validates the contract; the first scan resumes from the cursor it
   left, because nothing in this change touches `pipeline_cursor` or `group_state`.

**No pre-deploy drain, no pause, no queue-depth wait, no API call and no SQL.** The upgrade is limited to
deploying, undeploying, scaling and updating services, and a deploy must not block on a queue emptying —
which is why R7 tolerates a carried-over work item in code instead. Whatever is in `scheduled_tasks` when
the new build starts is run (chunk, write) or handed back to the readiness sweep (group).

**One rule is required, and it is not the ordering: no pipeline write — `POST`, `PATCH` or `DELETE` on
`/v1/pipelines` — while ADAS is rolling.** That one is ADAS's hazard rather than this service's, and
nothing guards it: an outgoing ADAS pod handling any pipeline write rewrites the folded `spec` without
its `transform`, and `maxUnavailable: 0` guarantees such a pod exists for the length of the roll.

*Why the four steps are only a recommendation.* Both misorderings are refused **and held** rather than
absorbed. `AdasWorkGate.requireCompatible` throws `AdasIncompatibleHoldException`, which reschedules the
work item with no attempt consumed and nothing archived, and ADAS's data plane answers 409 before any
lookup. A skew stalls and then self-heals once both halves are up, which is a delay, not damage. A
*required* window would rest on two legs — the outgoing ADAS pod's write path, and an operator drain of
`scheduled_tasks`. R7 replaces the drain with code, so only the first leg remains, and it is covered by
the no-pipeline-write rule above rather than by the ordering.

*Why the quiesce is still recommended, stated because a recommendation invites skipping.* During the
ADAS roll both ADAS builds serve concurrently, so this service left running can read a *compatible*
echo from an outgoing pod, start work, pay for a model call, and then have the write that follows
refused 409 by a new pod. That work is held rather than failed, and the new build's R7 adoption writes
it after the upgrade — **recovered, not lost**. The cost is a deferred write and a compatibility signal
that flaps for the length of the roll: noise, not damage. Scaling to zero buys that off, at the price of
a few minutes in which this service does nothing.

The other constraint on the window is a statement about what must not be *changed*, not an action to
perform: **do not edit a pipeline declaration while the window is open.** A carried-over item is labelled
under whatever declaration is current when it runs; R7 states the residual risk in full, and why it is
not a regression on the 0.2 build that enqueued the item. The required no-pipeline-write rule above
subsumes it for the length of the ADAS roll; this one extends past that, for as long as carried-over
work is still draining.

*Nothing needs verifying by hand between steps 2 and 3*: readiness stays DOWN until a sync validates the
contract echo, so a Ready pod at step 4 is itself the proof that the pair matches. The old image, while
ADAS is ahead, reads an `Adas-Contract-Version` of `2`, classifies the feed incompatible, logs it once,
publishes the metric and evaluates nothing; its data-plane calls also answer 409 — expect both, so
neither reads as a second fault.

## Open Questions

1. **Does the DLQ requeue path deserialize an archived chunk's payload directly?** If so, a pre-cut-over
   archived chunk requeued after the deploy describes a declaration shape this build cannot read, and
   R6 says it must be refused rather than re-run. This is a code check, not a spec question, and it is
   the one item in this change that could bite after the window closes rather than during it.
2. **Should a contract mismatch eventually fail readiness rather than only the sync metric?** The
   shipped requirement says no, because a pod restarted into the same refusal is worse than a degraded
   one. Unchanged here; revisit if an operator ever misses a mismatch for long enough to matter.
3. **Should a carried-over item be distinguishable in the enrichment row it writes?** It is not today:
   an adopted chunk stamps the live `generation` like any other, so a row derived from inputs extracted
   under the previous declaration is indistinguishable from one derived under the current one. The
   counter says how many crossed, not which rows they became. A per-row marker would need a provenance
   column no target carries and that ADAS would have to permit, for a population that exists for one
   deploy window. Left alone.
4. **Should the render-time assertion also refuse a template whose variables are all bound but whose
   rendered body is empty?** Distinct failure, no evidence it occurs, and cheap to add later if it does.
