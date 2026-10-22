## 1. Feed DTOs

- [x] 1.1 Add `TransformDto` to `adas/dto`: `type`, `model`, `preset`, `params`, `request_template`,
      `inputs` (a `Map<String, VarDto>`) and `outputs`. Read only what this service executes; ADAS's
      authored-only members (`output_vars`, `transform.response_schema`) need no field here — the feed
      serves the resolved schema at the top level.
- [x] 1.2 `PipelineDto`: drop `evaluator`, the top-level `evaluator_version` and the top-level `vars`;
      add `transform` and a top-level `response_schema`. Add **no** `contract` member — the version
      travels as the `Adas-Contract-Version` header, which this service already sends and verifies.
- [x] 1.3 Delete `EvaluatorVersionDto`. Rename `EvaluatorType` → `TransformType` and `EvaluatorPreset` →
      `TransformPreset`, keeping their members and their lowercase wire spellings (the
      case-insensitive enum read in `JsonMapperConfiguration` already covers them).
- [x] 1.6 Finish the rename: `EvaluatorInvoker` → `TransformInvoker`, with its field at every injection
      site. Missed by 1.3, and the one class whose whole job is to run the transform. **Leave**
      `DialRequestBuilder`'s `json_schema.name = "evaluator_output"` alone — it is a label on the DIAL
      request, not an ADAS contract member, and moving it would change what this service sends DIAL Core
      for no reason on either side.
- [x] 1.4 Keep `VarDto` and `OutputDto` exactly as they are — only the path to the var map changed.
- [x] 1.5 Update `PipelineDto`'s javadoc: it currently explains the second vocabulary the previous fold
      removed, and now has to explain that the transform is the authored declaration rather than a
      resolved registry object — and that **`transform.inputs` is the variable map while the top-level
      `inputs` is the read source table**, a collision this rename creates. They differ by JSON type
      (object vs array), so a misread fails to bind rather than binding to the wrong thing.

## 2. The contract version

- [x] 2.1 Raise `AdasContract.VERSION` from 1 to 2. **This is the whole of the deploy-order work.**
      `2026-09-16-refuse-skewed-adas-contract` already ships the header on every call, the echo
      verification in one place on the shared HTTP client, the incompatible classification and the
      stall-rather-than-steer behaviour. Because the echo is checked *before* the body is interpreted,
      an old build meeting the new feed never renders a template — the expensive failure needs no new
      guard.
- [x] 2.2 Add **nothing else**: no `contract` query parameter on the feed request, no handling of a 426
      `feed_contract_unsupported`, no body-member echo to verify. An earlier draft of this change
      specified all three; ADAS withdrew the producer half as well, and each was either a
      duplicate of the header or contradicted a shipped rule (design R2). If a task list resurfaces
      asking for them, it is the stale draft.
- [x] 2.3 `PipelineSyncService`: select the lane by `transform.type` (`class1-materialize` for `sql`,
      `pipeline-scan` otherwise), replacing the read of `evaluator.type`. The routing rule itself is
      unchanged, including the re-route when a pipeline's type changes — which is now reachable by an
      ordinary declaration patch rather than by repointing at a different evaluator, so it is more
      likely than before, not less.

## 3. Refusal arms

- [x] 3.1 The response-schema refusal tests the **top-level** `response_schema` and
      `transform.type == llm`, replacing `evaluator.response_schema` and `evaluator.type`. Keep it
      scoped to an explicitly `llm` transform rather than "anything not Class-1".
- [x] 3.2 The empty-`outputs` refusal is unchanged — `outputs` did not move — but re-check its scenario
      text, which names the inlined evaluator in passing.
- [x] 3.3 **Retarget the missing-field arm in `PipelineRouting.unsupportedReason`, do not delete it.** It
      refuses an entry carrying no inlined `evaluator`; it must refuse an entry carrying no
      **`transform`**. Left testing `evaluator` it becomes permanently true after the fold and refuses
      **every** pipeline; deleted, the gap it was built to close reopens. That requirement
      (`Admission refuses a pipeline missing any field its lane dereferences`) was written for this
      change and names it by name — it is the one arm this change cannot treat as incidental.
- [x] 3.4 Refuse a `sql` transform that declares `transform.inputs` — the member moved, the rule did
      not.
- [x] 3.5 **Add the two symmetric `llm` arms** the gate never had: refuse an `llm` transform declaring no
      `request_template`, and one declaring no `model`. 3.4 and the sql-declares-a-model arm refuse the
      Class-1 lane for carrying members it must not; nothing refused the model-calling lane for omitting
      the two it cannot run without, so such a pipeline was cached as healthy and then dead-lettered
      **every row** with an NPE at `output_map` — `TemplateRenderer` returned early from its placeholder
      assertion on a null template and then called `replace` on a null string. Scope both arms to a
      transform that is explicitly `llm`, so the Class-1 lane (which legitimately declares neither) is
      untouched. ADAS is adding a write-time refusal for the same shape; the runner must not depend on it
      (design R8).

## 4. Rendering and the placeholder assertion

- [x] 4.1 `InputMapper` / `ChunkBuilder`: read the variable map from `transform.inputs`. At row grain
      the keys are top-level placeholders; at group grain they are member-object fields under
      `{{members}}`. Neither reading changes — only where the map is fetched from.
- [x] 4.2 Assert at render time that every `{{placeholder}}` in the rendered body was substituted.
      **Never** render an unsubstituted placeholder and never substitute empty string for an unbound
      variable: both turn a detectable fault into a charged call and a stored label.
- [x] 4.3 Fail the **work item**, not the lane, naming the offending placeholder, and route it to the
      DLQ by the existing unexecutable-item path. One malformed row must not stop a pipeline that is
      succeeding on the rest; a broken declaration fails every item, which the DLQ makes obvious.
- [x] 4.4 Assert the failure happens **before** the DIAL call, so no spend is recorded — the existing
      accounting already records nothing for a work item that never called.
- [x] 4.5 Keep the five built-in member variables (`members`, `members_truncated`, `members_included`,
      `members_omitted`, `members_notice`) exempt from the assertion at group grain: they are supplied
      by the group renderer rather than by `transform.inputs`.
- [x] 4.6 Make substitution **single-pass** over the template with `Matcher.appendReplacement` +
      `Matcher.quoteReplacement`. The per-variable `String.replace` loop re-scanned text it had already
      written, so a bound value containing `{{other}}` had it substituted once `other` came round in the
      map — making the class's own "row content is passed through untouched" claim true only for names
      nothing bound, and making a row's rendering depend on iteration order (design R9). Fail a null
      template by name rather than by NPE while here; 3.5 makes it unreachable from the feed.

## 5. Write-back

- [x] 5.1 Stamp `pipeline_generation` from the entry's `generation` and stop sending
      `evaluator_version`. ADAS refuses any row naming the old spelling with 422, whether or not
      `pipeline_generation` is beside it, so sending both is not a safe hedge — it fails the batch.
- [x] 5.2 Sweep every payload builder for the old spelling: the scan lane, the group lane, the Class-1
      materialize callback and the backfill path. One missed site fails every write it makes.
- [x] 5.3 Assert by test that no request body this service sends carries `evaluator_version`.
- [x] 5.4 Do **not** describe the 422 as the deploy detector. `/rows` already requires the contract
      header and refuses a stale build with 409 before any validation, so the 422 is unreachable for
      that caller. It is simply a rule to obey (design R3), and a test asserting 422 from a
      deliberately stale build would observe 409 instead.

## 6. Runner state re-keying

- [x] 6.1 Widen the version argument of **all three** `DeterministicId` methods — `chunkInstanceId`,
      `backfillChunkInstanceId` and `groupInstanceId` — from `int` to `long`, and pass the pipeline's
      `generation`. `PipelineDto.generation` and `PipelineCacheEntryDto.generation` are already `long`,
      so only these signatures and the two Postgres columns are narrow.
- [x] 6.2 Write migration **V9** (the next free version after V8 — a duplicate Flyway version never
      conflicts in version control and instead fails every test at startup): rename
      `dlq_item.evaluator_version` and `spend_counter.evaluator_version` to `pipeline_generation` and
      widen both to `BIGINT`. The partial unique index `dlq_item_row_identity_uk` and `spend_counter`'s
      primary key follow the renamed column — neither needs dropping and recreating.
- [x] 6.3 **Rewrite no row.** A pre-cut-over value records which evaluator version produced that item;
      no generation can be invented for it after the fact. Say so in the migration header comment,
      which Flyway checksums — never edit it after it is applied anywhere.
- [x] 6.4 Confirm `run_history` and `group_state` need nothing. Checked: `group_state`'s `group_version`
      and `computed_version` are **source-data** versions (epoch millis from the source's version
      column) and `run_history`'s window is a source-version window — neither is an evaluator version.
      `pipeline_cursor` is keyed by pipeline name alone. So `dlq_item` and `spend_counter` are the only
      two tables the fold re-keys.
- [x] 6.5 Update `DlqItemEntity`, `DlqItemWriter`, `DlqItemDto` and `SpendService`'s SQL constants to
      the renamed column.
- [x] 6.6 **Drop** `evaluator_version` from `PipelineCacheEntryDto` rather than re-populating it from
      the generation: that response already carries `generation`, and re-populating would report one
      number twice under two names.
- [x] 6.7 Check the DLQ requeue path (design Open Question 1): if it deserializes an archived chunk's
      `payload` directly, a pre-cut-over archived chunk requeued after the deploy describes a
      declaration shape this build cannot read, and must be refused on requeue rather than re-run. This
      is the one item in the change that can bite after the window closes rather than during it.
      **Checked: it does.** Refused at the boundary, for the single and the bulk path.
- [x] 6.8 **Tolerate a pre-fold work item rather than dropping or dead-lettering it** (design R7). Add
      `worker/PreFoldWorkItem` carrying the `0` sentinel, the lane enum and the WARN + metric reporting,
      and wire it per lane:
      **chunk** (`WorkerService`) and **write** (`UpsertService`) *adopt* —
      `payload.setPipelineGeneration(pipeline.generation())`, refresh the MDC field, continue — with the
      arm placed **after** the pipeline is resolved from the cache and **before** the stale guard, which
      is the only position where there is a generation to adopt and the drop has not happened yet;
      **group** (`GroupWorkerService`) *discards* ahead of every guard, with no `dlq_item` row, because
      the readiness sweep re-derives it and its in-flight dedup (keyed on a generation-bearing instance
      id) cannot see a pre-fold item — so running it would buy two model calls for one group.
      Count `runner.workitem.prefold{lane}` on all three and never touch the DLQ circuit breaker.
      **Adoption, not "skip the stale check":** the payload's generation also builds the derived
      `UpsertPayload`, stamps `dlq_item_row_identity_uk`, keys failure spend and is an MDC field, and a
      leaked `0` breaks each — most seriously by making any later dead-letter permanently un-requeueable.
- [x] 6.8a **Strip the retired provenance column from a pre-fold write's rows, and add nothing back**
      (design R7). ADAS refuses a row naming `evaluator_version` with 422; `pipeline_generation` is
      deliberately *not* put in its place, because those rows were computed under the pre-fold
      declaration and the adopted generation names one that did not produce them. Verified on the ADAS
      side that an absent provenance column is accepted (`EnrichWriteValidator` requires only permitted
      columns plus one declared output; `EnrichWriteService.dropAbsentProvenance` only removes) and that
      nothing reads a generation stamp back. Read `payload.getRows()` **after** the arm, not before.
- [x] 6.8b **Remove `DlqStage.PRE_FOLD` and migration `V10`.** No lane dead-letters a pre-fold item any
      more, so the stage has no writer. `V10` was never released, so deleting the file beats shipping a
      CHECK widening for a value nothing writes.
- [x] 6.9 **Keep `DlqRequeueService.foldEra`, and say in its javadoc what still reaches it.** Re-examined
      after 6.8: no lane archives a pre-fold work item now, so the question is whether the refusal is
      dead. It is not, and two producers remain — (a) `dlq_item` rows the **0.2 build archived before the
      cut-over**, which a deploy does not clear and which name `evaluatorVersion` and no
      `pipelineGeneration` at all (the common case, and the one that outlives the window until the 30-day
      sweep); and (b) a pre-fold **write whose pipeline is absent from the cache**, which `UpsertService`
      archives with its rows *before* there is a live generation to adopt, so its stored payload carries
      the member at `0`. Both arms of the test are therefore kept: absent **or** `0`. An existing test
      asserts the refusal string contains `pipeline_generation`.

## 7. Tests

- [x] 7.1 Deserialization test against a captured **new-shape** feed response — ideally the fixture
      ADAS's own wire-shape test pins, so the two repositories agree through an artefact rather than
      through prose.
- [x] 7.2 Contract tests: an ADAS echoing `1` while this build serves `2` is incompatible, the cache is
      retained, the metric is counted and health stays up; an ADAS sending no echo is incompatible the
      same way; a matching echo is accepted. These extend the existing skew tests rather than adding a
      parallel set — there is no 426 and no body member to test.
- [x] 7.3 Placeholder-assertion test: a pipeline whose template names a variable its
      `transform.inputs` does not bind fails the item before any DIAL call, names the placeholder, and
      records no spend. Assert the DIAL client is never invoked — that is the whole point.
- [x] 7.4 Lane-routing test: `transform.type: sql` routes to `class1-materialize` and `llm` to
      `pipeline-scan`, and a type change re-routes.
- [x] 7.5 Admission test: an entry carrying no `transform` but with `outputs` present is refused naming
      the absent transform — the retargeted arm from 3.3, and the regression that would otherwise refuse
      every pipeline or none.
- [x] 7.6 Write-back test: the payload carries `pipeline_generation` and no `evaluator_version`.
- [x] 7.7 State test: a pipeline's **cursor and group state** survive a sync that re-reads every
      declaration in the new shape — those are keyed by pipeline name and must be untouched.
- [x] 7.8 Migration test: after V9 both columns are named `pipeline_generation` and typed `BIGINT`, the
      partial unique index and the primary key still hold, and pre-existing rows keep their values.
- [x] 7.9 Re-key test: a declaration edit that changes only a knob (`rate_rpm`) bumps `generation` and
      therefore produces different deterministic instance ids and a new `spend_counter` row — and the
      daily pause gate still sees the day's full total, because it sums across that key component.
      Assert the sum, not just the row: this is the behaviour whose opposite would be a money bug.
- [x] 7.10 Build fixtures from synthetic declarations modelled on the real shapes — do not check real
      prompts or declarations copied from a running deployment into test resources.
- [x] 7.11 Pre-fold tests. Seed the **actual pre-fold JSON** (naming `evaluatorVersion`) and deserialize
      it through db-scheduler's own `JacksonSerializer`, not this repo's mapper — the queue is what reads
      these payloads at runtime. Assert it yields `pipelineGeneration = 0` rather than failing, on all
      three payload types: that assertion is what makes the whole hazard real and it is kept whatever
      else changes (`PreFoldWorkItemTest`). Then, per lane: a chunk and a write **adopt the live
      generation and proceed**, counting the metric and writing no `dlq_item`; the `UpsertPayload` the
      chunk lane derives carries the **adopted** generation, not `0`; a pre-fold write's rows reach
      `AdasUpsertClient` with `evaluator_version` **removed** and `pipeline_generation` **not added**; and
      a pre-fold group item is **discarded with no DLQ row**, no stamp and no breaker count. Assert too
      that an adopted chunk whose old inputs cannot render the current template fails before any DIAL
      call with no spend and dead-letters at `input_map` carrying the adopted generation — that is the
      bound on the tolerance — and that an ordinary **stale** chunk still drops silently, or the new arm
      has swallowed the old one.
- [x] 7.12 Admission tests for 3.5: an `llm` transform with no `request_template` and one with no `model`
      are each refused by name, and a `sql` transform carrying neither is still admitted — the
      regression that would refuse every Class-1 pipeline.
- [x] 7.13 Renderer test for 4.6: a bound value containing another bound variable's placeholder survives
      verbatim, in either input order, and a `$` or backslash in a value is content rather than
      replacement syntax.

## 8. Spec hygiene (listed so it is not undone)

- [x] 8.1 All four delta requirements in an earlier draft named requirements that **do not exist** in
      `openspec/specs/` — `Pipeline sync polls ADAS and reconciles the schedule`, `The runner refuses a
      pipeline it cannot execute`, `The request template is rendered from the transform's bound inputs`
      and a MODIFIED `Pipeline routing by transform type`. On archive they would have appended as new
      requirements while leaving the stale originals in place. The real bases are `Pipeline registry
      sync`, `Pipeline admission gate`, `Recurring task reconciliation`, `Admission refuses a pipeline
      missing any field its lane dereferences`, `Every ADAS call declares a contract version…`, the
      `enrichment-worker` set, and a **RENAMED** `Pipeline routing by evaluator type` →
      `Pipeline routing by transform type`. Re-run a main-vs-delta name and scenario diff before
      archiving; a MODIFIED requirement replaces its base wholesale, so a wrong or stale base is a
      silent revert the validator does not catch.
- [x] 8.2 The change now covers eight capabilities, not three. The five added because the fold makes
      them false — not merely dated — are `enrichment-worker` (provenance columns and the
      stale-declaration guard), `incremental-scan` (input mapping and chunk identity),
      `group-enrichment` (assembly, provenance and group identity), `resilience-and-dlq` (DLQ row
      identity) and `spend-tracking` (counter key). Do not drop them for size.
- [x] 8.3 `openspec/specs/backfill/spec.md`'s `## Purpose` overview calls the version window "the
      mechanism for re-applying an evaluator-version bump to a range". No *requirement* in that
      capability is falsified, and a delta cannot carry overview prose, so this needs a direct edit at
      archive time. Same for `class1-enrichment`'s and `enrichment-worker`'s overview lines naming the
      evaluator.

## 9. Roll-out

- [ ] 9.1 **Scale this service to 0 — recommended, not required.** That is the whole of the first step:
      no pause, no queue-depth wait, no API call, no SQL. The upgrade is limited to deploying,
      undeploying, scaling and updating services, and a deploy must not block on a queue emptying.
      *Recommended rather than required*, because a skew is refused **and held** rather than absorbed:
      `AdasWorkGate.requireCompatible` throws `AdasIncompatibleHoldException`, which reschedules the
      work item with no attempt consumed and nothing archived, and ADAS's data plane answers 409 before
      any lookup. A misordered deploy stalls and self-heals once both halves are up. A *required* window
      would be needed only while an operator drain of the queue was part of the upgrade; task 6.8
      replaces that drain with code.
      *Still recommended, and here is the cost of skipping it*: during the ADAS roll both ADAS builds
      serve concurrently, so this service left running can read a *compatible* echo from an outgoing
      pod, start work, pay for a model call, and then have its write refused 409 by a new pod. That
      work is held rather than failed, and 6.8's pre-fold adoption writes it after the upgrade —
      **recovered, not lost**. What it costs is a deferred write and a compatibility signal that flaps
      for the length of the roll: noise, not damage.
      Scaling to 0 stops execution but does **not** empty `scheduled_tasks`, and that is deliberately
      left alone. A work item enqueued before the window carries a payload naming `evaluatorVersion`,
      and db-scheduler's `JacksonSerializer` **disables** `FAIL_ON_UNKNOWN_PROPERTIES` (verified in
      `db-scheduler-15.6.0`), so the new build deserializes it **silently** with
      `pipelineGeneration = 0` — never a generation ADAS serves. **Task 6.8 is what handles it**, in
      code and with no operator involvement: a carried-over chunk and write are adopted onto the live
      generation and run (the write with the retired provenance column stripped, per 6.8a), and a
      carried-over group item is discarded to the readiness sweep. Nothing is dead-lettered.
      A shutdown-drain was considered and rejected: it would have to live in the **previous 0.2 release,
      which does not have it**, and executions SIGKILLed at shutdown come back as pre-fold items on
      the next start regardless.
      **The one *hard requirement* of this roll-out is a sentence, not a step, and it belongs to ADAS:
      issue no pipeline write — `POST`, `PATCH` or `DELETE` on `/v1/pipelines` — while ADAS is
      rolling.** Nothing guards it: an outgoing ADAS pod handling any pipeline write rewrites the
      folded `spec` without its `transform`, and `maxUnavailable: 0` guarantees such a pod exists for
      the length of the roll. It is recorded here because the same operator runs both services.
      **A weaker caution extends past the ADAS roll: do not edit a pipeline declaration while the
      window is open**, meaning for as long as carried-over work is still draining. A carried-over item
      is labelled under whatever declaration is current when it runs, so an edit inside the window
      could mean a newer prompt, a repointed variable or a `filter` that would no longer have selected
      those rows. All three were equally true of the 0.2 build that enqueued the item; only a repointed
      `target` is new (design R7).
- [ ] 9.2 ADAS deploys `fold-evaluator-into-pipeline`. Deploying ADAS before this service is the
      **recommended** order and not a requirement — the reverse is held and self-heals (9.1) — but
      9.1's no-pipeline-write requirement applies for the whole length of this step either way.
- [ ] 9.3 While ADAS is ahead, confirm the **old** image reads `Adas-Contract-Version: 2`, classifies
      the feed incompatible, logs once per transition, publishes the metric and evaluates nothing. Its
      data-plane calls will also answer **409** — expect both, so neither reads as a second fault.
- [ ] 9.4 Deploy this service (migration V9 runs at startup) and scale back to **1**. Confirm the
      first poll validates, the first scan resumes from its existing cursor, and the first write-back lands
      `pipeline_generation`.
- [ ] 9.5 Confirm `pipeline_cursor` and `group_state` are unchanged across the window — no
      re-derivation.
- [ ] 9.6 Keep this change and ADAS's `fold-evaluator-into-pipeline` consistent: the wire facts live in
      both, and ADAS's wire-shape test is what they agree through. A change to either side's shape is a
      change to both documents, and ADAS's `RunnerContractShapeTest` fails its build if the projection
      moves without the version being raised.
- [x] 9.7 `./gradlew clean build` passes.
- [ ] 9.8 Read `runner_workitem_prefold_total{lane}` once the queue has caught up. A non-zero total
      **during** the window is expected — it counts work that crossed the fold, not failures — and the
      lane tag says what became of it. What matters is that it stops rising and stays flat afterwards.
      Watch dead-letters at stage `input_map` on the chunk lane over the same period: that is how a
      carried-over chunk fails if the current template names a variable its old inputs do not bind, and
      a burst of them can auto-pause the pipeline until `DLQ_BREAKER_PAUSE_FOR` elapses (design R7).

## 10. Release documentation

- [x] 10.1 Draft the 0.3 upgrade note, matching the structure and tone of
      `release-0.2-upgrade.md`: contract version 2, migration V9, the breaking declaration and
      provenance-column changes, and — most importantly — **the roll-out procedure from 9.1**, which
      otherwise lives only in this task list and is archived with the change. Its §2 says explicitly
      that no pre-deploy action is required, what the build does with a carried-over item on each lane,
      and the "do not edit a declaration in the window" caution; its §1 carries the one hard rule — no
      pipeline write while ADAS is rolling — folded into the ADAS step as a warning. It stays an
      operator runbook: terse steps and warnings, with the required-versus-recommended levelling and
      the cost-of-skipping-the-quiesce argument left in 9.1 and design R2/R7. Published as
      `docs/release-0.3-upgrade.md` with the 0.3 release rather than with this change: an upgrade note is
      release-scoped.
- [x] 10.1a Publish `docs/release-0.3-upgrade.md` when 0.3 is cut. It is the one part of this change
      that cannot land with it, and the roll-out procedure in 9.1 is archived with this change, so
      without the note that procedure survives nowhere a future operator will look.
- [x] 10.2 Do **not** edit `docs/release-0.2-upgrade.md`. Its "`Adas-Contract-Version: 1`" and "the
      migration set is unchanged from 0.1.2 (`V1`–`V8`)" are now false *of the product* and still true
      *of that release*, which is what a release-scoped upgrade note records.
- [x] 10.3 Update `docs/e2e-runbook.md`, which is stale in exactly the way that breaks this change's own
      verification: it registers an evaluator at `POST /v1/evaluators` (now 405), declares an
      `evaluator_version` provenance column on the group target, tells the operator to confirm
      `evaluator_version` is populated and bumped, and queries `spend_counter` by `rule_id` and
      `evaluator_version`. Move the registration bodies onto the nested `transform`, the provenance column
      to `pipeline_generation`, and mark the V8 cut-over appendix's capture SQL with the schema era it is
      written against.
