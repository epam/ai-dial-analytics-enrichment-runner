## Why

The group lane trims every group's assembled input to one runner-wide token budget (`group.input-token-budget`,
estimated at four characters per token). That fits a `chat_completion` transform, whose model has a fixed context
window and no logic of its own. It does not fit a `dial_app_route` transform: the application receiving the group
can count tokens properly and decide for itself what to keep. Pre-trimming its input throws away members it could
have used and keeps them by a heuristic it cannot see. The only way to switch the trimming off today is to set
the budget to `0`, which removes it for every group pipeline at once, the `chat_completion` ones that need it
included.

## What Changes

- New optional per-pipeline knob `advanced.input_token_budget` (integer). Absent means
  `group.input-token-budget`, whatever the preset. `0` switches the runner's trimming off for that pipeline, and
  `N > 0` is that pipeline's own ceiling.
- A `dial_app_route` group pipeline whose application trims its own input opts out with
  `advanced.input_token_budget: 0`. When the runner trims or not, ADAS's row, byte and `member_select` limits
  still apply, and they still set `truncated` and the `members_notice` caveat when they narrow a group.
- `runner.group.input.budget.cut` gains a `pipeline` tag, so a cut can be traced to the pipeline whose budget
  caused it now that budgets differ per pipeline.
- **BREAKING (contract):** `AdasContract.VERSION` goes from `5` to `6`. ADAS's contract-shape test pins every
  member of `advanced`, and adding one is a contract change on its side. ADAS and the runner deploy together.
  During the skew, the existing echo check stalls the runner, so no call is made.
- `group.input-token-budget` / `GROUP_INPUT_TOKEN_BUDGET` stays. It becomes the default for group pipelines that
  declare no knob, rather than the one budget for all of them. A pipeline that declares no knob behaves exactly as
  it does today.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `group-enrichment`: "Bounded group input" applies a per-pipeline budget, the knob or else the runner default,
  instead of the one runner-wide budget. "Content cut to fit the budget is observable"
  attributes the count to a pipeline.
- `pipeline-sync`: a new requirement reads `advanced.input_token_budget` as one more execution knob, and the
  contract-version requirement records the bump to `6`.

## Impact

- **Code:** `AdvancedDto` and `PipelineDto` (new knob and accessor), `GroupProperties` (budget resolution),
  `GroupInputAssembler.applyBudget`, `GroupWorkerService` (passes the pipeline name to the cut metric),
  `RunnerMetrics.recordInputBudgetCut`.
- **Contract:** raised to `6`, paired with the ADAS change that accepts, validates (`>= 0`) and serves the knob.
  Both sides ship in the same deploy window, as for every earlier bump. A pipeline that declares no knob behaves
  as it does today.
- **Docs:** `docs/configuration.md` (`GROUP_INPUT_TOKEN_BUDGET` row), and the next release's upgrade guide: the
  paired ADAS deploy for contract `6`, the new knob, and the counter's new `pipeline` tag. No pipeline's input
  changes on upgrade.
- **Metrics:** `runner_group_input_budget_cut_total` is now a series per pipeline. Dashboards that read it
  unlabelled still sum correctly.
