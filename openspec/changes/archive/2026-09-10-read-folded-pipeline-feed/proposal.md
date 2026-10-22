## Why

ADAS's `simplify-pipeline-authoring` removes the second vocabulary from its pipeline feed. The same fact
had two names — one an author wrote (`scan_every`, `vars`, `outputs`) and one the feed carried
(`cadence`, `input_bindings`, `output_bindings` + the evaluator's `output_vars`) — and that second set
existed for exactly one reader: a runner deployed before the change, reading a new ADAS during a rolling
deploy. The cut-over is a coordinated window with the runner scaled to zero, so that reader cannot exist.
ADAS has deleted the old spelling with no alias and no deprecation window; this repo catches up or stops
syncing.

The fold also gives the runner two things a rename would not. `OutputMapper` stops joining two lists by
name — ADAS resolves the mapping once and serves one ordered list, so the `HashMap` built per row
disappears. And three wire members that no code here reads (`evaluator.input_vars`, and
`EvaluatorVarDto`'s `type` and `sql`) go with it.

## What Changes

- **BREAKING (upstream contract).** The sync feed request becomes
  `GET /v1/pipelines?kind=enrich&enabled=true&view=compiled`. **`view=compiled` is mandatory**: the default
  is now `view=source`, the authored declaration, which carries no `evaluator`, no `grain_key`, no
  `version_column`, no `outputs` and no resolved `inputs`. Omitting it does not degrade the feed — it
  returns a different, cheaper object, and every pipeline would look unrunnable.
- **BREAKING (DTOs).** `PipelineDto` drops `input_bindings`, `output_bindings`, `sampling`, `cadence`,
  `batch_scan_limit`, `batch_chunk` and the top-level `rate_rpm`, and gains `vars` (a map keyed by
  variable name), `outputs` (an ordered list) and `advanced` (the nested knobs, `rate_rpm` among them).
  New `VarDto {column, jsonata}`, `OutputDto {name, column, jsonata}` and
  `AdvancedDto {scan_every, rows_per_scan, rows_per_call, rate_rpm, sample_fraction}`.
  `InputBindingDto`, `OutputBindingDto` and `EvaluatorVarDto` are deleted; `EvaluatorVersionDto` drops
  `input_vars` and `output_vars`. The knob accessors read through `advanced` on `PipelineDto`, so call
  sites change by name only (`cadence()` → `scanEvery()`, `batchScanLimit()` → `rowsPerScan()`,
  `batchChunk()` → `rowsPerCall()`, `sampling()` → `sampleFraction()`; `rateRpm()` keeps its name).
- **`OutputMapper` loses its join.** It iterates `outputs` once — evaluate `jsonata` when present, else
  read `response[name]` — and writes under `column`. `name` and `column` are two facts and are **not**
  collapsed: `name` is the model response property (the target column's *exposed* name, and what the
  composed response schema declares) and `column` is the physical column written. They are often equal in
  practice and equal by construction on none.
- **`InputMapper` iterates a map.** The variable name is the entry key rather than a `var` member; the
  value is a `VarDto` carrying exactly one of `column` or `jsonata`.
- **The admission gate's two emptiness checks collapse into one.** Both lanes now read `outputs`, so the
  `sql` arm and the `llm` arm added by `refuse-unmappable-llm-pipeline` become one condition with two
  reasons.
- **`advanced` members are omitted when unset** and `advanced` itself is absent when the declaration set
  none. Every accessor is null-safe, and an absent member keeps meaning "apply the runner's configured
  default" — exactly as an absent `cadence` did.
- **Docs.** `docs/e2e-runbook.md`'s registration bodies move to the authored surface (evaluator `outputs`
  replacing `input_vars` + `response_schema` + `output_vars`; pipeline `vars` and `advanced` replacing
  `input_bindings`, `output_bindings` and `cadence`, with pipeline-level output bindings gone entirely),
  and the ADAS-side reads in it gain `?view=compiled`. `docs/configuration.md`'s knob descriptions name
  the new wire members. `openspec/config.yaml`'s ADAS integration contract records the projection
  parameter and the folded members.
- **Not in scope.** The runner's own config keys keep their names (`scan.default-cadence`,
  `default-scan-limit`, `default-chunk`) — an internal default's name does not have to match the wire, and
  renaming them is a Helm/env change with no behavioral value. `POST /pipelines/{name}/try` (rendering a
  prompt for one row or group without writing) is a separate, larger piece of work.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `pipeline-sync`: the feed request gains the mandatory `view=compiled` projection and what that
  projection guarantees; the recurring task's fixed delay comes from `advanced.scan_every`; the
  `sql`-evaluator binding scenario no longer describes `output_vars`; the admission gate's mapping check
  reads `outputs`.
- `incremental-scan`: input mapping reads the `vars` map; the sampling fraction is
  `advanced.sample_fraction`; the page and chunk sizing knobs are `advanced.rows_per_scan` and
  `advanced.rows_per_call`.
- `enrichment-worker`: output mapping is one resolved list — response property, physical column and
  optional transform per entry — with no join by name, and the two names are not interchangeable.
- `group-enrichment`: group-key sampling reads `advanced.sample_fraction`; group input assembly applies
  the pipeline's `vars` to each member row.

`backfill` and `class1-enrichment` need **no** spec delta: neither names a knob's wire spelling — they
say "a resolved `limit`" and "the page's chunks" — so only their call sites move.

## Impact

- **Upstream contract:** ADAS serves only the new vocabulary. Because the runner disables
  `FAIL_ON_UNKNOWN_PROPERTIES`, the order in which the two repos *merge* is free; the order in which they
  *deploy* is not.
- **Deployment — one window, and the runner is not optional.**
  `scale runner to 0 → deploy ADAS → deploy runner → scale runner to 1`. There is no version negotiation:
  an old runner against a new feed deserializes `outputBindings` to null and walks into the all-NULL
  upsert that `refuse-unmappable-llm-pipeline` now refuses; a new runner against an old ADAS finds no
  `outputs` at all. **This costs the runner rollback**: once ADAS serves only the new vocabulary the old
  image can never be scaled back up, and a crashlooping new one must be fixed forward.
- **Expected during the window** (none of these is a fault): Class-1 clamp stalls, because no
  materialize call means `materialized_through_version` freezes and dependent rollups hold at that
  position — self-healing within one cadence of the runner returning; a group-pipeline burst on return,
  since groups do not expire while the runner is down and all become ready at once (throttled by
  `rate_rpm` and `DB_SCHEDULER_THREADS`, so check the DIAL token budget and keep the window short); and
  in-flight chunks reclaimed by db-scheduler's dead-execution detector, which costs a re-charged model
  call, not a wrong row.
- **Before scaling to 0:** confirm no backfill is `RUNNING` in `run_history` rather than relying on the
  restart reclaim.
- **Code:** `adas/dto/` (three records added, three deleted, two changed), `adas/AdasRegistryClient`,
  `scan/InputMapper`, `worker/OutputMapper`, `sync/PipelineRouting`, `scan/ScheduleFactory`,
  `scan/PipelineScanExecutor`, `scan/ChunkBuilder`, `scan/group/GroupMarkBuilder`,
  `backfill/BackfillExecutor`, `class1/Class1MaterializeExecutor`, `worker/EvaluatorInvoker`.
- **Tests:** there are no JSON fixtures — the feed shape is built in Java by `support/TestPipelines`,
  which changes first. Five classes assert on it (`OutputMapperTest`, `PipelineSyncIntegrationTest`,
  `WorkerIntegrationTest`, `worker/group/GroupWorkerIntegrationTest`,
  `worker/upsert/TwoPhaseUpsertIntegrationTest`), plus `InputMapperTest`, `GroupInputAssemblerTest`,
  `PipelineRoutingTest`, `ScanIntegrationTest` and the two backfill tests. A fixture where
  **`name != column`** is added: nothing here currently distinguishes them, so a regression that collapsed
  the pair would pass every existing test.
- **Data:** none. No migration, no state discarded, no identity change.
