## Context

The runner evaluates `llm` enrich pipelines in two lanes, row-grain and group, each with two presets,
`chat_completion` and `dial_app_route`. All four combinations funnel into `TransformInvoker.derive(pipeline,
transform, inputs)`: render the template, build the preset's body, call DIAL, retry once with a corrective
message (chat only), validate against the response schema, and map the outputs. Its inputs come from
`InputMapper` (row) or `GroupInputAssembler` (group), which are pure. Inside `derive`, `invoke` takes a permit
from the pipeline's rate limiter and records spend. Every other side effect sits in the callers
(`WorkerService`, `GroupWorkerService`): DLQ, upsert enqueue, group stamp and charge, circuit breaker.

ADAS's preview calls no model, and the runner has no rendering or test-call surface. The admin UI already
calls this runner's `/v1` endpoints from its server side with the user's bearer token. The paired ADAS change
`add-enrich-trial-input` adds `POST /v1/pipelines/{name}/trial-input`. It returns ADAS's preview, the compiled
declaration ADAS gated, and the uncapped rows the runner would receive, read under whichever identity calls
it.

## Goals / Non-Goals

**Goals:**

- Show an author what arming would do for one sampled row (up to five) or one group: the request sent, the
  reply received, whether it validated, and the values written. Catch a missing deployment, a refused route
  body, and a reply that does not parse or map.
- Reuse the production path, so a dry run that succeeds predicts a worker that succeeds.
- Keep the author's data restrictions: the dry run reads only what the author's preview may.
- Account for the money a dry run spends.

**Non-Goals:**

- Evaluating an unsaved candidate declaration. ADAS evaluates the stored one, and so does this.
- Class-1 (`sql`) pipelines, whose ADAS preview already shows computed values.
- Writing a dry run's result to the target, or offering to.
- A queued or asynchronous dry run. One call returns one answer.

## Decisions

### The runner owns the dry run, reading through one ADAS snapshot

`POST /v1/pipelines/{name}/try` calls trial-input once, evaluates what it returns, and answers with ADAS's
preview plus evaluations. A client that calls only `try` gets the preview grid too, and a client that wants
the free sample keeps calling ADAS's `/preview`. No input is read twice for one click.

- *Rejected: the UI chains ADAS preview → the runner.* Preview values are capped at 2048 bytes and groups at
  five members. Shipping the uncapped rows through the browser-facing server action would move whole groups
  of request bodies through the UI tier.
- *Rejected: the runner reads `/scan` and `/group-rows` itself.* Those authorize by the runner's service
  grants, use the stored (possibly stale) mapping of a draft, and `/scan` reads oldest-first from a cursor.
- *Rejected: ADAS calls DIAL.* That would need a second renderer, which both specs rule out.

### The caller's credential is forwarded on trial-input only

`AdasTrialInputClient` is built on a `RestClient` that shares the contract interceptor and the ADAS base URL
and timeout, but **not** the default `Api-Key` header. Per request, it sends the credential that authenticated
the inbound call, read from that call's own headers: its `Authorization` header, else its `Api-Key` header, the order
the runner's authentication prefers them in. The headers rather than the security context, because the API-key
authentication does not keep the key it introspected.
ADAS sees the author, so its column, row, gate and allowlist checks apply to the author. In `none` mode there
is no inbound credential, and the service key is used.

No other ADAS call changes identity. This is the only place a user credential leaves the runner, and only
towards ADAS, which accepts the same issuers.

### A side-effect-scoped entry point on `TransformInvoker`

`derive` gains a sibling, `trial(pipeline, transform, inputs) → TrialOutcome`, that runs the same `callFor`,
render, `callWithRepair` and `OutputMapper` steps. It returns the rendered body of the first attempt, the last
raw reply, the attempt count, usage and latency, and the classified failure (stage, DIAL status, validation
errors) instead of throwing. It keeps `invoke`'s spend recording and rate-limiter use. The rate limiter is
asked through `tryAcquire`, which takes a permit only when one is free, so a busy pipeline answers 429 instead
of queueing behind its workers. A refusal before any call is a 429. A refusal after a call was paid for (a
second row, or a corrective retry) ends only that evaluation, as `failed` at `dial_call`, so the answers
already bought are returned. `DialCall` stays package-private, and the trial entry point lives in the same
package.

Failure classification reuses the workers' table, so the stages read the same as in the DLQ. The difference
is that nothing is dead-lettered, and a transient failure is reported instead of retried with backoff: a
dry run is interactive, and minutes of retries would be the wrong answer to a click.

- *Rejected: a `dryRun` flag threaded through `WorkerService`.* The worker owns the queue, upsert and DLQ
  transactions. A flag there puts every write one missed branch away from running on a dry run.
- *Rejected: a no-op spend sink.* The spend is real. Hiding it would make the daily budget report less than
  what was spent.

### Admission is re-run on the served declaration

`PipelineRouting`'s admission checks (route grammar, output `source` compilation, template, model and
response schema presence) run during sync, on enabled pipelines only. A draft has never been through them,
so the dry run runs the same admission on the `pipeline` trial-input returns and refuses with 422
`pipeline_not_runnable` before any DIAL call.

### Concurrency and size bounds as constants

- One dry run per pipeline, tracked in memory (the runner is single-replica by design), answers 409 while
  one runs.
- Row-grain rows are evaluated concurrently, at most `limit` (≤5) at a time, on virtual threads of the dry run's
  own, so a dry run cannot starve the worker pool.
- `request` and `response` are capped at 64 KiB each in the answer. The full request is still sent.
- Spring MVC's response-body writer (`org.springframework.web.servlet.mvc.method.annotation`) logs the body it
  writes at DEBUG. No endpoint returned content before; this one does, so the logger joins the pinned set.

These are constants rather than configuration keys. They bound an interactive response, not throughput, and
no deployment needs a different value to stay safe. `docs/configuration.md` is unchanged.

### Time

A dry run is synchronous. Its worst case is the preset's read timeout (60 s for chat, 180 s for a route)
times its attempts, plus the trial-input read. The admin UI sets no fetch timeout. Any ingress in front of
the runner must allow that long, and `docs/` says so beside the existing exposure notes.

## Risks / Trade-offs

- **[A user credential is forwarded]** → Only to ADAS, only on trial-input, never logged, and never stored.
  The scenario that pins "no service key on a caller's request" also pins that the token is the caller's.
- **[Dry runs consume the day's budget]** → By design. The gate refuses once the budget is reached, and the
  runtime view shows the spend.
- **[An ingress timeout shorter than a route call]** → Documented. The response would be lost but the spend
  recorded. The 409 guard clears when the server-side call ends, not when the client disconnects.
- **[Large group payloads in memory]** → Bounded by ADAS's group fetch byte cap, the same bound the group
  worker lives under, and by one dry run per pipeline.
- **[Drift between the dry run and the worker]** → Shared code from input mapping to output mapping. A test
  runs the same fixture through the worker and the dry run against one WireMock DIAL and asserts identical
  requests and mapped outputs.

## Migration Plan

1. Ship with ADAS contract `7` (`add-enrich-trial-input`). A mixed pair stalls all work through the existing
   echo check and spends nothing.
2. The admin UI enables "Test with model" when the runner is configured. An older runner answers 404 on
   `try` (`not_found`), which the UI shows as "not available".
3. Rollback: roll both services back together.

## Open Questions

- Should a dry run's spend also be counted separately (for example a `dry_runs` column) so the runtime view
  can show it apart? Not needed for correctness. It can follow if authors ask for it.
