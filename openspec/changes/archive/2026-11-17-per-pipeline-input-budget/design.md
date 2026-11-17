## Context

`GroupInputAssembler.applyBudget` reads `groupProperties.inputTokenBudget()` (default `60000`, `0` disables) and
estimates each member's cost as `(key + value characters) / 4 + 1`. Over budget, it drops the oldest members.
If the newest member alone still does not fit, it cuts that member's fields to their tail, with an in-value
marker. ADAS has already applied its own row cap, byte cap and `member_select` before the runner sees the group.
The runner folds that truncation into the same verdict, so the `truncated` provenance and the `members_notice`
caveat always agree.

Since the app-route preset shipped, a group transform can target either a model (`chat_completion`) or a DIAL
application's route (`dial_app_route`). An application can tokenize properly and choose what to keep, so the
runner's estimate-based trimming is only in its way. The budget is still a single runner-wide value, though.

ADAS's `RunnerContractShapeTest` pins every member of the shapes the runner reads, `advanced` included, and
requires `RunnerContract.VERSION` to rise on any change to them. The runner compares the echoed version for
equality.

`advanced` is where ADAS already serves per-pipeline execution knobs (`rows_per_scan`, `rows_per_call`, `rate_rpm`,
`sample_fraction`). An absent member there means "the runner's default", and ADAS validates the values. The
runner reads them through null-safe accessors on `PipelineDto` and does not re-validate them in the admission
gate.

## Goals / Non-Goals

**Goals:**

- Each group pipeline can switch the runner's trimming off, or set its own ceiling, without touching other
  pipelines.
- A pipeline that declares nothing behaves exactly as it does today, so upgrading changes no pipeline's input.
- Everything else about a trimmed input stays as it is: drop order, tail-keeping cuts, markers, caveat, and the
  `truncated` verdict.

**Non-Goals:**

- A real tokenizer. The four-characters-per-token estimate stays: it is a guard, and the runner cannot know an
  arbitrary deployment's tokenizer anyway.
- Lifting ADAS's caps. `group.fetch-limit`, ADAS's byte cap and `member_select` still bound what any pipeline
  receives.
- A budget on the row lane, which has none today.
- The ADAS side: accepting, validating and projecting `advanced.input_token_budget`. That is a paired change in
  ADAS.

## Decisions

### The knob is an integer in `advanced`, not a boolean

`advanced.input_token_budget` has the same shape and meaning as `GROUP_INPUT_TOKEN_BUDGET`: `0` is off and `N` is
the ceiling. One integer covers switching off, lowering for a small-context model, and raising for a large-context
one.

- *Alternative: a boolean such as `trim_input: false`.* Rejected. It covers switching off only, and the next
  request ("this model has a 200k window") would need a second knob.
- *Alternative: a member on `transform`.* Rejected. `transform.params` is overlaid onto the request body, and a
  new `transform` member would mix a runner execution knob into the declaration of what is called. `advanced` is
  where execution knobs live.

### One default for every preset

The budget is resolved in one place, from the pipeline's knob and the runner's configured default. The preset
plays no part:

| `advanced.input_token_budget` | budget                                   |
|-------------------------------|------------------------------------------|
| absent                        | `group.input-token-budget`               |
| `0`                           | off                                      |
| `N > 0`                       | `N`                                      |
| negative                      | as absent (`group.input-token-budget`)   |

The resolver is a pure function, `GroupProperties.resolveInputTokenBudget(PipelineDto)`, which sits beside
`resolveSweepBatch` and `resolveInflightCeiling` and is unit-testable without the assembler. `applyBudget` takes
the resolved value as a parameter instead of reading the property.

Opting out is a decision about the application behind the route, not about the preset. An application that
forwards the group to a model without managing its size needs the guard as much as a `chat_completion` call
does, and only the pipeline author knows which kind it is. Keeping one default also means upgrading changes no
pipeline's input.

- *Alternative: an app-route pipeline is untrimmed by default.* Rejected. It changes the input of every existing
  app-route group pipeline on upgrade, and it removes the guard from applications that relied on it, without
  their authors having asked.
- *Alternative: a second env var for the app-route default.* Rejected for now. The per-pipeline knob covers the
  request, and a runner-wide preset default can be added later without changing this contract.

### A negative knob falls back to the default; it is not refused

ADAS validates the knob (`>= 0`). The runner's other `advanced` knobs are not re-checked by the admission gate.
For a value ADAS should never send, the defensive reading is "absent", which keeps the default guard in place.
Treating it as `0` would silently remove the guard. Refusing the whole
pipeline would stop it over a knob that only affects the input size. The fallback is logged once per generation,
not per evaluation.

### The contract version rises to 6

Adding a member to `advanced` is a contract change under ADAS's own rule, so `AdasContract.VERSION` goes from
`5` to `6` on both sides in one deploy window, the same as the four earlier bumps.

- *Alternative: stay at `5` on the grounds that the member is optional and an older runner ignores it.* Rejected.
  It contradicts the rule ADAS's shape test enforces, which allows only renames that leave the JSON unchanged.
  It would also leave a runner on `5` silently trimming a pipeline whose operator set `0`, with nothing to
  refuse it.

### The knob is read only on the group lane

The row lane and the Class-1 lane have no input budget, so the knob means nothing there. The runner ignores it on
those lanes rather than refusing the pipeline. Whether to reject such a declaration is ADAS's decision.

### Switching the budget off keeps ADAS's truncation visible

With a budget of `0`, the assembler keeps its existing early return: no drop and no cut, and `fetchTruncated` is
still folded in. So `members_truncated`, `members_omitted = 0`, `members_notice` and the row's `truncated` keep
reporting ADAS's narrowing exactly as they do today. An app receiving `members_truncated` can tell it is seeing
part of the group.

### The cut counter is tagged by pipeline

`runner.group.input.budget.cut` gains `pipeline=<name>`, following `runner.class1.rows.written` and
`runner.backfill.batch.failures`. Once budgets differ per pipeline, an untagged count cannot be argued from, which
is the counter's whole purpose. The number of pipeline names is the number of configured pipelines, the same
cardinality the other per-pipeline counters already accept.

## Risks / Trade-offs

- [The contract bump couples the two deploys] → Same procedure as earlier bumps. During the skew window the echo
  check stalls the runner (no scans, no calls, no writes) instead of letting it misread the feed. The upgrade
  guide states the pairing.
- [A pipeline set to `0` receives the full ADAS-capped group, up to `group.fetch-limit` members and ADAS's byte
  cap] → That is what opting out means. The application sees `members_truncated` when ADAS narrowed the group, and
  ADAS's byte cap still bounds the payload.
- [The counter's tags change] → Prometheus queries without a `pipeline` selector still return the same total.
  Only panels that compare raw series labels need a look.

## Migration Plan

1. Deploy ADAS and the runner together on contract `6`. No pipeline's input changes, because none declares the
   knob yet.
2. Set `advanced.input_token_budget` per pipeline as needed. An app-route pipeline opts out with `0`.
3. Rollback: roll both services back together to contract `5`. Every group pipeline returns to the runner-wide
   budget. No persisted state is involved.

## Open Questions

(none) ADAS's paired change rejects `advanced.input_token_budget` on a non-group pipeline with 422. The runner
still ignores it there rather than refusing the pipeline, so its behaviour does not depend on that check.
