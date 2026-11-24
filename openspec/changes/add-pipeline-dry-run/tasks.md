## 1. Contract and ADAS client

- [x] 1.1 Raise `AdasContract.VERSION` to `7`, with a one-line note for the trial-input call site
- [x] 1.2 Add `AdasTrialInputClient` on a `RestClient` that shares the contract interceptor, base URL and read timeout but has no default `Api-Key`; it sends the caller's credential per request and falls back to the service key only in `none` mode
- [x] 1.3 Add the DTOs `TrialInputRequest` and `TrialInputResponse {preview (opaque JSON), pipeline: PipelineDto, input_rows, truncated}`
- [x] 1.4 Carry the inbound credential (`Authorization`, else `Api-Key`) from the request's own headers in one value type (`InboundCredential`)

## 2. Evaluation core

- [x] 2.1 Add `TransformInvoker.trial(...) → TrialOutcome`, sharing `callFor`, render, `callWithRepair` and output mapping with `derive`; return the rendered first body, last raw reply, attempts, usage, latency and the classified failure instead of throwing
- [x] 2.2 Add a non-blocking `tryAcquire` to the DIAL rate limiter for dry runs; keep `acquire` unchanged for workers
- [x] 2.3 Keep `SpendService.record` on every dry-run call (success, and failure on a permanent refusal) under the served declaration's `generation`
- [x] 2.4 Run `PipelineRouting.unsupportedReason` (already public, per entry) on the served `PipelineDto`

## 3. Dry-run service and endpoint

- [x] 3.1 Add `DryRunService`: per-pipeline in-flight guard, spend gate check, trial-input read, admission, row mapping via `InputMapper` or group assembly via `GroupInputAssembler`, bounded concurrent evaluation
- [x] 3.2 Map failures to stages (`input_map`, `group_map`, `dial_call`, `validate`, `output_map`) using the workers' classification, without DLQ, upsert, stamp, charge or breaker
- [x] 3.3 Build the response: `preview` passed through, `evaluations` with request/response capped at 64 KiB and the truncation flags
- [x] 3.4 Add `DryRunController` `POST /v1/pipelines/{name}/try` with body validation (unknown member → 400, `limit` 1–5)
- [x] 3.5 Add `DefaultExceptionHandler` mappings: ADAS refusal passthrough (status, code, message), 409 `dry_run_in_progress`, 429 `spend_budget_exhausted`, 429 `dial_rate_limited`, 422 `pipeline_not_runnable`, 503 `adas_unavailable`, 503 `adas_contract_mismatch`
- [x] 3.6 Log one boundary line per dry run (pipeline, outcome, attempts, latency) and no content

## 4. Tests

- [x] 4.1 Row-grain chat (WireMock DIAL): one call with `response_format`; `limit: 3` gives three calls in order; outputs mapped
- [x] 4.2 Group: the whole fetched group is assembled under the input token budget; included and omitted counts reported
- [x] 4.3 App route: route URL, no `response_format`, no corrective retry, `source` mapping
- [x] 4.4 Failures: DIAL 404 → `dial_call`/`404`; schema miss after retry → `validate` with `attempts: 2`; JSONata output error → `output_map`; unresolved placeholder → `input_map` with no call
- [x] 4.5 Side effects: no DLQ row, no upsert task or write-back, no group state; spend counter incremented (the dry-run path holds no breaker or cursor dependency to touch)
- [x] 4.6 Refusals: ADAS 403/422 passthrough with no DIAL call; spend gate 429 before the trial-input read; rate limiter 429 before any call, and a refused corrective retry answered as a failed `dial_call` evaluation keeping the paid reply; concurrent 409; admission 422; ADAS timeout 503 with the verdict unchanged; contract mismatch 503
- [x] 4.7 Credential: the trial-input request carries the caller's bearer token and no service `Api-Key`; `none` mode uses the service key
- [x] 4.8 Parity: the same fixture through the worker and through `try` sends identical DIAL requests and maps identical outputs
- [x] 4.9 Logging: at TRACE, no record contains member content, the rendered request or the reply (pin `org.springframework.web.servlet.mvc.method.annotation`, which logged the response body)

## 5. Docs and verification

- [x] 5.1 Document `POST /v1/pipelines/{name}/try` in the runner's API docs, and note in the exposure docs that an ingress must allow the route read timeout for it
- [x] 5.2 Name the paired ADAS contract `7` release in the release notes and upgrade guidance
- [x] 5.3 Confirm the merged specs match the shipped behaviour
- [x] 5.4 `./gradlew clean build` passes
