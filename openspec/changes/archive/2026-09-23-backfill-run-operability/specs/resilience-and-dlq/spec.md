## MODIFIED Requirements

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

Re-keying the identity's third component onto the pipeline's `generation` changes what this upsert
collapses, and two consequences follow that this requirement states rather than leaves implied. **A
pre-cut-over row and a post-cut-over one are no longer comparable**, because the component's *meaning*
changed while its *values* did not move: generations and evaluator versions are drawn from the same
small-integer space, both starting at 1 and neither reserved. So a grain dead-lettered before the fold and
re-failed after it usually writes a **second** `dlq_item` beside the first — the common case, since a
long-lived pipeline's `generation` has outrun its evaluator versions — and, where the two values happen to
coincide, **upserts onto the pre-cut-over row instead**. Neither outcome is harmful: an overwritten row is
a stale failure replaced by a current one, and the retention sweep clears whichever is older. What is lost
is only the ability to read the component without knowing which era wrote it. And because `generation`
moves on **any** declaration edit — not only a prompt or expression change — the same non-collapse now
recurs on every such edit, where an evaluator version would have held the key stable across a `filter`,
trigger, cadence, `rate_rpm` or `batch_scan_limit` change.

The column backing it SHALL be **renamed to `pipeline_generation` and widened to `BIGINT`**. Leaving it as
`dlq_item.evaluator_version INT` would reproduce, inside this service's own database, the defect ADAS's fold
refuses to allow on the wire: a column named for an evaluator version holding generation values, with
pre-cut-over rows carrying evaluator versions and post-cut-over rows carrying generations in the same
column, drawn from the same small-integer space and with nothing to say which of the two a given value
belongs to. The widening is required independently of the rename, because a `generation` is a 64-bit
counter in ADAS.

The partial unique index `dlq_item_row_identity_uk (pipeline_name, grain_key, <version>) WHERE grain_key IS
NOT NULL` SHALL follow the renamed column rather than being dropped and recreated. **Pre-cut-over rows SHALL
NOT be rewritten**: their small integers record which evaluator version produced them, and no generation can
be invented for them after the fact. The consequence SHALL be stated rather than discovered, and stated
accurately: what the rename changes is the column's name, not its value space, so the two eras' rows
usually sit side by side and occasionally collide onto one — as described above.

The DLQ SHALL be listable and requeueable: requeuing an archived item re-enqueues its payload **onto the task
it came from** and deletes the item in one transaction, so a requeued upsert re-attempts only the write and a
requeued compute re-computes. A requeued item SHALL be enqueued at its task's own queue priority, not at
the scheduler's default, which would rank an operator's explicit request below every automatically enqueued
item. A **row-grain** poison item — one dead-lettered per row, which carries no payload because the row it
came from is not reconstructible — SHALL NOT be requeueable. Items past the retention window SHALL be swept.

An archived payload that **predates the evaluator fold** SHALL NOT be requeueable either, and the test
SHALL be that the payload names no `pipelineGeneration` **or** names one equal to `0`. Both readings mean
the same thing and both reach the DLQ, from two different producers. The first is a row the **previous
build archived before the cut-over**: the dead-letter table is not a queue and a deploy does not clear it,
so such a row survives until the retention sweep, and its payload names the retired member and no
generation at all. The second is a **write item whose pipeline was absent from the cache** when it ran:
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

#### Scenario: A work item enqueued before the fold never reaches the dead-letter queue

- **GIVEN** a queued work item whose payload names `evaluatorVersion` rather than `pipelineGeneration`,
  still in the job queue when the post-fold build starts
- **WHEN** it becomes due on any of the three lanes
- **THEN** no `dlq_item` row is written for it and the DLQ circuit breaker records no failure — the row
  and write lanes adopt the pipeline's live `generation` and run it, and the group lane discards it to
  the readiness sweep, each reported at WARN and counted on a metric of its own

#### Scenario: A pre-fold payload archived by the previous build is terminal

- **GIVEN** a `dlq_item` written before the cut-over, whose payload names `evaluatorVersion` and carries
  no `pipelineGeneration`
- **WHEN** an operator requeues it
- **THEN** the requeue is refused naming the cause and the row is retained with its payload; a bulk
  requeue for the same pipeline skips it and requeues the rest

#### Scenario: A pre-fold write archived for an absent pipeline is terminal

- **GIVEN** a `dlq_item` whose payload carries `pipelineGeneration` at `0`, written when a pre-fold write
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
