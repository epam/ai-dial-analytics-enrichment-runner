# resilience-and-dlq Specification

## Purpose
What happens when work fails: bounded retry with exponential backoff per work item, dead-letter archival that
never loses a payload or loops an execution, requeue onto the task an item came from, per-pipeline DIAL rate
limiting, and a circuit breaker that pauses a pipeline whose failures are flooding.

## Requirements

### Requirement: Bounded chunk retry with backoff

A transient work-item failure SHALL retry that item on an exponential backoff with jitter, up to a configured
maximum number of consecutive failures. On reaching the maximum the item SHALL NOT be retried again. This
applies per item and therefore per **phase**: a transient compute failure (DIAL 429/5xx, rate-limit
exhaustion) retries the compute, and a transient write failure (an ADAS upsert failure) retries only the
write — never the compute that produced its rows. Both phases SHALL use the same configured budget and
backoff, since there is no evidence the write needs different patience than the compute.

#### Scenario: A chunk is rescheduled before the limit

- **WHEN** a chunk fails and its consecutive-failure count is below the maximum
- **THEN** it is rescheduled to a later time (backoff) rather than dead-lettered

#### Scenario: Backoff grows with consecutive failures

- **WHEN** the backoff delay is computed for a higher consecutive-failure count
- **THEN** it is strictly larger than for a lower count and always positive

#### Scenario: A failing write retries without recomputing

- **GIVEN** an upsert item whose write fails transiently below the retry limit
- **WHEN** it is rescheduled and runs again
- **THEN** only the write is re-attempted, from the item's payload

### Requirement: Dead-letter archival and requeue

On retry exhaustion the runner SHALL archive the work item to `dlq_item` with its serialized payload and
remove the scheduled execution (rather than deleting it silently) — for a row chunk, a group item and an
upsert item alike. The scheduled execution SHALL be removed even when archival fails, so a failed archive
cannot re-loop the execution. A group's dead-letter SHALL carry the group key in `grain_key`.

**Every** dead-letter write carrying a `grain_key` SHALL be an upsert on the identity
`(pipeline_name, grain_key, pipeline generation)`, not an insert. Work items run at-least-once, and the same
row or the same group is therefore dead-lettered on more than one attempt; an insert would raise a constraint
violation instead, which in a failure handler discards the payload it was archiving and in a worker turns a
permanent rejection into an endless retry chain. An item's `created_at` SHALL NOT be refreshed by a repeat,
so a permanently failing item still ages out of the retention window. Every archived item SHALL record the
task it came from, including one with no payload, so a stored row is never mislabelled as another task's.

Keying the identity's third component on the pipeline's `generation` determines what this upsert
collapses, and two consequences follow that this requirement states rather than leaves implied. **A row
written before the `V9` migration and one written after it are not comparable**, because the column's values
before the migration are a different number space drawn from the same small integers, starting at 1 with
none reserved. So a grain dead-lettered before the migration and re-failed after it usually writes a
**second** `dlq_item` beside the first and, where the two values happen to coincide, **upserts onto the older
row instead**. Neither outcome is harmful: an overwritten row is a stale failure replaced by a current one,
and the retention sweep clears whichever is older. And because `generation` moves on **any** declaration
edit — a prompt or expression change, but equally a `filter`, trigger or `advanced` change — a grain that
re-fails after such an edit writes a new row rather than collapsing onto the previous one.

The column backing it SHALL be **`pipeline_generation BIGINT`**, renamed from `dlq_item.evaluator_version INT`
by the `V9` migration, so the column's name states the number space it holds. The widening is required
independently of the rename, because a `generation` is a 64-bit counter in ADAS.

The partial unique index `dlq_item_row_identity_uk (pipeline_name, grain_key, pipeline_generation) WHERE
grain_key IS NOT NULL` SHALL follow the renamed column rather than being dropped and recreated. **Rows written
before the migration SHALL NOT be rewritten**: no generation can be derived for them after the fact. The
consequence SHALL be stated rather than discovered, and stated accurately: the rename changes the column's
name, not its value space, so older and newer rows usually sit side by side and occasionally collide onto
one — as described above.

The DLQ SHALL be listable and requeueable: requeuing an archived item re-enqueues its payload **onto the task
it came from** and deletes the item in one transaction, so a requeued upsert re-attempts only the write and a
requeued compute re-computes. A requeued item SHALL be enqueued at its task's own queue priority, not at
the scheduler's default, which would rank an operator's explicit request below every automatically enqueued
item. A **row-grain** poison item — one dead-lettered per row, which carries no payload because the row it
came from is not reconstructible — SHALL NOT be requeueable. Items past the retention window SHALL be swept.

An archived **legacy payload** SHALL NOT be requeueable either, and the test
SHALL be that the payload names no `pipelineGeneration` **or** names one equal to `0`. Both readings mean
the same thing and both reach the DLQ, from two different producers. The first is a row **archived by an earlier
build**, whose payload names `evaluatorVersion` and no generation at all: the dead-letter table is not a
queue and a deploy does not clear it, so such a row survives until the retention sweep. The second is a **write item whose pipeline was absent from the cache** when it ran:
that item is archived with its rows rather than destroyed, and it is archived before there is any live
generation to adopt, so its payload carries the member at `0`. Testing presence alone would let the second
kind be requeued into a lane that would archive it again for the same reason. `0` is unambiguous, because
ADAS bumps `generation` on every accepted mutation and never serves it. A single requeue SHALL be refused
with a reason naming the cause; a bulk requeue SHALL **skip** such an item and requeue the rest, exactly
as it skips a poison row, and SHALL leave it in place.

Both operations SHALL narrow by pipeline under the query parameter `pipeline_name`, and an archived item
SHALL report its owner under the JSON property `pipeline_name`. The `/v1/dlq` paths do not themselves carry
the pipeline in the URL, so this parameter and this property are the only places the identity appears on that
surface — and therefore the only places a caller can be broken by it silently.

A dead letter produced by a **backfill run** SHALL record that run, and the absence of a run SHALL mean the
live path. The two mean opposite things to an operator: a live dead letter says rows arriving now cannot be
labelled and the pipeline is degraded right now, while a backfill dead letter says a replay of history found
rows that history could not enrich either — and a replay of a large window buries the live failures beneath
its own in a listing ordered by recency. Both operations SHALL therefore narrow by that run, and the listing
SHALL additionally narrow by **lane**, so the live path can be read without a replay's noise by an operator
who holds no run ids. A filter combination that cannot match — a live lane together with a run — SHALL be
refused rather than answered with an empty list, because an empty list on a failure listing reads as
"nothing failed".

The run SHALL be taken from the writer where the writer holds it — the backfill's own page commit — and
otherwise recovered from the work item's own instance id, which already carries it: a backfill chunk is
namespaced by its run under a prefix no pipeline name can produce, and the write phase's id extends that same
prefix, so the run SHALL be recorded at every dead-letter write of the row lane, the write phase's permanent
rejections included. No queued payload SHALL change, so no work item in flight across a deploy is left
undeserializable — the failure this service has already paid for once.

A **requeued** item SHALL keep its run. The requeue re-enqueues under an instance id of its own; that id
SHALL carry the run when the item had one, so an item requeued by run that fails again is archived under the
same run rather than drifting into the live lane — which would re-create exactly the noise this attribution
removes.

The run SHALL NOT join the dead-letter identity. It SHALL be an attribute the upsert overwrites — back to none
when the live path is the later writer — so a per-row dead letter that fails on the live path and again in a
replay at the same generation still collapses onto one row and reports the more recent of the two. Items
without a grain key are plain inserts and never collapse. Adding it to the identity would split a grain's dead letter in two,
contradicting the upsert rule above and re-admitting the duplicate accumulation the partial index closed;
the cost of not adding it is that a lane-scoped listing can miss a grain that failed in both lanes, which is
bounded to a replay running over a window the live path is also failing on and leaves the current failure
visible, which is the one that matters.

**A payload-less repeat SHALL NOT overwrite a requeueable item.** A group's exhausted evaluation and a
mark-time (`input_map`) failure for the same group key share the identity, and only the first carries a
payload. When a repeat without a payload lands on a row that has one, the upsert SHALL keep that row's
payload, task and run and update only the stage and error; otherwise a replay meeting one bad row of a group
would turn the live path's requeueable group failure into an unrequeueable one filed under the run.

**A group-lane dead letter SHALL NOT be attributed to a run.** A backfill of a group pipeline only marks
groups dirty; the readiness sweep enqueues the evaluation, at live priority, under an un-namespaced instance
id. A group-assembly dead letter is therefore always the sweep's and never a run's, and stamping it with a
run id would be false. The exception is the mark-time (`input_map`) failure the backfill's own commit writes
for a group key, where the run is the writer.

Requeueing SHALL NOT change lane or priority because an item carries a run: a requeued item is re-enqueued
onto the task it came from at that task's own priority, exactly as it is today, and it counts toward the
pipeline's live in-flight backpressure like every requeued item. A requeue is an operator decision taken now
rather than a continuation of the replay — so a run-scoped bulk requeue of a large replay's failures will
throttle that pipeline's live scan while it drains, which an operator should expect.

The requeue SHALL select by pipeline, by run, or by both: at least one SHALL be given, and neither is `400`;
both together select their intersection, and an intersection that is empty requeues nothing rather than
erroring, since a bulk operation that matched nothing has not failed. Filter values on the listing — the lane
included — SHALL be accepted in their lowercase wire spelling, and an unrecognised value SHALL be `400`.

#### Scenario: Exhausted chunk is archived, not lost

- **WHEN** a chunk exhausts its retries
- **THEN** a `dlq_item` carrying its payload is written and the scheduled execution is removed

#### Scenario: The execution is removed even if archival fails

- **GIVEN** an exhausted chunk whose DLQ archival write fails
- **WHEN** the failure handler runs
- **THEN** the scheduled execution is still removed rather than re-picked into a loop

#### Scenario: Retried per-row failures do not duplicate

- **GIVEN** a chunk with a permanent per-row failure that is retried
- **WHEN** the chunk is re-processed
- **THEN** the row's `dlq_item` is upserted in place, not duplicated

#### Scenario: Requeue re-enqueues and clears the item

- **GIVEN** an archived chunk DLQ item with a payload
- **WHEN** it is requeued
- **THEN** an enrich-chunk work item is re-enqueued and the DLQ item is deleted

#### Scenario: The DLQ is narrowed by pipeline name

- **GIVEN** archived items belonging to more than one pipeline
- **WHEN** the DLQ is listed with `pipeline_name` set to one of them, or a requeue is issued for it
- **THEN** only that pipeline's items are listed or requeued, and each listed item reports its owner under
  `pipeline_name`

#### Scenario: A legacy work item never reaches the dead-letter queue

- **GIVEN** a queued work item whose payload names `evaluatorVersion` rather than `pipelineGeneration`,
  still in the job queue when the current build starts
- **WHEN** it becomes due on any of the three lanes
- **THEN** no `dlq_item` row is written for it and the DLQ circuit breaker records no failure — the row
  and write lanes adopt the pipeline's live `generation` and run it, and the group lane discards it to
  the readiness sweep, each reported at WARN and counted on a metric of its own

#### Scenario: A legacy payload archived by an earlier build is terminal

- **GIVEN** a `dlq_item` written by an earlier build, whose payload names `evaluatorVersion` and carries
  no `pipelineGeneration`
- **WHEN** an operator requeues it
- **THEN** the requeue is refused naming the cause and the row is retained with its payload; a bulk
  requeue for the same pipeline skips it and requeues the rest

#### Scenario: A legacy write archived for an absent pipeline is terminal

- **GIVEN** a `dlq_item` whose payload carries `pipelineGeneration` at `0`, written when a legacy write
  item found its pipeline absent from the cache
- **WHEN** an operator requeues it
- **THEN** the requeue is refused naming the cause and the row is retained with its rows readable, so the
  paid-for result can be replayed by hand or recovered by a bounded backfill

#### Scenario: Row-grain poison rows are not requeueable

- **WHEN** a requeue targets a per-row poison item that carries no payload
- **THEN** the request is rejected with `422 dlq_not_requeueable` and the item is retained

#### Scenario: An exhausted group item is archived and requeueable

- **WHEN** a group work item exhausts its retries
- **THEN** a `dlq_item` carrying the group key and the item's payload is written, and requeuing it
  re-enqueues one group work item

#### Scenario: An exhausted upsert item requeues as a write

- **WHEN** an upsert item exhausts its retries and is later requeued
- **THEN** an upsert work item is re-enqueued carrying the same rows, and no compute is re-run

#### Scenario: A replay's dead letter names its run and a live one does not

- **GIVEN** a backfill run and the live path both dead-lettering rows for the same pipeline
- **WHEN** the archived items are read
- **THEN** each item the run produced names that run, at every stage of the row lane including the write
  phase, and each item the live path produced names none

#### Scenario: The listing separates a replay from what is failing now

- **GIVEN** archived items from a backfill run and from the live path
- **WHEN** the dead-letter queue is listed narrowed to one run, and again narrowed to the live lane
- **THEN** the first returns only that run's items and the second returns only the live ones

#### Scenario: A filter combination that cannot match is refused

- **WHEN** the dead-letter queue is listed for the live lane together with a run
- **THEN** the response is `400` in the standard error shape rather than an empty list

#### Scenario: A run's dead letters are requeueable on their own

- **GIVEN** archived items from a backfill run and from the live path for the same pipeline
- **WHEN** a bulk requeue is issued for that run
- **THEN** only the run's items are re-enqueued, each onto the task it came from at that task's own
  priority, and the live items are left in place

#### Scenario: A grain failing in both lanes stays one row

- **GIVEN** a per-row dead letter written by the live path and then written again by a backfill at the same
  pipeline generation
- **WHEN** the archived items are read
- **THEN** there is one row, and it names the run that wrote last

#### Scenario: A mark-time failure does not overwrite a requeueable group failure

- **GIVEN** a live group dead letter carrying its payload
- **WHEN** a backfill's page commit dead-letters a row of the same group at the same generation, without a
  payload
- **THEN** there is one row, it still carries the payload, the task it came from and no run, and it remains
  requeueable

#### Scenario: A group assembly failure is not attributed to a run

- **GIVEN** a group pipeline whose backfill has marked a window dirty
- **WHEN** the readiness sweep's evaluation of one of those groups dead-letters
- **THEN** the archived item names no run

#### Scenario: A queued work item needs no new field

- **GIVEN** work items enqueued by a backfill before this change is deployed
- **WHEN** they become due afterwards and dead-letter
- **THEN** they are archived with their run recorded, because the run is read from the instance id rather
  than from the payload

#### Scenario: A write-phase rejection names its run

- **GIVEN** a backfill's write item that ADAS rejects permanently
- **WHEN** it is dead-lettered
- **THEN** the archived item names the run

#### Scenario: A requeued run item that fails again keeps its run

- **GIVEN** a backfill run's dead letter requeued by run
- **WHEN** the requeued item fails again and is archived
- **THEN** the archived item still names that run and is not listed in the live lane

#### Scenario: A requeue with no selector is refused

- **WHEN** a bulk requeue is issued with neither a pipeline nor a run
- **THEN** the response is `400` and nothing is requeued

#### Scenario: An unknown lane is refused

- **WHEN** the dead-letter queue is listed with a lane value other than live or backfill
- **THEN** the response is `400` in the standard error shape

### Requirement: DLQ listing pagination and counts

The DLQ listing SHALL page by keyset in the total order `created_at` descending, then `id` descending, so a
caller can walk the archived items matching its filters with no item repeated, and with every item that
existed for the whole walk returned exactly once, while items are archived, requeued or swept concurrently.
An item archived during the walk sorts ahead of the cursor and is not returned by it. A page SHALL hold at most
`limit` items; `limit` SHALL default to `100` and be clamped to `1..1000`. The response SHALL carry `items`,
`has_more` (whether further items exist past this page) and `next_cursor` — an opaque string to pass back as
the `cursor` query parameter for the next page, absent when `has_more` is false (the API omits null fields).
A request without `cursor`, or with a blank one, SHALL return the first page. A `cursor` the listing did not
issue — undecodable, of an unknown version, or with malformed or out-of-range fields — SHALL be refused with
HTTP 400 and error code `invalid_cursor` rather than answered with the first page, because silently restarting
a walk shows the operator the same items twice.

Every page SHALL also carry `total`, the number of items matching the request's filters, and
`requeueable_total`, the number of those whose item-level `requeueable` flag is true. Both SHALL ignore the
cursor, so every page of one walk reports the same counts absent concurrent change, and both SHALL be read on
the same snapshot as the page's items. `requeueable_total` SHALL count by exactly the rule behind the item flag
(the item carries a payload), so the counter and the rows never disagree; a legacy payload the requeue refuses
(see Dead-letter archival and requeue) therefore lists with `requeueable: true`
and counts as requeueable here.

#### Scenario: Walking the listing to exhaustion

- **GIVEN** five items match the filters, all archived at the same instant
- **WHEN** the caller lists with `limit=2`, following `next_cursor` each time
- **THEN** the pages hold 2, 2 and 1 items, `has_more` is true, true and false, the last page carries no
  `next_cursor`, and every item appears exactly once

#### Scenario: Requeue during a walk

- **GIVEN** a walk of the listing has returned its first page
- **WHEN** an item not yet listed is requeued before the next page is requested
- **THEN** the walk continues from the cursor, omits that item, and repeats no item

#### Scenario: Counts follow the filters, not the cursor

- **GIVEN** a pipeline has three items, two of which carry a payload
- **WHEN** the caller lists it by `pipeline_name` with `limit=1`, following `next_cursor` each time
- **THEN** every page reports `total` 3 and `requeueable_total` 2

#### Scenario: A cursor the listing did not issue is refused

- **WHEN** the caller lists with a `cursor` that was not issued by the listing
- **THEN** the system responds HTTP 400 with error code `invalid_cursor`

### Requirement: Per-pipeline DIAL rate limiting

DIAL calls SHALL pass through a per-pipeline token-bucket rate limiter sized to the pipeline's rate budget (or
a configured default). A worker that cannot obtain a permit within the configured timeout SHALL fail
the chunk transiently (so it backs off), rather than exceeding the budget.

#### Scenario: Requests beyond the budget are refused within the window

- **WHEN** more permits than the per-minute budget are requested within a window
- **THEN** the excess requests are refused

### Requirement: DLQ circuit breaker with pipeline pause and resume

The runner SHALL auto-pause a pipeline's enqueue when its dead-letter rate within a single scan page or
worker unit of work crosses the configured absolute count or fraction. The threshold is page-scoped (a page
holds up to the scan limit) and the fraction is the effective lever on a worker chunk (whose size is
well below the count); a non-positive threshold or fraction SHALL disable that lever rather than
tripping on the first dead-letter.

Group work items SHALL feed the same breaker through a **fraction measured over a bounded window of the
pipeline's most recent group outcomes**. A group work item is a single unit, so it has no page to be a fraction
of, and a fraction computed over it alone would read as a 100% failure rate and pause the pipeline on its very
first poison group; a running count of failures is no substitute, because a count that never decays reaches
any threshold eventually, so a pipeline with a small permanent failure rate would auto-pause indefinitely
however healthy it is. The window is therefore the denominator: each completed group — including one that
resolves empty and needs no write — and each dead-lettered group SHALL contribute **exactly one** outcome to
it, so a group's compute phase and its write phase together report once. The window SHALL be full before it
can trip, which makes it the minimum sample too, and tripping SHALL drop it so recovery after a resume is
judged on a fresh window rather than on the failures that caused the pause. A non-positive window SHALL
disable the group lane rather than trip on its first outcome, and the absolute count lever SHALL NOT apply
to it — a count is a page/chunk lever and a group has neither.

Conversely a **rejected write** SHALL count as all of its rows having failed, because the whole write was
rejected and the cause — a column the pipeline no longer declares — is what the breaker exists to catch.

A pause SHALL record its **origin**, and the origin SHALL decide whether it expires. A breaker-origin pause
SHALL resume on its own after a bounded window; an operator-origin pause SHALL NOT expire at all. The two
are different in kind. What the breaker asserts is a statement about the world outside the runner, inferred
from a failure rate, and most of the conditions that produce it — an upstream outage, a refusing model
endpoint — clear without anyone touching the runner; a pause that cannot expire records that inference as
permanent and converts a bounded outage into an unbounded silent stop. An operator's pause is a decision
taken with knowledge the runner does not have, and SHALL survive anything the runner concludes.

The auto-pause window SHALL widen on repetition and SHALL reset on recovery. Because tripping drops the
outcome window, a pipeline that auto-resumes into an unfixed upstream must produce a **full window** of
fresh outcomes before it can trip again — at the shipped defaults, fifty group assemblies and fifty model
calls spent to re-derive a verdict the runner held minutes earlier. Each consecutive auto-pause of the same
pipeline SHALL therefore last longer than the last, doubling to a configured cap, so the cost of probing a
long outage grows with its logarithm rather than in proportion to it. A pipeline that fills a full outcome
window **without** tripping SHALL be treated as recovered and its consecutive-pause count reset, that being
the same evidence the trip was made from. An operator's manual resume SHALL NOT reset it, a manual resume
being a decision rather than evidence about the upstream. A non-positive window SHALL disable expiry, so a
deployment that wants a trip to be sticky can have one.

A scan fire for a paused pipeline SHALL be
skipped, a readiness sweep for a paused pipeline SHALL be skipped, and a multi-page scan fire SHALL stop
draining as soon as a page trips the breaker (so the rest of the backlog is not enqueued before the pause
takes effect). An operator SHALL be able to pause and resume a pipeline and list paused pipelines.

#### Scenario: A flooding pipeline is auto-paused and then skipped

- **GIVEN** a pipeline whose dead-letter rate crosses the flood threshold
- **WHEN** the breaker records the flood
- **THEN** the pipeline is paused and its subsequent scan fires are skipped

#### Scenario: One poison group does not pause its pipeline

- **GIVEN** a group pipeline whose outcome window is wider than one group
- **WHEN** a single group is dead-lettered
- **THEN** the pipeline is not paused and its other groups continue to be evaluated

#### Scenario: A residual group failure rate never pauses its pipeline

- **GIVEN** a group pipeline a minority of whose groups the model can never label
- **WHEN** it evaluates groups indefinitely at that failure rate
- **THEN** the pipeline is never paused, however many groups it has processed

#### Scenario: A group pipeline that breaks is paused on its recent outcomes

- **GIVEN** a group pipeline whose window is full of successes
- **WHEN** enough of its subsequent groups dead-letter to cross the fraction within the window
- **THEN** the pipeline is paused, and earlier successes that have left the window do not spare it

#### Scenario: A group pipeline's first failures are not enough to pause it

- **GIVEN** a group pipeline that has produced fewer outcomes than its window holds
- **WHEN** every one of them is a dead-letter
- **THEN** the pipeline is not paused, because the window is not yet a sample

#### Scenario: An in-flight drain halts when a page trips the breaker

- **GIVEN** a scan fire draining multiple pages for a pipeline
- **WHEN** a page's dead-letter rate trips the breaker and the pipeline becomes paused mid-fire
- **THEN** the drain stops without scanning or enqueuing further pages in that fire

#### Scenario: An operator resumes a paused pipeline

- **WHEN** an operator resumes a paused pipeline
- **THEN** it no longer appears paused and its scans run again

#### Scenario: A paused group pipeline stops being swept

- **GIVEN** a group pipeline that the breaker has paused
- **WHEN** the readiness sweep runs
- **THEN** no group work item is enqueued for that pipeline

#### Scenario: An auto-pause outlives the outage by its window and no longer

- **GIVEN** a pipeline the breaker auto-paused during an upstream outage that has since ended
- **WHEN** the pause window elapses
- **THEN** the pipeline resumes without operator action, the resume is logged, and its work is enqueued
  again

#### Scenario: An operator pause is not swept away by the same clock

- **GIVEN** a pipeline an operator paused deliberately
- **WHEN** any amount of time passes
- **THEN** it stays paused until an operator resumes it

#### Scenario: A persistent outage costs a logarithm of probes, not a multiple

- **GIVEN** a pipeline that auto-pauses, resumes, and immediately trips again
- **WHEN** it trips repeatedly without an intervening recovery
- **THEN** each pause lasts longer than the previous one up to the configured cap

#### Scenario: Recovery resets the widening

- **GIVEN** a pipeline that has auto-paused several times and then completes a full outcome window without
  tripping
- **WHEN** it next auto-pauses
- **THEN** the pause is the shortest configured one again

#### Scenario: Expiry can be switched off

- **GIVEN** a deployment configuring a non-positive auto-pause window
- **WHEN** the breaker trips
- **THEN** the pause is held until an operator resumes it

### Requirement: A misclassified failure costs latency, never the work

Every dead-letter the runner writes at **group** granularity SHALL carry the group work item's payload,
whatever the runner concluded about the cause.

The runner classifies an HTTP failure from its status alone: 5xx and 429 are "later", everything else in
the 4xx range is "never". That is right for a rejection of the request as posed — a bad limit, a missing
entitlement, an unknown pipeline — and right for a capacity refusal that names the **upstream's own
ceiling**, which is deterministic: the identical request is refused identically until something about it
changes, so retrying it loops. It is wrong for a refusal an upstream issues because *it* ran out of a
resource under unrelated load, which is transient and succeeds unchanged once that load clears.

Those two are indistinguishable from the status only if the upstream reports them with the same status. ADAS
does not: it answers its own ceiling with a 4xx and its own resource exhaustion with a 5xx, so the rule
above is correct without the runner inspecting anything beyond the status. The classification can still be
wrong — an upstream that conflated the two would reintroduce exactly this — which is why the payload rule
below is not contingent on it.

What made that costly was not the retry decision but its second consequence: a failure ruled permanent was
archived **without** a payload, on the reasoning that replaying a deterministic failure gains nothing. That
reasoning holds only while the ruling is correct; when it is wrong the same line converts a recoverable
outcome into a destroyed one, and does so invisibly, since the archived row looks the same either way. The
perverse result is that the better-behaved upstream failure — a clean, deterministic refusal — produces a
worse outcome than a socket timeout, which is ruled transient, exhausts its attempts, and is archived
*with* its payload.

A group work item's payload is four scalars. Storing it on every group dead-letter costs a few dozen bytes
and makes a misclassification a question of **latency** rather than of **loss**: the item is requeueable
whichever way the runner ruled, and an operator's recovery is one requeue instead of a re-derivation that
nothing records the need for. Retryability SHALL additionally treat `408` and `425` as "later", both being
unambiguous statements that the request may be made again.

The runner SHALL NOT branch on an upstream's `error` machine code to make this classification. Doing so
accurately requires the upstream to commit to a stable set of capacity codes, which is a contract between
the two services and a change to both of them; the runner's single existing branch on an ADAS condition is
documented as such deliberately. The remedy when a status is wrong is for the upstream to correct the
status, not for the runner to learn a vocabulary: a machine-code branch here would make that vocabulary
load-bearing across the boundary, so every future capacity condition would need both services changed
together. Where a refusal is nonetheless dead-lettered, the rule above means it is recovered rather than
lost.

#### Scenario: A refused group fetch is recoverable

- **GIVEN** a group fetch that ADAS refuses with a status the runner classifies as permanent
- **WHEN** the group is dead-lettered
- **THEN** the stored item carries the group payload and requeuing it re-enqueues one group work item

#### Scenario: A poison group is still not retried in place

- **GIVEN** the same refusal
- **WHEN** the work item completes
- **THEN** it is not retried against the model or the upstream, and the group is stamped so the readiness
  sweep does not re-derive it

#### Scenario: A timeout-class status is retried rather than dead-lettered

- **WHEN** an upstream answers `408`
- **THEN** the failure is treated as transient and the item retries on its backoff ladder
