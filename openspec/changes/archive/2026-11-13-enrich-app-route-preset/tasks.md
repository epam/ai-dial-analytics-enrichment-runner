## 1. Contract and feed shape

- [x] 1.1 Add `DIAL_APP_ROUTE` to `adas/dto/TransformPreset` and `route` to `TransformDto`. Raise
  `AdasContract.VERSION` to `5`.
- [x] 1.2 Extend `FeedShapeTest` and `TestPipelines` so a `dial_app_route` entry deserializes with its
  route, and a `chat_completion` entry carries no route.
- [x] 1.3 Add the route-grammar check (`RoutePath`) to admission in `PipelineRouting`, and refuse a route
  entry whose route is absent or malformed. Test every case of the admission scenario.

## 2. Call path

- [x] 2.1 Add `dial.route-read-timeout-ms` (`DIAL_ROUTE_READ_TIMEOUT_MS`, default `180000`) to
  `DialProperties`. Add a second `RestClient` bean in `DialClientConfiguration` with the same base URL,
  `Api-Key` and timing interceptor.
- [x] 2.2 Add `DialClient.appRoute(deployment, route, body)`:
  - `POST /v1/deployments/{deployment}/route/{segments…}`, with each segment encoded by `pathSegment`;
  - no `api-version`;
  - returns the raw body.
- [x] 2.3 Introduce a per-preset `DialCall` strategy that covers body building, the call, output
  extraction, repair support and usage. The chat strategy reproduces today's behavior exactly. The route
  strategy adds no `response_format`, extracts the whole body, does no repair, and reports usage as one
  call.
- [x] 2.3a Assemble a route's output from the parsed body by each resolved output's `source` (compiled once
  through `JsonataEvaluator`), or by its name. A value that evaluates to nothing is left out. A body that is not a
  JSON object and a failing `source` each dead-letter at `validate` with the raw body. Admission refuses a
  `source` that does not compile, naming its output.
- [x] 2.4 Route `TransformInvoker.invoke` and `callWithRepair` through the strategy, and add a `preset`
  span attribute.
- [x] 2.5 Record spend for a route call with `SpendService.record(..., 0, "calls", failure)`.

## 3. Template renderer

- [x] 3.1 Add `{{name:json}}` to `TemplateRenderer`, keeping the single pass and the unbound check on the
  name. It renders a string as quoted JSON, a number or boolean bare, a list or map as JSON text, and null
  as `null`.
- [x] 3.2 Unit tests cover the three scenarios of "A placeholder may render its value as raw JSON", plus
  an unknown suffix failing as unbound.

## 4. Integration tests

- [x] 4.1 Row and group WireMock tests for a route pipeline:
  - the exact path, and no `api-version`;
  - a body without `response_format`;
  - the whole body mapped to columns;
  - a nested body read by an output `source`, with an extra `usage` member ignored;
  - an absent optional field written as NULL.
- [x] 4.2 An invalid route body is dead-lettered at `validate` after exactly one call. `503` retries and
  `400` dead-letters at the DIAL-call stage.
- [x] 4.3 Spend shows 2 calls and 0 tokens for two route calls, and the calls budget pauses the pipeline.
- [x] 4.4 The route read timeout applies to route calls and not to chat calls.

## 5. Docs and specs

- [x] 5.1 Add the `DIAL_ROUTE_READ_TIMEOUT_MS` row to the DIAL Core table in `docs/configuration.md`, and
  widen the table intro from "chat/completions calls" to chat/completions and application-route calls.
- [x] 5.2 Add an application-route pipeline example to `docs/e2e-runbook.md` that uses
  `{{members:json}}`.
- [x] 5.3 Confirm the merged `enrichment-worker`, `spend-tracking` and `pipeline-sync` specs match the
  shipped behavior.

## 6. Verify

- [x] 6.1 `./gradlew clean build` passes.
