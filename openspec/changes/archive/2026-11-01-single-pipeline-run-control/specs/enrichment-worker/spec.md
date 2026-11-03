## MODIFIED Requirements

### Requirement: Stale-declaration and pipeline-lifecycle guards

Before writing, the runner SHALL drop a work item whose pipeline's currently-served `generation` differs
from the `generation` it was built against, and SHALL drop a **pre-compute** item whose pipeline is no
longer present in the cache (deleted, no longer served by ADAS, or refused by the runner). It SHALL instead **hold**
an item of any phase whose pipeline is cached as **stopped**.

`generation` bumps on **any** declaration change — prompt, filter, trigger, `cadence`, `rate_rpm`,
`batch_scan_limit` — so a work item is dropped even across a pure execution-knob edit, costing a re-derivation on
the row and group lanes and an archived paid-for result on the write lane, for an edit that changed nothing about
how the label is computed. It does **not** bump on a change of `enabled` alone, so stopping and restarting a
pipeline leaves its held items current.

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

A work item whose pipeline is cached as **stopped** — disabled in ADAS, the pipeline's single run control — SHALL
be **held** on every lane, chunk, group and write alike, under the same definition of held the cold cache uses:
rescheduled after the fixed hold delay without consuming an attempt, never archived and never dropped, with no
model call and no write while it is held. It SHALL run normally once a sync caches the pipeline as active again.
Holding rather than dropping is the point of a stop: the scan cursor advanced when the chunk was enqueued, so a
dropped chunk is a set of rows nothing revisits, and a dropped write is a paid result lost. The stop SHALL be
checked before the model call and checked again before the write, as the absent-pipeline check is. The
stale-declaration guard still applies to a held item once it runs: a declaration re-declared while the pipeline
was stopped moves `generation`, and the item is then dropped or archived as any stale item is.

A stop is observed at the runner's next sync, not when ADAS records it. An item whose model call began before the
sync landed may reach ADAS after the pipeline was disabled, and ADAS refuses that write with HTTP 409
`pipeline_conflict` — on the pipeline-scoped write paths that code means only that the pipeline is disabled. The
write lane SHALL **hold** such an item, exactly as it holds one whose pipeline is cached as stopped: it SHALL NOT be
dead-lettered, SHALL NOT stamp its group and SHALL NOT count against the DLQ circuit breaker. The refusal is the
stop arriving ahead of the sync, not a rejection of the rows; dead-lettering it would lose the paid result to a
manual requeue, stamping the group would mark an unwritten label clean, and counting it — every row of the write
at once — would let an operator's stop trip the breaker. Once the sync lands the item is held as stopped work, and
it writes after the pipeline is re-enabled. Every item still queued when the sync lands is held the same way.

A disabled pipeline does not reach this point: the runner reads the unfiltered feed, so a disabled pipeline stays
cached as stopped and its work is held. Absence here means the pipeline was deleted, ADAS no longer serves it, or
the runner refused it. The post-compute item is still archived rather than dropped, because a refused pipeline
can be admitted again once its declaration is fixed, and a paid result SHALL NOT be destroyed on a verdict the
runner can revisit.

Archiving for this cause SHALL NOT stamp the group and SHALL NOT count against the DLQ circuit breaker,
unlike a write ADAS permanently rejects. The write never reached ADAS, so nothing about the group's
evaluation state changed; and pausing a pipeline that is already absent would leave a pause entry that outlives
its return and silently holds it back.

A stale-declaration upsert item SHALL still be dropped rather than archived: its rows were computed against
a superseded `generation` and must never be written, so preserving them would preserve a result that is
no longer valid.

A work item whose payload carries a **pipeline generation of `0`** SHALL be treated as a distinct **pre-fold** case
on every lane, and SHALL NOT be dropped by the stale-declaration guard. `0` is the value a legacy item deserializes
to: its payload names `evaluatorVersion` rather than `pipelineGeneration`, and the job scheduler's serializer
ignores unknown properties rather than failing on them, so the retired member is discarded and the new one left at
its default. `0` is never a generation ADAS serves, so the reading is unambiguous.

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

#### Scenario: A computed write survives its pipeline leaving the cache mid-flight

- **GIVEN** an upsert item holding a computed label, and its pipeline deleted in ADAS or refused by the runner
  before the write executes (so the runner has evicted it from the cache)
- **WHEN** the write item runs
- **THEN** the item is archived to the DLQ with its payload and `upsert-rows` task name, nothing is written to
  ADAS, the group's `computed_version` is unchanged, and the pipeline is not paused

#### Scenario: The archived write is recoverable without a model call

- **GIVEN** such an archived item and the pipeline back in the cache
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

#### Scenario: Work of a stopped pipeline is held, not dropped

- **GIVEN** chunk, group and write items queued for a pipeline that a sync has cached as stopped
- **WHEN** each becomes due
- **THEN** none is dropped, archived, or counted as a failed attempt, no DIAL call and no ADAS write is made, and
  each remains queued

#### Scenario: A write refused because the stop has not been synced yet is held

- **GIVEN** an upsert item holding a computed label, whose pipeline was disabled in ADAS after its model call and
  before the runner's next sync
- **WHEN** the write executes and ADAS answers HTTP 409 `pipeline_conflict`
- **THEN** the item is held without consuming an attempt, nothing is dead-lettered, its group is not stamped, the
  DLQ circuit breaker records nothing, and the write lands once the pipeline is re-enabled

#### Scenario: Held work of a stopped pipeline runs once it is re-enabled

- **GIVEN** such held items, built at the pipeline's current `generation`
- **WHEN** the pipeline is re-enabled in ADAS, which leaves its `generation` unchanged, and a sync caches it as
  active
- **THEN** each item executes normally with its full attempt budget, and no chunk is dropped as stale

#### Scenario: A re-declaration while stopped still makes held work stale

- **GIVEN** a chunk held for a stopped pipeline at generation 4, and the pipeline re-declared at generation 5
  while stopped
- **WHEN** the pipeline is re-enabled and the chunk becomes due
- **THEN** the chunk is dropped by the stale-declaration guard without calling DIAL or upserting
