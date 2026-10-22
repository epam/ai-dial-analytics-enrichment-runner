## Context

The runner is a read-only consumer of ADAS's pipeline registry. It models the feed in
`adas/dto/PipelineDto` and a handful of nested records, and every lane reads its knobs and its mapping
off that record.

ADAS's `simplify-pipeline-authoring` change folds the feed onto the authored vocabulary. Two
projections of one stored pipeline are served, differing **in resolution, never in naming**:

- `view=source` — the **new default** — is the authored declaration: `vars`, `advanced`, and the *stored*
  `inputs`. It carries no `evaluator`, no `grain_key`, no `version_column`, no `outputs`.
- `view=compiled` is the same vocabulary with the derived values filled in: the inlined evaluator with its
  response schema, the resolved `outputs`, the resolved `inputs` and the `version_column`.

The runner reads `compiled`, asking for it explicitly. This is the single highest-consequence line in the
change: without the parameter the feed is not degraded, it is a *different object*, and every pipeline
fails the admission gate for want of an evaluator and an output mapping.

What the compiled projection now serves, verified against ADAS's shipped DTOs rather than drafted:

```json5
{ "name": "…", "kind": "enrich", "target": "…", "inputs": ["…"], "filter": "…",
  "trigger": {"kind": "group", "group_by": "…", "ready_when": {"idle": "30m"}, "member_select": {/* … */}},
  "enabled": true, "generation": 24, "created_at": "…", "updated_at": "…",
  "evaluator_name": "session-insights", "evaluator_version": 6,
  "evaluator": {"name": "…", "version": 6, "type": "llm", "preset": "chat_completion",
                "model": "…", "params": {/* … */}, "request_template": "…", "response_schema": {/* … */},
                "created_at": "…"},
  "grain_key": "client_session_id", "version_column": "_ingested_at",
  "vars":    {"request": {"column": "request_body"}, "response": {"column": "assembled_response"}},
  "outputs": [{"name": "title", "column": "title"},
              {"name": "mood",  "column": "mood", "jsonata": "(mood = \"none\") ? null : mood"}],
  "advanced": {"scan_every": "5m", "rows_per_scan": 1000, "rows_per_call": 1,
               "rate_rpm": 75, "sample_fraction": 0.5},
  "state": {/* … */} }
```

Gone from every response: `input_bindings`, `output_bindings`, `evaluator.input_vars`,
`evaluator.output_vars`, `sampling`, `cadence`, `batch_scan_limit`, `batch_chunk`, `priority`. Inclusion
is `NON_NULL` throughout, so an unset member is absent rather than null.

## Goals / Non-Goals

**Goals:**

- Read the folded feed exactly as ADAS serves it, with no compatibility shim and no second spelling
  retained on this side either — the whole point of the fold is that one fact has one name.
- Delete the join in `OutputMapper` rather than re-implement it against renamed members.
- Keep every call site's *shape* unchanged where only a name moved, so the diff is reviewable as
  "renames plus two mappers" rather than as a behavior change.
- Leave the runner's own configuration keys and its persisted state untouched.

**Non-Goals:**

- Modelling the members the runner does not read (`target`, `inputs`, `filter`, `evaluator_name`,
  `created_at`, `updated_at`, `state`). `PipelineDto` deliberately models only what is consumed;
  `FAIL_ON_UNKNOWN_PROPERTIES` is off, so omitting them is how the runner says it does not depend on them.
- Renaming the runner's config keys (`scan.default-cadence`, `default-scan-limit`, `default-chunk`) to
  match the wire. Internal defaults need not share the wire's spelling, and the rename is a Helm/env
  change with no behavioral value.
- `POST /pipelines/{name}/try`. ADAS's new enrich preview deliberately renders no prompt, because the
  template renderer and its escaping live here and a second implementation is where drift would hide.
  That is real and worth doing — it is what closes the authoring loop — but it is a feature, not part of
  catching up to the feed.

## Decisions

**1. `view=compiled` is a fixed part of the request, not a configuration knob.**

It goes in `AdasRegistryClient.fetchPipelines` beside `kind=enrich` and `enabled=true`, which are already
sent explicitly for the same reason: the request should state which set the runner means. Making it
configurable would create a setting whose only wrong value breaks every pipeline silently. The sync
integration test's WireMock stub matches on all three query parameters, so dropping any one of them fails
the tests with an unmatched request rather than by returning a degraded feed.

_Alternative considered:_ tolerate a source-projection response by falling back to a second request.
Rejected — it would paper over a misconfiguration that has exactly one correct value, and there is no
sensible behavior for a pipeline with no evaluator.

**2. The knobs get null-safe accessors on `PipelineDto`, delegating to `advanced`.**

`advanced` is absent when the declaration set none, and each of its members is absent when unset. Rather
than making six call sites each null-check a nested record, `PipelineDto` exposes `scanEvery()`,
`rowsPerScan()`, `rowsPerCall()`, `rateRpm()` and `sampleFraction()`, each returning null when `advanced`
or the member is absent. This mirrors what the record already does for `trigger` (`triggerKind()`,
`triggerCron()`, `groupBy()`, `readyWhen()`), so the shape is not new. Every consumer already treats null
as "apply the configured default" — `ScanProperties.resolveScanLimit`, `resolveChunk`,
`Class1Properties.resolveLimit`, `DialRateLimiter.acquire`, `Sampler.keep` — so absence keeps meaning
exactly what an absent `cadence` meant.

_Alternative considered:_ have call sites read `pipeline.advanced().rowsPerScan()`. Rejected — it turns a
name change into a null-check at six sites, one of which (`Sampler`) is on the per-row path.

**3. `OutputMapper` iterates `outputs` once; `name` and `column` stay two facts.**

The mapper becomes: for each output, evaluate `jsonata` over the response when present, else read
`response[name]`, and put the value under `column`. The per-row `HashMap` and the lookup by variable name
both disappear.

The two names must not be collapsed, and the temptation is real because they are commonly equal in
practice. `name` is the model response property — the target column's *exposed* name, and the property
the composed `response_schema` declares. `column` is the physical `source_name`; ADAS rewrites a binding
to the physical spelling before storing it. Inferring either from the other writes to a column that does
not exist, or reads a property that was never declared. ADAS's own `Class1MaterializeIntegrationTest`
covers a target exposing `model_logical` over a `model_physical` column, and this change adds the mirror
of that fixture here, because nothing in this repo currently distinguishes them and a regression that
collapsed the pair would pass every existing test.

An absent `jsonata` is the *only* spelling of "no transform": an identity expression is refused at
registration, and most outputs declare none. So the `StringUtils.isNotBlank` branch keeps its
current meaning and merely reads a different member.

**4. `InputMapper` iterates the `vars` map's entries.**

`vars` replaces `input_bindings` *and* the evaluator's `input_vars` together — the map's keys are the
variable names, which is what drove substitution all along. The value is a `VarDto` carrying exactly one
of `column` or `jsonata`, always the object form (ADAS echoes the object form even where the declaration
used the bare-string shorthand, precisely so a reader never has to guess which spelling it will get). The
resolution branch is unchanged; only the source of the variable name moves from a record member to the
entry key, and the `InputMappingException` is raised with that key.

Iteration order matters only for the rendered prompt's stability, and ADAS preserves declaration order in
the map, so the mapper keeps building a `LinkedHashMap`.

**5. The gate's two emptiness checks collapse into one condition with two reasons.**

Before the fold the `sql` arm tested `evaluator.output_vars` and the `llm` arm (added by
`refuse-unmappable-llm-pipeline`) tested `output_bindings` — different members because on the Class-1
lane the output vars *were* the derivation. After the fold both facts are `outputs`, so the honest shape
is one check hoisted above the `isClass1` branch, choosing its message by lane. The messages stay
distinct because the consequences are: an unmappable `sql` pipeline derives nothing, while an unmappable
`llm` pipeline pays for a model call and then overwrites a label with nulls.

**6. `EvaluatorVarDto` is deleted rather than kept for the evaluator endpoint.**

`GET /v1/evaluators/{name}/versions/{v}` is unchanged and still answers with the stored row verbatim,
`output_vars` and per-output `type` included — but the runner does not call it, and never has. Keeping a
record for a surface with no client is how a dead vocabulary survives a fold. Its `type` and `sql` members
were confirmed unread here before the change; `evaluator.input_vars` likewise.

**7. `advanced.rate_rpm` keeps the accessor name `rateRpm()`.**

It moved location, not meaning, and `EvaluatorInvoker`'s call site reads identically. Renaming it for
symmetry with the four that did change would be churn.

## Risks / Trade-offs

- **Forgetting `view=compiled` is a total, silent outage** — every pipeline refused for want of an
  evaluator, the cache empty, readiness never satisfied. → It is a fixed query parameter, the WireMock
  stub matches on it so the tests fail loudly if it is dropped, and it is called out in its own task
  ahead of the DTO work.
- **The rollback door closes.** Once ADAS serves only the new vocabulary the previous runner image cannot
  be scaled back up; a crashlooping new one must be fixed forward. → A deliberate consequence of dropping
  the second vocabulary, and a better failure mode than the silent one it replaces. It is stated in the
  proposal's Impact so whoever runs the window knows before they need it.
- **A regression collapsing `name` and `column` would pass today's tests.** → A fixture where they differ
  is added in the same change, asserted in both `OutputMapperTest` and an integration test that inspects
  the written row.
- **The `EvaluatorVersionDto` constructor loses two positional parameters**, and every test that
  hand-rolls it breaks. → Intended: `TestPipelines` is the one place that should build these, and the
  compile error names each site that bypassed it.

## Migration Plan

One coordinated window, in this order, with no version negotiation at any step:

```
confirm no backfill is RUNNING  →  scale runner to 0  →  deploy ADAS  →  deploy runner  →  scale runner to 1
```

The `RUNNING` check is done rather than relied upon: a killed run stays `RUNNING` in `run_history` until
the restart reclaims it, and confirming beats trusting the reclaim.

Neither intermediate combination may exist. An old runner against a new feed deserializes `outputBindings`
to null and reaches the all-NULL upsert; a new runner against an old ADAS finds no `outputs`. Because
`refuse-unmappable-llm-pipeline` shipped first, the first of those two is now a refusal rather than a
silent overwrite — which is the backstop, not the plan.

Merge order between the two repos is free (`FAIL_ON_UNKNOWN_PROPERTIES` is off here), deploy order is not.

**Expected during the window (none of these is a fault):**

- **Class-1 clamp stalls.** No materialize calls means `materialized_through_version` freezes on every
  Class-1 pipeline (e.g. `usage-client-identity-live`), so the freshness clamp holds dependent rollups and
  pipelines (e.g. `sessions_rollup`) at that position. Their ADAS crons keep firing and write up to the clamp.
  Self-heals within one cadence of the runner returning.
- **Group pipelines burst on return.** Groups do not expire while the runner is down; they sit dirty and
  all become ready at once. Throttled by each pipeline's `rate_rpm` and
  `DB_SCHEDULER_THREADS`. Check the DIAL role token budget first and keep the window short.
- **In-flight chunks** are reclaimed by db-scheduler's dead-execution detector on restart — the designed
  at-least-once path, so the cost is a re-charged model call, not a wrong row.

**After scaling back up**, check on the ADAS side: `generation` unchanged on every pipeline,
`materialized_through_version` unchanged on every Class-1 one, `rebuild_required`
matching the captured baseline, and every enrich pipeline still resolving to the read source it resolved
to before. Those reads now need `?view=compiled` explicitly.

Rollback is forward-only for the runner, as above.

## Open Questions

None. The wire contract in §Context is copied from ADAS's shipped DTOs
(`web/dto/pipeline/PipelineDto`, `web/dto/enrichment/PipelineOutputDto`, `VarSpecBody`,
`PipelineRequests.AdvancedRequest`, and `PipelineMapper.inlinedEvaluatorOf`), not drafted.
