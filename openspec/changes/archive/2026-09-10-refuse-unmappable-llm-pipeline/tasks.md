## 1. The admission arm

- [x] 1.1 In `sync/PipelineRouting.unsupportedReason`, add the non-Class-1 arm: when `isClass1(pipeline)`
      is false and `pipeline.outputBindings()` is null or empty, return a reason naming the missing output
      mapping. Place it beside the existing `isClass1` block so the two emptiness checks read as one pair,
      and key it off `isClass1` rather than `EvaluatorType.LLM` so a future model-calling evaluator type
      inherits the check (design decision 4). Do not dereference `pipeline.evaluator()` in this arm — it is
      null for a pipeline carrying no evaluator, which `isClass1` already reports as false.
- [x] 1.2 State the rationale in the method's Javadoc where the other reasons are explained: an empty
      mapping upserts the grain key plus provenance and nothing else, which on a `ReplacingMergeTree`
      replaces an already-paid-for label with nulls — no exception, no dead-letter, no retry, model call
      charged first. Note that ADAS refuses to serve such a pipeline too, and why the runner does not rely
      on that.

## 2. Tests

- [x] 2.1 `sync/PipelineRoutingTest`: a row (`on_ingest`) `llm` pipeline with empty output bindings is
      refused with the new reason; a group `llm` pipeline with a valid `ready_when` and empty output
      bindings is refused with the same reason (the group arms run first, so this pins that a valid group
      declaration does not shadow the mapping check).
- [x] 2.2 `sync/PipelineRoutingTest`: an `llm` pipeline whose output bindings are non-empty while the
      evaluator declares **no** output vars is admitted — `unsupportedReason` returns null. This is the
      common case and the regression that a "refuse when either list is empty" arm would break.
- [x] 2.3 `sync/PipelineSyncIntegrationTest`: syncing an enabled `llm` pipeline with no output bindings
      leaves it absent from the `PipelineCache` and creates no recurring task instance under either task
      name (the counting path is `PipelineSyncService.admit`, already covered by the existing refusal
      tests).
- [x] 2.4 Check whether `support/TestPipelines`'s builder defaults leave `outputBindings` empty
      (`List.of()`), which would newly refuse every fixture that does not set them, and give the default
      builder a single output binding so existing fixtures stay admissible. Fixtures that must be refused
      set it back to empty explicitly.

## 3. Verify

- [x] 3.1 `openspec validate refuse-unmappable-llm-pipeline --strict` passes.
- [x] 3.2 `./gradlew clean build` passes (compile + checkstyle + tests). Prefix with
      `TESTCONTAINERS_RYUK_DISABLED=true` if the Testcontainers reaper cannot start.
- [x] 3.3 Confirm the `pipeline-sync` spec matches the shipped behavior; no other capability spec needs a
      change, since nothing outside the admission gate moves.
