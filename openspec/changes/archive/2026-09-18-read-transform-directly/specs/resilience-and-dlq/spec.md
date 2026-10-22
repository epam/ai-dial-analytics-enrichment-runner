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
