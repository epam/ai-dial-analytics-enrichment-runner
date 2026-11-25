## Why

ADAS's dry-run preview of an `llm` enrich pipeline calls no model. An author therefore cannot learn before
arming whether the deployment exists, whether a `dial_app_route` application accepts the rendered body, or
whether the reply parses against the response schema and maps onto the outputs. Those failures surface as
DLQ entries after the pipeline is armed and spending. The renderer, the group-input assembler, the DIAL
client, the corrective retry, the validator and the output mapper all live here, so the model-calling dry run
belongs to this service. ADAS's enrich preview requirement already points here.

## What Changes

- New endpoint `POST /v1/pipelines/{name}/try` (`FULL_ADMIN`, like every functional endpoint). It accepts the
  same narrowing as ADAS's preview (`group_key`, or `from`/`to`), plus `limit` for row-grain pipelines
  (default 1, at most 5). It works on **disabled** pipelines, which is its main use, and on enabled ones.
- It reads its input through ADAS's new runner-only `POST /v1/pipelines/{name}/trial-input`, **forwarding the
  caller's own credential**. ADAS then applies the author's column and row restrictions, the arm gate and the
  `app-routes` allowlist, and the runner learns nothing its caller could not preview.
- It evaluates each sampled row, or the sampled group, through the production code path: input mapping,
  group assembly with the token budget, rendering, the DIAL call for either preset with the chat corrective
  retry, response validation and output mapping. It leaves out every side effect except spend: no DLQ entry,
  no upsert, no cursor move, no group stamp or cost-ceiling charge, no circuit-breaker count.
- The response is ADAS's preview, unchanged, plus one `evaluation` per row or group. Each one carries a
  status, the failing stage (named as the DLQ names it), the mapped output values, the raw reply and the
  rendered request (each capped), the DIAL status on a refused call, the retries used, the usage and the
  latency, and for a group the members included and omitted.
- A model or application failure is a **successful** dry run (HTTP 200, `status: failed`). Refusals use the
  `ErrorView` contract: ADAS's refusal passed through with its code, 409 `dry_run_in_progress` (one dry run
  per pipeline at a time), 429 `spend_budget_exhausted`, 429 `dial_rate_limited`, 422 `pipeline_not_runnable`
  (a declaration this runner's admission would refuse), 503 `adas_unavailable`.
- Dry-run calls are **real spend**. They are recorded in `spend_counter` under the pipeline like any other
  call, are refused when the soft daily spend gate is reached, and take a permit from the pipeline's rate
  limiter without waiting behind live work.
- The rendered request is returned to the caller but, as with every rendered prompt, is never logged.
- **BREAKING (ADAS contract):** the runner declares `Adas-Contract-Version: 7` and must ship with the ADAS
  build that serves it.

## Capabilities

### New Capabilities

- `pipeline-dry-run`: the `try` endpoint, its input read under the caller's identity, its side-effect-free
  evaluation, its response, its refusals, and its spend, rate and concurrency rules.

### Modified Capabilities

- `pipeline-sync`: the contract-version requirement adds the trial-input call site and raises the declared
  version to `7`.
- `service-runtime`: the body-handling requirement pins Spring MVC's response-body writer
  (`org.springframework.web.servlet.mvc.method.annotation`) at INFO, since a dry run's response carries rendered
  prompts and members.

## Impact

- **API:** `POST /v1/pipelines/{name}/try`. Already covered by the `/v1/**` `FULL_ADMIN` rule; no
  authorization change.
- **Code:** a new `web/controller/DryRunController` and `dryrun/` service; `AdasTrialInputClient` (a client
  that sends the caller's credential instead of the service `Api-Key`, behind the same contract
  interceptor); a side-effect-scoped entry point on `TransformInvoker` that keeps spend and rate limiting
  but returns the rendered request, raw reply and retry count; `PipelineRouting` admission reused on the
  served declaration; `AdasContract.VERSION = 7`.
- **Reuse, unchanged:** `InputMapper`, `GroupInputAssembler`, `TemplateRenderer`, `DialRequestBuilder`,
  `DialClient`, `ResponseValidator`, `OutputMapper`, `SpendService`, `SpendPauseGate`.
- **Configuration:** none added. Caps on returned request and reply sizes are constants.
- **Paired change:** ADAS `add-enrich-trial-input`, contract `7`.
- **Consumer:** the admin UI's Preview tab gains a "Test with model" action that calls this endpoint through
  its existing runner client. That is a separate frontend change.
