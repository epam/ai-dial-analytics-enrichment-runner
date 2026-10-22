## Why

ADAS's `fold-evaluator-into-pipeline` dissolves the evaluator registry into the pipeline declaration. The
feed stops carrying an inlined `evaluator` object and starts carrying the authored `transform` block the
operator wrote, with the transform's inputs renamed from the top-level `vars` to `transform.inputs` so
that the block's two halves — what the transform reads and what it writes — read as a pair.

This repository consumes exactly those members, so it catches up or stops syncing. The upstream change
is deliberately **not** shaped to avoid this: an earlier draft of it kept synthesizing an `evaluator`
object purely so no runner redeploy would be needed, and that was dropped once the `transform.inputs`
rename made a coordinated release necessary anyway. Paying for one release and keeping a permanent
second spelling of the feed's largest payload — a `request_template` is multi-kilobyte and this feed is
polled — was the worse trade.

The second half of this change is the part that is ours to get right rather than ours to follow, and it
is **not** the deploy handshake. That already exists: `2026-09-16-refuse-skewed-adas-contract` shipped
`AdasContract` and the requirement that every ADAS call declares `Adas-Contract-Version` and every
response is checked for its echo, with an incompatible ADAS stalling the runner rather than steering it.
Raising that constant to 2 is all this change owes the ordered deploy. What is genuinely ours is
narrower and was missed by an earlier draft: **`evaluator_version` is not only a provenance column this
service sends, it is a component of this service's own state keys** — the deterministic work-item
instance id, the DLQ row-identity index and the `spend_counter` primary key. The fold leaves no evaluator
version to put there, and the only candidate, the pipeline's `generation`, does not behave the same way.

## What Changes

- **BREAKING (DTOs).** `PipelineDto` drops `evaluator` and the top-level `evaluator_version` and `vars`,
  and gains `transform` (a `TransformDto` carrying `type`, `model`, `preset`, `params`,
  `request_template`, `inputs` and `outputs`) plus a derived top-level `response_schema`.
  `EvaluatorVersionDto` is deleted. `EvaluatorType` and `EvaluatorPreset` are retained as the enums of
  `transform.type` and `transform.preset` — the concept moved onto the declaration, the vocabulary did
  not change — and are renamed to `TransformType` / `TransformPreset` to match. **No `contract` member is
  added**, and no `contract` query parameter is sent; see the next bullet.
- **`AdasContract.VERSION` is raised from 1 to 2**, and that is the whole of this change's deploy-order
  work. The mechanism already ships on both sides: ADAS echoes `Adas-Contract-Version` on every response
  under `/v1/pipelines` (the feed included) and requires an exact match on the four data-plane endpoints
  with 409; this service already sends the header on every call and already classifies an absent or
  differing echo as an incompatible ADAS whose body it will not interpret. Because the echo is checked
  **before** the body, an old build meeting the new feed never reads it — so the expensive failure
  (binding no variables, rendering literal `{{placeholder}}` text, paying for the call, storing the
  answer as a label) is unreachable without any new mechanism. An earlier draft of this change specified
  a mandatory `contract=N` query parameter, an ADAS **426** `feed_contract_unsupported` and a body echo
  to verify; all of it is **withdrawn** as a duplicate of the header, and ADAS's change withdrew the
  producer half in the same review.
- Lane routing, refusal arms and provenance all read their fact from a new place, none of which changes
  what they decide: the lane is selected by `transform.type` instead of `evaluator.type`, the
  response-schema refusal tests the top-level `response_schema` instead of `evaluator.response_schema`,
  and the template's variables come from `transform.inputs` instead of `vars`.
- **The missing-field admission arm is retargeted, not left alone.** `Admission refuses a pipeline
  missing any field its lane dereferences` currently refuses an entry carrying **no inlined
  `evaluator`** — and was written for this change, naming it as the case its sibling arm does not cover.
  Left as-is it becomes permanently true after the fold and refuses **every** pipeline; removed, the gap
  it was built to close reopens. It now refuses an entry carrying no **`transform`**.
- **The write-back stamps `pipeline_generation`, from the pipeline's own `generation`, and stops sending
  `evaluator_version`.** ADAS refuses a row naming `evaluator_version` with HTTP 422. That refusal is
  **not** a deploy detector on this path — `/rows` already requires the contract header, so a stale
  build is refused 409 first — it is the ordinary rule that a retired spelling is refused rather than
  absorbed, and it means sending both spellings "as a hedge" fails the batch.
- **BREAKING (runner state keys).** `generation` replaces the evaluator version wherever this service
  keys its own state on it, and the consequences are stated rather than discovered:
  `DeterministicId.chunkInstanceId` / `groupInstanceId` / `backfillChunkInstanceId`,
  `dlq_item_row_identity_uk (pipeline_name, grain_key, evaluator_version)`, and `spend_counter`'s
  primary key `(pipeline_name, evaluator_version, day)`. Two properties change, neither cosmetic:
  `generation` is a **64-bit** counter where these are `INT`/`int`, and `generation` moves for **any**
  declaration edit — `cadence`, `filter`, `rate_rpm`, `trigger` — where an evaluator version moved only
  for a prompt or expression change.
- **A pre-fold work item is tolerated on all three lanes, not dropped and not dead-lettered.** Scaling to
  zero stops execution; it does not empty `scheduled_tasks`. An item enqueued by the pre-fold image
  deserializes with `pipelineGeneration = 0` rather than failing, because db-scheduler's serializer
  ignores unknown properties — and every lane's stale-declaration guard then dropped it at `info`, losing
  rows the cursor had passed on the chunk lane and a paid-for result on the write lane. `0` is never a
  generation ADAS serves, so it is now recognised as its own case and handled per lane: the **chunk** and
  **write** lanes *adopt* the pipeline's live `generation` onto the payload and run the item; the **group**
  lane *discards* it with no dead-letter row, because a group item is keys only and the readiness sweep
  re-derives it. A pre-fold write additionally has the retired `evaluator_version` provenance column
  stripped from its rows, with nothing put in its place. Each is logged once at WARN and counted on
  `runner.workitem.prefold{lane}`; none writes a dead-letter row and none counts against the circuit
  breaker. **There is no pre-upgrade drain step.** Draining would have to live in the previous 0.2
  release, and an execution SIGKILLed at shutdown returns as a pre-fold item on the next start regardless —
  so the tolerance is the mechanism, not a backstop behind a procedure.
- **Two missing admission arms on the `llm` side.** The gate refused a `sql` transform for declaring a
  model or `transform.inputs`, and refused nothing for an `llm` transform that omits `request_template`
  or `model`. Such a pipeline dead-lettered every row with an unclassified render error at `output_map`
  after being cached as healthy; it is now skipped at sync with a named reason.
- **A render-time placeholder assertion**, independent of any version number: before a call is made,
  every `{{placeholder}}` the template carries must resolve against the variables actually bound for
  that unit of work, and a lane that cannot satisfy it fails the work item rather than calling the
  model. A version handshake catches "wrong build"; this catches "right build, wrong declaration" — a
  template edited to name a variable nothing binds, which ADAS now permits to be *stored* because it
  validates placeholders at arm and preview rather than on every write. It is the cheapest possible
  guard on the only failure that costs money before it is visible.

Explicitly **out of scope**: any change to scan paging, group-assembly bounds, DLQ retry policy,
backfill windowing or spend *policy*; and reading the authored projection, which carries no resolved
member this service can execute. Note that this is narrower than an earlier draft claimed: that draft
put "the DLQ" and "spend" wholly out of scope and asserted "`spend-tracking` is **not** modified", which
is false — both are keyed on the member that is being retired.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `pipeline-sync`: the feed's transform is read from `transform` rather than from an inlined `evaluator`
  object, its inputs from `transform.inputs`, and its response schema from a derived top-level member;
  the lane selector and every refusal arm read their fact from the new members; the missing-field arm is
  retargeted from `evaluator` to `transform`; two symmetric arms are added for an `llm` transform that
  declares no `request_template` or no `model`; and the shipped contract requirement records the raise to
  version 2 and states that no query parameter or body member is added for it.
- `enrichment-worker`: the request template renders from `transform.inputs`; an unresolved placeholder
  fails the work item instead of reaching the model; the provenance columns written are
  `pipeline_generation`, `model` and `enriched_at`; the stale-evaluator guard becomes a
  stale-**declaration** guard keyed on `generation`, which now drops a computed item after a knob edit
  that would previously have left it alone, and gains a pre-fold arm ahead of it that normalises the
  payload onto the live generation (chunk and write) or hands the item back to the readiness sweep
  (group) rather than dropping it; and template substitution becomes single-pass, so a bound value
  containing another variable's placeholder is data rather than a second substitution.
- `class1-enrichment`: the Class-1 lane is selected by `transform.type: sql`, reads its expressions
  through the same `outputs` mapping as before, and its re-derivation story is restated — patch the
  transform, which bumps `generation`, then backfill.
- `incremental-scan`: input mapping reads `transform.inputs`, and the deterministic chunk instance id is
  keyed on `generation`.
- `group-enrichment`: group input assembly applies `transform.inputs` to each member row, group
  provenance writes `pipeline_generation`, and the group work-item identity is keyed on `generation`.
- `resilience-and-dlq`: the DLQ row identity is keyed on `generation`; a pre-cut-over row and a
  post-cut-over one for the same grain are no longer comparable (they usually sit side by side, and
  collapse onto one where the two eras' values coincide); a work item enqueued before the fold reaches
  the DLQ on **no** lane, so no stage is added for it; and the requeue path still refuses a payload
  carrying no usable generation, which is what a row the 0.2 build archived before the cut-over holds.
- `spend-tracking`: `spend_counter` is keyed on `generation`. The daily pause gate is unaffected in
  effect, because it reads `SUM(...) WHERE pipeline_name = ? AND day = ?` and therefore sums across every
  key value for the day — verified in `SpendService`, and worth stating because the obvious fear (a knob
  edit silently resetting an exhausted daily budget) is the one thing that does **not** happen.
- `runner-ops`: the pipeline-cache introspection entry reports the pipeline's `generation` rather than an
  evaluator version.
- `observability`: a new counter, `runner.workitem.prefold{lane}`, pre-registered for all three lanes,
  counts work items that crossed the fold — adopted on the chunk and write lanes, discarded on the group
  lane. It is the **only** record of the event, because nothing is dead-lettered for it, and it is
  expected to rise once during the deploy window and stay flat on every lane afterwards.

## Impact

- **Upstream**: requires ADAS's `fold-evaluator-into-pipeline`. The two documents describe one wire and
  must stay consistent; ADAS's wire-shape test pins the serialized compiled projection both sides are
  written against, and its `RunnerContractShapeTest` fails ADAS's build if that shape moves without the
  version being raised.
- **Deploy**: one hard requirement and a recommended sequence around it. The requirement belongs to
  ADAS and is stated here because the same operator runs both services: **no pipeline write —
  `POST`, `PATCH` or `DELETE` on `/v1/pipelines` — while ADAS is rolling**, because an outgoing ADAS
  pod silently rewrites a folded `spec` without its `transform`. Nothing guards that. The recommended
  sequence: scale this service to 0, deploy ADAS, deploy this service, scale back to 1. The *ordering*
  is a recommendation rather than a requirement because misordering is refused **and held** rather
  than absorbed, by the contract version alone: an old runner against a new ADAS reads an echo of `2`,
  classifies the feed incompatible, stalls with its cached declarations and reports it; its data-plane
  calls are separately refused 409, and `AdasWorkGate.requireCompatible` throws
  `AdasIncompatibleHoldException`, which reschedules the work item with no attempt consumed and
  nothing archived. A skew therefore stalls and self-heals once both halves are up. A *required* ordered
  window would be needed only if an operator drain of `scheduled_tasks` were still part of the upgrade;
  design R7 replaces that drain with code, so what remains is one requirement plus a recommendation.
  The quiesce is still recommended, and the reason is a real cost rather than caution: during the ADAS
  roll both ADAS builds serve concurrently, so a running old runner can read a *compatible* echo from
  an outgoing pod, start work, pay for a model call, and then have its write refused 409 by a new pod.
  That work is held rather than failed, and this build's pre-fold adoption writes it after the
  upgrade — **recovered, not lost**. The cost is a deferred write and a compatibility signal that flaps
  for the length of the roll: noise, not damage.
- **Docs**: a 0.3 upgrade note is drafted, carrying contract version 2, migration `V9` and — the part
  that would otherwise be archived with this change — a roll-out procedure that is deploy order and
  nothing else, with a section saying explicitly that the work left in the queue needs no operator
  action, and the no-pipeline-write warning folded into its ADAS step. It stays an operator runbook:
  terse steps and warnings, with the required-versus-recommended reasoning left here and in the
  design. It is published as `docs/release-0.3-upgrade.md` when the 0.3 release is cut, because an
  upgrade note is release-scoped and publishing one before its release would describe an upgrade
  nobody can yet perform. `docs/release-0.2-upgrade.md` is release-scoped history and is **not** edited, even where
  it is now false. `docs/e2e-runbook.md` is updated for the folded registration bodies and the
  `pipeline_generation` provenance column.
- **State**: no row is deleted, but **the keys change**, which an earlier draft denied. `pipeline_cursor`
  and `group_state` are keyed by pipeline name alone and are genuinely untouched — there is no cursor
  loss and no re-derivation, and no repeat of `V8__rekey_and_discard_runner_state.sql`. `dlq_item` and
  `spend_counter` are not: both carry an `evaluator_version` column in their uniqueness key. Rows written
  before the cut-over keep small evaluator-version integers that nothing will produce again, so they are
  inert rather than corrupt — a pre-cut-over DLQ row can no longer collapse with a post-cut-over failure
  for the same grain, and will instead sit beside it until the 30-day sweep. Whether the two columns are
  **renamed** by migration or left holding a generation under a misleading name is the one open decision
  this change must make rather than inherit; see design R6.
- **Code**: `PipelineDto`, `TransformDto` (new), `EvaluatorVersionDto` (deleted),
  `EvaluatorType`/`EvaluatorPreset` (renamed), `AdasContract` (version raised), `AdasRegistryClient`,
  `PipelineSyncService`, `PipelineRouting`, `PipelineCacheMapper`, `PipelineCacheEntryDto`, `InputMapper`,
  `ChunkBuilder`, `DeterministicId`, `ScanPageCommitter`, `PipelineScanExecutor`, `GroupMarkBuilder`,
  `GroupReadinessSweep`, `GroupAdminController`, `BackfillExecutor`, `BackfillPageCommitter`,
  `WorkerService`, `WorkerMdc`, `ProvenanceColumns`, `DlqItemEntity`, `DlqItemWriter`, `DlqItemDto`,
  `SpendService`, the template renderer and the write-back payload builder. Added by review:
  `worker/PreFoldWorkItem` (new), `ProvenanceColumns.RETIRED_EVALUATOR_VERSION`, `RunnerMetrics`
  (`runner.workitem.prefold`), and the rename `EvaluatorInvoker` → `TransformInvoker`, which completes R5.
  No DLQ stage and no second migration: nothing dead-letters a pre-fold item any more.
- **Not impacted**: ClickHouse, blob storage, DIAL Core's own contract, and every already-written
  enrichment row.
