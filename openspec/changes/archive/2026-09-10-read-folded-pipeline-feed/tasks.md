## 1. The query parameter, first and on its own

- [x] 1.1 In `adas/AdasRegistryClient.fetchPipelines`, add `view=compiled` beside `kind=enrich` and
      `enabled=true`. Without it ADAS serves the authored projection — no `evaluator`, no `grain_key`, no
      `version_column`, no `outputs`, no resolved `inputs` — and every pipeline is refused by the
      admission gate. Record in the class Javadoc that the parameter is mandatory and why the default is
      not a degraded feed but a different object.

## 2. DTOs

- [x] 2.1 Add `adas/dto/VarDto` — `{column, jsonata}`, exactly one set. Note in the Javadoc that ADAS
      always echoes the object form, even where the declaration used the bare-string shorthand, so the
      runner never has to handle two spellings.
- [x] 2.2 Add `adas/dto/OutputDto` — `{name, column, jsonata}`. State that `name` is the model response
      property (the target column's exposed name, and what the composed `response_schema` declares) and
      `column` is the physical column written, that they are often equal in practice and equal by
      construction on none, and that an absent `jsonata` is the only spelling of "no transform".
- [x] 2.3 Add `adas/dto/AdvancedDto` — `{scan_every, rows_per_scan, rows_per_call, rate_rpm,
      sample_fraction}`, every member nullable, with "absent means apply the runner's configured default"
      stated once here rather than at each reader.
- [x] 2.4 In `adas/dto/PipelineDto`: drop `inputBindings`, `outputBindings`, `sampling`, `cadence`,
      `batchScanLimit`, `batchChunk` and the top-level `rateRpm`; add `vars` (`Map<String, VarDto>`),
      `outputs` (`List<OutputDto>`) and `advanced` (`AdvancedDto`). Update the record's Javadoc: it
      describes the feed contract, so the folded vocabulary and the `view=compiled` projection belong in
      it.
- [x] 2.5 In the same record add the null-safe knob accessors delegating to `advanced` — `scanEvery()`,
      `rowsPerScan()`, `rowsPerCall()`, `rateRpm()`, `sampleFraction()` — mirroring the existing
      `triggerKind()` / `groupBy()` / `readyWhen()` pattern, so call sites change by name only and no
      caller null-checks a nested record.
- [x] 2.6 In `adas/dto/EvaluatorVersionDto`, drop `inputVars` and `outputVars`. What survives is exactly
      what makes the model call: identity, `type`, `preset`, `model`, `params`, `request_template`,
      `response_schema`, `created_at`.
- [x] 2.7 Delete `adas/dto/EvaluatorVarDto`, `adas/dto/InputBindingDto` and `adas/dto/OutputBindingDto`.
      `EvaluatorVarDto`'s `type` and `sql` and the evaluator's `input_vars` were verified unread before
      this change; `GET /v1/evaluators/{name}/versions/{v}` still serves them, but the runner does not
      call it.

## 3. The two mappers

- [x] 3.1 `scan/InputMapper`: iterate the `vars` map's entries. The entry key is the variable name (so
      `InputMappingException` is raised with it); the value resolves as before — `row[column]` for a bound
      column, else the JSONata expression over the row, else null. Keep the `LinkedHashMap` result:
      declaration order is the order the prompt interpolates. Keep the compiled-expression cache and its
      per-expression synchronisation unchanged.
- [x] 3.2 `worker/OutputMapper`: **delete the join**. Iterate `outputs` once — evaluate `jsonata` when
      present, else read `response[name]` — and put the value under `column`. The `HashMap` built per row
      and the `EvaluatorVarDto` lookup both disappear. Keep raising `OutputMappingException` named by the
      response property.

## 4. Call sites

- [x] 4.1 `sync/PipelineRouting.unsupportedReason`: hoist the emptiness check above the `isClass1` branch
      — both lanes now read `outputs` — and keep two distinct reasons, since an unmappable `sql` pipeline
      derives nothing while an unmappable `llm` one destroys a paid-for label. Leave the `sql`
      declares-a-model arm inside the branch.
- [x] 4.2 `scan/ScheduleFactory`: `pipeline.cadence()` → `pipeline.scanEvery()`.
- [x] 4.3 `scan/PipelineScanExecutor`: `batchScanLimit()` → `rowsPerScan()`, `batchChunk()` →
      `rowsPerCall()`.
- [x] 4.4 `scan/ChunkBuilder` and `scan/group/GroupMarkBuilder`: `sampling()` → `sampleFraction()`.
- [x] 4.5 `backfill/BackfillExecutor` (three sites) and `class1/Class1MaterializeExecutor`: the same
      accessor renames.
- [x] 4.6 `worker/EvaluatorInvoker`: `pipeline.rateRpm()` is unchanged by name and now reads through
      `advanced`; confirm no other site read a removed member.

## 5. Tests

- [x] 5.1 `support/TestPipelines` first: the builder's `inputBindings` / `outputBindings` / `outputVars`
      knobs become `vars` (map), `outputs` (list) and the `advanced` knobs, and `build()` constructs the
      new `PipelineDto`. Keep the default `outputs` non-empty, as the admission gate requires.
- [x] 5.2 Add a fixture where **`name != column`** and use it in `worker/OutputMapperTest` and in one
      integration test that inspects the written row. Nothing in this repo currently distinguishes the
      two, so a regression that collapsed the pair would otherwise pass every existing test.
- [x] 5.3 `worker/OutputMapperTest`: a transform output, a no-transform output read straight from
      `response[name]`, and a failing expression that raises `OutputMappingException`.
- [x] 5.4 `scan/InputMapperTest` and `worker/group/GroupInputAssemblerTest`: rebuild their fixtures on the
      `vars` map, keeping the bound-column, JSONata, absent-column and failing-expression cases.
- [x] 5.5 `sync/PipelineSyncIntegrationTest`: the WireMock stub matches `view=compiled` (so dropping the
      parameter fails loudly rather than silently), the JSON fixtures move to `vars` / `outputs` /
      `advanced`, and the Class-1 assertion that read `evaluator().outputVars().getFirst().sql()` asserts
      on the pipeline's `outputs` instead. Add a case pinning that an absent `advanced` falls back to the
      configured defaults.
- [x] 5.6 `sync/PipelineRoutingTest`: the Class-1 no-outputs case and the model-calling no-outputs case
      both read `outputs`, with their distinct reasons asserted.
- [x] 5.7 The remaining fixture users compile and pass: `WorkerIntegrationTest`,
      `worker/group/GroupWorkerIntegrationTest`, `worker/upsert/TwoPhaseUpsertIntegrationTest`,
      `scan/ScanIntegrationTest`, `scan/ChunkBuilderTest`, `scan/group/GroupMarkBuilderTest`,
      `scan/ScheduleFactoryTest`, `scan/PipelineScanExecutorTest`, `backfill/BackfillIntegrationTest`,
      `backfill/BackfillLanesIntegrationTest`, `class1/Class1MaterializeExecutorTest`.

## 6. Docs and project context

- [x] 6.1 `openspec/config.yaml` `context:` — record the mandatory `view=compiled` projection on the sync
      feed and the folded members (`vars`, `outputs`, `advanced`) in the ADAS integration contract, and
      drop the retired spellings.
- [x] 6.2 `docs/e2e-runbook.md` — move the registration bodies to the authored surface: an evaluator
      declares `outputs` (keyed by target column) instead of `input_vars` + `response_schema` +
      `output_vars`; a pipeline declares `vars` and `advanced` and **no** output bindings at all, since
      the outputs now come from the evaluator resolved against the target. Add `?view=compiled` to the
      ADAS-side pipeline reads, and re-word the `cadence` / `batch_scan_limit` references in the prose.
- [x] 6.3 `docs/configuration.md` — the knob descriptions name the new wire members
      (`advanced.scan_every`, `advanced.rows_per_scan`, `advanced.rows_per_call`, `advanced.rate_rpm`).
      The runner's own property and env-var names do **not** change in this task.

## 7. Verify

- [x] 7.1 Grep `src/main` and `src/test` for every retired spelling — `input_bindings`, `output_bindings`,
      `inputBindings`, `outputBindings`, `outputVars`, `inputVars`, `sampling()`, `cadence()`,
      `batchScanLimit`, `batchChunk`, `InputBindingDto`, `OutputBindingDto`, `EvaluatorVarDto` — and
      confirm none remains.
- [x] 7.2 `openspec validate read-folded-pipeline-feed --strict` passes.
- [x] 7.3 `./gradlew clean build` passes (compile + checkstyle + tests). Prefix with
      `TESTCONTAINERS_RYUK_DISABLED=true` if the Testcontainers reaper cannot start.
- [x] 7.4 Confirm the four touched capability specs match the shipped behavior, and that `backfill` and
      `class1-enrichment` genuinely needed none (neither names a knob's wire spelling).
