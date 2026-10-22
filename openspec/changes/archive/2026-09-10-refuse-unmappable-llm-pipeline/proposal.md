## Why

The admission gate refuses a Class-1 (`sql`) pipeline that declares no output mapping, but there is no
equivalent arm for the `llm` lanes — and on those lanes the same defect is far more expensive. With no
output bindings, `OutputMapper.map` returns an empty column map, the worker still assembles a write row
from the grain key plus the five provenance columns, and that row is upserted. Against the target
enrichment's `ReplacingMergeTree`, an all-NULL label row at an existing grain key **replaces a label that
has already been paid for**. It is not a failure: nothing is dead-lettered, nothing is retried, the model
call was made and charged first, and the loss is silent — the worst failure mode in the system.

Now, because the folded ADAS pipeline feed (ADAS's `simplify-pipeline-authoring`) is about to rename
the member this mapping arrives in. ADAS's own half of the guarantee — it refuses to serve such a pipeline
on the feed at all — lands in the same release, but the runner must not depend on a remote invariant to
avoid a local catastrophe, and this arm is also the backstop if an old runner image is ever scaled back up
against a new ADAS. It is correct against today's feed and independent of the fold, so it ships first and
alone.

## What Changes

- `PipelineRouting.unsupportedReason` gains an `llm` arm: a non-Class-1 pipeline whose output bindings are
  absent or empty is refused with a reason naming the missing mapping, exactly as the `sql` arm already
  refuses an evaluator with no output vars. Refusal means the pipeline is neither cached nor scheduled, is
  logged once per generation, and is counted in `runner.pipelines.unsupported`.
- No other behavior changes. The gate stays conservative: it names one combination that provably cannot
  produce a label, and nothing about an unrecognised or merely unusual pipeline.
- Not in scope: the vocabulary fold itself (`vars` / `outputs` / `advanced` and `view=compiled`), which
  follows in `read-folded-pipeline-feed` and re-points this same emptiness check at `outputs`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `pipeline-sync`: the **Pipeline admission gate** requirement gains the `llm`-lane refusal — a pipeline
  that would make a model call and then write no label column is added to the enumerated set of
  provably-unexecutable combinations, with the reason stated where the gate is specified.

## Impact

- **Code:** `sync/PipelineRouting.java` (one arm), plus the refusal's Javadoc rationale. No call-site
  changes: `PipelineSyncService.admit` already logs, counts and skips whatever the gate names.
- **Tests:** `sync/PipelineRoutingTest` (refused and admitted cases for both `llm` lanes),
  `sync/PipelineSyncIntegrationTest` (the pipeline is absent from the cache and has no recurring task).
- **Operational:** a pipeline in this state stops running rather than silently blanking labels. If one
  exists in a registry it will disappear from the pipeline cache and be logged as refused on the
  first sync after deploy — which is the point, and is visible rather than silent.
- **Upstream:** none. This is a local refusal against the feed as ADAS serves it today.
- **Data:** none. It prevents writes; it does not repair rows an earlier run may already have blanked.
