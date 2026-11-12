## Why

The runner can only call a deployment as `POST /openai/deployments/{model}/chat/completions` and read the
output from `choices[0].message.content`. A DIAL application whose function is a custom route
(`/v1/deployments/{id}/route/{path}`) taking and returning plain JSON has to maintain a chat-completions
shim to be enriched with. ADAS is adding a second transform preset, `dial_app_route`, together with a
`transform.route` member and a raw-JSON placeholder form, under runner contract `5`. This change is the
runner half.

## What Changes

- **New call path.** For `transform.preset: dial_app_route` the runner calls
  `POST {DIAL}/v1/deployments/{model}/route/{route}`. The body is the rendered template overlaid with
  `transform.params`. There is no `response_format` and no `api-version`. The output is assembled
  from the response body: each output takes its `source` expression's result, or the member of its own name.
  The mapping runs before validation, so an application keeps its own response shape, and a nullable output the
  application omits is written as NULL.
  `chat_completion` is unchanged.
- **Validation.** The route's output is validated against the entry's `response_schema` as a model's is,
  but **without the repair retry**. A route has no conversational turn to correct, so an invalid or
  non-JSON body is dead-lettered at `validate` with the raw response on the first attempt.
- **Spend.** A route call is recorded as a call, with no token usage. The calls budget of the daily
  spend gate applies to it; the token budget does not accrue.
- **Raw-JSON placeholders.** The template renderer accepts `{{name:json}}`, which renders the bound value
  as a JSON literal (unescaped) in value position. The `{{name}}` form is unchanged.
- **Admission.** A `dial_app_route` entry whose `route` is absent or fails the route grammar is refused
  at admission. ADAS refuses that shape, and the runner checks it again because the route is appended to
  a URL.
- **Timeout.** Route calls get their own read timeout, `DIAL_ROUTE_READ_TIMEOUT_MS`, because an
  application call typically does several model calls of its own.
- **BREAKING (contract):** `AdasContract.VERSION` goes from `4` to `5`. ADAS and the runner deploy
  together; during skew the existing echo check stalls the runner, so no call is made.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `enrichment-worker`:
  - the DIAL call is dispatched on `preset`;
  - repair applies to `chat_completion` only;
  - the `:json` placeholder form.
- `spend-tracking`: a route call's usage is recorded as a call.
- `pipeline-sync`:
  - contract version `5`;
  - admission refuses a route entry with an absent or malformed `route`.

## Impact

- **Code:**
  - `adas/dto/TransformPreset` and `TransformDto` (`route`);
  - `dial/DialClient`, which gains `appRoute(...)`, and a route-response reader;
  - `worker/TransformInvoker` (dispatch, no repair on route), `DialRequestBuilder`, `TemplateRenderer`;
  - `spend/SpendService` caller, `sync/PipelineRouting` (admission), `adas/AdasContract`;
  - `configuration/DialProperties` and `DialClientConfiguration`, for the route timeout.
- **Config:** a new `DIAL_ROUTE_READ_TIMEOUT_MS` (`dial.route-read-timeout-ms`).
- **Network:** a route call goes to the same DIAL Core host as chat completions, so egress is unchanged.
- **Paired change:** ADAS's change of the same name ships the preset, the `app-routes` allowlist
  setting and contract `5`.
