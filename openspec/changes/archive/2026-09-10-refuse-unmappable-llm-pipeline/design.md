## Context

`PipelineRouting.unsupportedReason` is the runner's single admission gate. `PipelineSyncService.admit`
calls it for every enabled pipeline on the feed; a non-null reason means the pipeline is neither cached
nor given a recurring task instance, is logged once per generation, and is counted in
`runner.pipelines.unsupported`.

The gate enumerates six combinations today. Five concern the group trigger; one concerns Class-1 (`sql`)
evaluators — including *"sql evaluator declares no output vars"*, the emptiness check that keeps a
materialize pipeline with nothing to derive off the schedule. There is no arm for the `llm` lanes.

What happens without it, on either `llm` lane (row × llm, group × llm):

1. `OutputMapper.map` iterates the pipeline's output bindings. With none, it returns an empty map.
2. `WorkerService` / `GroupWorkerService.buildRow` still builds a write row: the grain key, then
   `putAll(columns)` over the empty map, then `evaluator_version`, `model`, `enriched_at` and (group lane)
   `group_version`, `truncated`.
3. That row is enqueued for `upsert-rows` and written. ADAS accepts it — every column on it is permitted.
4. The target enrichment is a `ReplacingMergeTree` keyed on the grain. The row **replaces** whatever label
   sat at that key, with nulls.

Nothing in the chain reports a failure. No exception is thrown, no dead-letter row is written, the
circuit breaker sees a success, and the group lane stamps the group computed so the readiness sweep never
revisits it. The model call was made and charged before any of it. Compared with the `sql` arm's failure —
a pipeline that quietly does nothing — this one destroys data that was already paid for.

ADAS's `simplify-pipeline-authoring` refuses to serve such a pipeline on the feed at all, so after that
release the runner should never see one. That is a *remote* invariant guarding a *local* catastrophe, and
it stops holding the moment an old image is scaled back up against a new ADAS, or a future ADAS relaxes the
check. The arm belongs on both sides.

## Goals / Non-Goals

**Goals:**

- No `llm` pipeline that cannot produce a single label column is ever cached, scheduled, or fired.
- The refusal is visible through the paths the gate already has: the once-per-generation warn log, the
  unsupported gauge, and absence from `/v1/pipelines/cache`.
- The arm is correct against the feed vocabulary as ADAS serves it **today**, so it can ship on its own
  ahead of the fold, and so its meaning survives the fold as a one-line accessor rename.

**Non-Goals:**

- The vocabulary fold (`vars`, `outputs`, `advanced`, `view=compiled`). `read-folded-pipeline-feed`
  re-points this same emptiness check at `outputs`; nothing about the requirement changes there.
- Repairing rows an earlier run may already have blanked. The gate prevents future writes; it cannot know
  which historical rows were overwritten, and re-deriving them is a backfill decision, not a sync one.
- Any wider validation of the mapping: that a bound column exists on the target, or that a bound response
  property is declared by the schema. ADAS validates both at registration, and a second validator here
  would drift from the one that actually gates the registry. Only *emptiness* is checked, because only
  emptiness is what the runner can prove locally and is what turns a write into a deletion.

## Decisions

**1. The arm tests the pipeline's output bindings, not the evaluator's output vars.**

The two are different facts and only one of them decides whether a column is written. `OutputMapper`
iterates `output_bindings` and keys the result by each binding's `column`; the evaluator's `output_vars`
supply an optional JSONata transform per variable, and their absence just means "take
`response[var]` verbatim" — the common case, and perfectly runnable. An empty binding list is what
produces an empty column map, so that is what the gate tests.

This is also why the arm is not simply the `sql` arm's condition applied to both types: on the `sql` lane
the output vars *are* the derivation (ADAS evaluates their SQL inside ClickHouse), so there the vars are
the right thing to test. Post-fold ADAS resolves both into one `outputs` list and the two arms collapse
onto the same member — which is the point of the fold, and is why this change is worth landing in the
vocabulary it already has rather than waiting.

_Alternative considered:_ refuse when either list is empty. Rejected — it would refuse the common case of
outputs that declare no transform.

**2. Refuse at admission rather than fail at the worker.**

The alternative is a guard in `OutputMapper` or `buildRow` that throws when the column map is empty,
dead-lettering the row or group. It is strictly worse: the model call has already been made and charged
by then, every row of every page pays for it, and the DLQ fills with a defect that is a property of the
declaration, not of any row. The gate answers the question once per sync, before a single call.

_Alternative considered:_ both. Rejected as duplicate enforcement with no added coverage — nothing reaches
the worker that admission did not admit, since `WorkerService` and `GroupWorkerService` both re-read the
pipeline from the cache and drop the item when it is absent.

**3. Refuse, not "run with a warning".**

An unmappable pipeline has no useful degraded mode: with no output column, the only thing it can write is
the destructive row. Skipping the write and keeping the schedule would burn a model call per row forever
for nothing. Refusal is the only outcome that neither spends nor destroys, and the gate's existing
reporting is what makes it loud.

**4. Keyed off `isClass1`, not off `EvaluatorType.LLM`.**

The gate's existing arms already branch on `isClass1(pipeline)`, which is `evaluator.type == SQL`. The new
arm is the `else`: any pipeline the runner will route to a lane that makes a model call. Written that way,
a future evaluator type that also calls a model inherits the check instead of slipping past a
`type == LLM` test. A pipeline carrying no evaluator at all is left to the existing behavior — `isClass1`
is false for it, so it must not dereference the evaluator here.

## Risks / Trade-offs

- **An existing pipeline could be in this state and stop running on deploy.** → That is the intended outcome,
  and it is the loud version of what it was already doing silently. It is also checkable before the
  rollout — list the registry's enrich pipelines and confirm each declares outputs — and the refusal names
  the pipeline in a warn log on the first sync.
- **The arm's condition is written in a vocabulary that changes one change later.** → Deliberate: the
  hazard is present now and the follow-up change is a rename of the accessor the arm reads, covered by its
  own task and by the tests added here.
- **It does not catch every way a mapping can be wrong** (a bound column that does not exist on the
  target, a bound property the schema never declares). → Those are ADAS's registration checks, and they
  fail loudly at write time (ADAS rejects an undeclared column with 422) rather than silently. Only the
  empty case is silent, and only the empty case is guarded here.

## Migration Plan

Deploy on its own, ahead of ADAS's `simplify-pipeline-authoring` and ahead of
`read-folded-pipeline-feed`. No coordination, no data migration, no configuration change: the gate is
evaluated on the next poll after restart. Rollback is a plain image rollback — the previous runner reads
the same feed.

## Open Questions

None.
