## Context

`TransformInvoker` runs one shared pipeline for both grains: render, build, call, validate (with repair),
then map. `DialClient.chatCompletion` hardcodes the chat-completions path and `api-version`.
`DialRequestBuilder` always adds `response_format`, and its repair appends a `messages` entry.
`ChatResponse.firstContent()` unwraps the envelope, and spend records `usage.total_tokens`.
`TemplateRenderer` escapes every value for embedding inside a JSON string.

ADAS's paired change adds `preset: dial_app_route` and `transform.route`, keeps the route allowlist on
its own side (the `app-routes` instance setting, guarded so an enabled pipeline's route is always
allowed), and raises the contract to `5`. The runner therefore needs no allowlist knowledge.

## Goals / Non-Goals

**Goals:**

- One extra call shape inside the existing invoker, sharing the rate limiting, span, timer, retry
  classification and DLQ paths.
- A route call never carries chat-only members, and never retries for a correction it cannot request.
- A raw-JSON placeholder form that matches ADAS's validation exactly.

**Non-Goals:**

- Enforcing the allowlist (ADAS owns it).
- Parsing an application's own usage block into tokens or cost.
- HTTP methods other than `POST`, query strings, or extra headers on the route call.

## Decisions

### The invoker dispatches on preset through a small call strategy

Each preset is a `DialCall` that owns three steps: building the body (which decides whether
`response_format` is added), making the call, and extracting the output (the envelope's content, or the
raw body). Each also says whether repair is supported and reports usage (tokens, or a call). The
invoker's loop runs the repair only when the strategy supports it.

- *Alternative: `if (preset == …)` branches inside the invoker.* Rejected. Three branch sites (build, call,
  extract) would drift apart, and the repair loop would grow a fourth.

### No repair retry on a route

A corrective message only means something to a chat model. Resending the identical body to a route
costs a second call for the same deterministic answer. The unit is dead-lettered at `validate` on the
first failure, with the raw body attached, so the operator can requeue it once the application is fixed.

### The route is encoded segment by segment

`UriBuilder.pathSegment(...)` encodes each `/`-separated segment. Admission has already refused anything
outside `[A-Za-z0-9._~-]`, `.`/`..`, `?`, `#` and `%`, so encoding is a second guard, not the only one.

### A separate read timeout for route calls

An application usually makes several model calls per request, so its latency tail is longer than one
model call's. Raising the shared `DIAL_READ_TIMEOUT_MS` would loosen the hang detection for every chat
call too. The new property, `DIAL_ROUTE_READ_TIMEOUT_MS`, defaults to `180000`. It is applied with a
second `RestClient` bean that shares the base URL, the `Api-Key` header and the timing interceptor.

- *Alternative: a per-pipeline `advanced.call_timeout`.* Deferred. It is an ADAS declaration change, and
  no case needs a per-pipeline value yet.

### Spend records a call with zero usage

The `calls` unit already exists in `SpendService`. A route pipeline is bounded by the calls budget. An
application's own `usage` block is not interpreted, since its shape is the application's own.

### `{{name:json}}` in the same single pass

The placeholder pattern gains an optional `:json` group. The name before the suffix is what the
unbound check compares. Any other suffix is not matched as a suffix, so it stays part of the name and
fails as unbound. The pass stays single, so substituted data is never re-scanned.

### Contract 5

The constant is raised in `AdasContract`, and `FeedShapeTest` / `TestPipelines` gain the route preset.

## Risks / Trade-offs

- **A route answering extra top-level members fails strict validation** (`additionalProperties: false`
  on a composed schema).
  → This is the specified contract. The application answers the bare output object.
- **No repair means a flaky application dead-letters more readily than a model would.**
  → Transient statuses (5xx/429/408/425) still retry with backoff. Only a 2xx with a wrong body goes
  straight to the DLQ, which is requeueable.
- **Skewed deploy.**
  → The echo check stalls the runner with no calls made. Deploy both halves together.

## Migration Plan

- Deploy with ADAS's contract-5 build.
- No database migration: `spend_counter.usage_unit` already accepts `calls`.
- `DIAL_ROUTE_READ_TIMEOUT_MS` is optional.
- Rollback: roll back with ADAS, after the route pipelines are disabled there.
