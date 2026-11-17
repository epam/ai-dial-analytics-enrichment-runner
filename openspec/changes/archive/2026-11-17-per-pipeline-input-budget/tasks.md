## 1. Read the knob from the feed

- [x] 1.1 Add `@JsonProperty("input_token_budget") Integer inputTokenBudget` to `AdvancedDto`, documented beside
      the other knobs
- [x] 1.2 Add a null-safe `PipelineDto.inputTokenBudget()` accessor next to `rowsPerCall()` / `sampleFraction()`
- [x] 1.3 Extend `FeedShapeTest` (or the existing feed-binding test): a declared `input_token_budget` binds, and an
      absent member or absent `advanced` reads as null
- [x] 1.4 Add a sync test showing that a `schedule` pipeline carrying the knob is admitted unchanged

## 2. Raise the contract version

- [x] 2.1 Raise `AdasContract.VERSION` to `6` and add a "6 since" paragraph to its Javadoc
- [x] 2.2 Update `AdasContractInterceptorTest`'s pinned literal (`5` → `6`), and add `input_token_budget` to the
      `advanced` members pinned in `FeedShapeTest` (the JSON fixture and the record-component list)

## 3. Resolve the budget per pipeline

- [x] 3.1 Add `GroupProperties.resolveInputTokenBudget(PipelineDto)`, following the resolution table in design.md:
      a declared value of `0` or more wins, otherwise `inputTokenBudget`. A negative value reads as absent. The
      preset is not consulted
- [x] 3.2 Log the negative-value fallback at most once per pipeline generation (for example, a small seen-set keyed
      by name and generation in the resolver's caller)
- [x] 3.3 Unit-test the resolver for every row of the table, and that an absent knob gives the same result under
      both presets

## 4. Apply the resolved budget

- [x] 4.1 Make `GroupInputAssembler.applyBudget` take the budget as a parameter, and have `assemble` pass
      `groupProperties.resolveInputTokenBudget(pipeline)`. Update the class Javadoc and the
      `GroupProperties.inputTokenBudget` `@param` to say it is the default for pipelines that declare no knob
- [x] 4.2 Assembler tests: a pipeline with no knob is trimmed exactly as today; a pipeline declaring `0` passes an
      over-budget group through intact; a positive knob overrides the default; with a budget of `0` and
      `fetchTruncated=true`, the result is truncated with `omitted=0` and a non-empty notice
- [x] 4.3 Extend `GroupWorkerIntegrationTest` (it runs the real assembler; `GroupWorkerServiceTest` mocks it) with an
      app-route group declaring `0` and over the default budget: the route receives every member intact, and the
      row and group state are not marked truncated

## 5. Attribute the cut counter

- [x] 5.1 Change `RunnerMetrics.recordInputBudgetCut()` to `recordInputBudgetCut(String pipelineName)` and register
      `runner.group.input.budget.cut` per pipeline, following `runner.class1.rows.written`
- [x] 5.2 Pass the pipeline name from `GroupWorkerService`, and update any test that asserts the counter so it
      selects by the `pipeline` tag

## 6. Docs

- [x] 6.1 `docs/configuration.md`: reword the `GROUP_INPUT_TOKEN_BUDGET` row to say it is the default for group
      pipelines that declare no `advanced.input_token_budget`, and that a pipeline whose application trims its own
      input (typically `dial_app_route`) opts out with `0`. Mention the new `pipeline` tag in the tuning hint
- [x] 6.2 Add a Deployment Changes entry to the next release's upgrade guide: `docs/release-0.24-upgrade.md` (created
      here; it also covers the contract `5` app-route preset), naming contract `6` and the paired ADAS deploy, the
      new knob (`0` opts out), and the counter's new `pipeline` tag
- [x] 6.3 Confirm `openspec/specs/group-enrichment/spec.md` and `openspec/specs/pipeline-sync/spec.md` match the
      shipped behaviour once the delta specs are merged at archive

## 7. Verify

- [x] 7.1 `openspec validate per-pipeline-input-budget --strict`
- [x] 7.2 Run the public-repo hygiene sweep from `CLAUDE.md` over the changed files
- [x] 7.3 `./gradlew clean build`
