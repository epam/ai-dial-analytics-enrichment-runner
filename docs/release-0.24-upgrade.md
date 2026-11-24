# Enrichment Runner 0.24 — upgrade notes

How to move a runner from **0.23.0** to **0.24**. A runner on an older build first follows
[`release-0.23.0-upgrade.md`](release-0.23.0-upgrade.md) to 0.23.0.

The runner moves as a pair with **ADAS 0.24**. Follow ADAS's own `docs/release-0.24-upgrade.md` for its side.
The contract version rises from `4` to `7`, and neither build works with the other's previous release, so both
are upgraded in one window. Three changes raise it, and all ship in this release:

- **`5`: group and row pipelines may call a DIAL application route** (`preset: dial_app_route`) instead of chat
  completions (§2).
- **`6`: a group pipeline may set its own input token budget** with `advanced.input_token_budget`. `0` switches the
  runner's trimming off for that pipeline (§3).
- **`7`: a pipeline can be dry-run against its model or application** with `POST /v1/pipelines/{name}/try`, which
  reads its input through ADAS's new `trial-input` (§4).

No runner configuration has to change. A pipeline that uses neither feature behaves as it did on 0.23.0.

---

## 1. Upgrade procedure

### Before the window — on the old build

Nothing to do. One optional key is new (§5). Its default fits most deployments.

### The window

1. **Scale the runner to 0** and wait for the pod to be gone. A rolling update is not enough: an old and a
   new pod would overlap against a half-updated ADAS.
2. **Deploy ADAS 0.24.** Follow its upgrade notes. This release is more than a redeploy on the ADAS side.
3. **Deploy runner 0.24.** There is no migration.
4. **Scale the runner back to 1.** Readiness stays DOWN until the first pipeline sync succeeds, so a Ready
   pod confirms the pair matches.

**While the runner is at 0**, nothing is lost. ADAS's Class-1 materialize positions stop advancing, so
dependent rollups lag until a cadence after the runner is back. Keep the window short.

### After the window

1. **Verify:**
   - the pod is Ready and `runner.adas.contract.compatible` is `1`;
   - `runner.pipelines.cached` equals the number of enabled pipelines you expect.
2. **Only then** declare route pipelines (§2) or per-pipeline budgets (§3).

**No runner state is migrated or discarded.** Scan cursors, Class-1 positions, group state and queued work
are untouched.

**Rollback** means rolling ADAS and the runner back together. A 0.23.0 runner speaks contract `4`, and ADAS
0.24 refuses it. Before rolling back, follow ADAS's rollback steps for route pipelines. A per-pipeline budget
needs nothing: a 0.23.0 pair does not serve the member, and every group pipeline returns to
`GROUP_INPUT_TOKEN_BUDGET`.

---

## 2. Pipelines calling a DIAL application route

An `llm` transform with `preset: dial_app_route` and a `route` is sent to
`POST /v1/deployments/{model}/route/{route}` with the rendered template as its body. It carries no
`response_format` and no `api-version`, and each output reads its value from the response body (see
[`enrichment-worker`](../openspec/specs/enrichment-worker/spec.md)). ADAS decides which routes a pipeline may
call, through its `app-routes` setting.

What changes on the runner side:

- **No repair retry on a route.** A response that fails its `response_schema` is dead-lettered at `validate`
  after one call. A chat-completion pipeline still gets its repair retry.
- **Its own read timeout.** Route calls use `DIAL_ROUTE_READ_TIMEOUT_MS` (default `180000`), because an
  application that calls models itself is slower than one model call. `DIAL_READ_TIMEOUT_MS` keeps applying to
  chat calls.
- **Spend.** A route call is recorded as one call with no token usage. `SPEND_BUDGET_DAILY_CALLS` applies to it,
  and `SPEND_BUDGET_DAILY_TOKENS` does not accrue (see [`spend-tracking`](../openspec/specs/spend-tracking/spec.md)).
- **Metrics.** `runner.dial.latency`, `runner.dial.calls` and `runner.dial.rate.limited` carry a `preset` tag,
  `chat_completion` or `dial_app_route`. A query that sums over `preset` returns the same totals as before.

## 3. Per-pipeline group input budget

The runner trims a group's assembled input to an approximate token budget before the model call. It drops the
oldest members first and estimates four characters per token (see
[`group-enrichment`](../openspec/specs/group-enrichment/spec.md)). Until now the budget was the runner-wide
`GROUP_INPUT_TOKEN_BUDGET`. A group pipeline may now declare its own in ADAS:

| `advanced.input_token_budget` | Budget for that pipeline   |
|-------------------------------|----------------------------|
| absent                        | `GROUP_INPUT_TOKEN_BUDGET` |
| `0`                           | no runner trimming         |
| `N > 0`                       | `N`                        |

The preset does not change the default. A `dial_app_route` application that tokenizes properly and chooses what
to keep opts out with `0`. ADAS's own limits (`GROUP_FETCH_LIMIT`, ADAS's response-byte cap, `member_select`)
still apply to every pipeline. When they narrow a group, the prompt's `members_truncated` and `members_notice`,
and the row's `truncated` flag, still say so.

`runner.group.input.budget.cut` now carries a `pipeline` tag, so a budget can be tuned for the pipeline that hits
it. A query that sums over `pipeline` returns the lane total as before. The series of a pipeline appears with its
first cut, not at startup, so a panel or alert that expects a zero series before any cut reads it as absent.

A negative `advanced.input_token_budget`, which ADAS refuses, is read as absent and logged once per pipeline
generation.

---

## 4. Dry run against the model

`POST /v1/pipelines/{name}/try` takes ADAS's preview sample of an `llm` pipeline, enabled or disabled, and evaluates
it the way a worker would: one row by default (`limit`, at most 5) or the sampled group, rendered, sent to the
deployment or route, validated and mapped. The response is ADAS's preview plus one evaluation per row or group, with
the request sent, the reply, the outputs, and the stage a failure would have been dead-lettered at (see
[`pipeline-dry-run`](../openspec/specs/pipeline-dry-run/spec.md)).

- **It spends.** Each call counts towards the pipeline's daily spend and is refused with `429 spend_budget_exhausted`
  once the soft budget is reached. It takes a rate-limit permit only if one is free (`429 dial_rate_limited`
  otherwise), and runs one at a time per pipeline (`409 dry_run_in_progress`).
- **It writes nothing else.** No dead letter, write-back, cursor move, group stamp or breaker count.
- **It reads as the caller.** The runner forwards the caller's `Authorization` or `Api-Key` to ADAS on this one
  call, so ADAS's column and row restrictions and the `app-routes` allowlist apply to the caller.
- **A proxy must allow the model call's time.** See [`kubernetes-exposure.md`](kubernetes-exposure.md#ingress).
- **One more logger is pinned at `INFO`**: `org.springframework.web.servlet.mvc.method.annotation`, which below
  `INFO` would log the dry run's response body. It cannot be lowered at runtime.

---

## 5. Reference

### Migrations

None.

### Configuration

Added, optional:

| Variable                     | Default  | Effect                                                         |
|------------------------------|----------|----------------------------------------------------------------|
| `DIAL_ROUTE_READ_TIMEOUT_MS` | `180000` | Read timeout for application-route calls (§2).                 |

`GROUP_INPUT_TOKEN_BUDGET` keeps its default. It now applies to group pipelines that declare no
`advanced.input_token_budget` (§3). Nothing is removed. Descriptions are in [`configuration.md`](configuration.md).

### Contract

`Adas-Contract-Version` is `7`, echoed back by ADAS. The check runs before a response is read, so a mismatched pair
never renders a template or calls a model. A runner meeting an ADAS on another version stalls: it starts no new work,
logs `ADAS is incompatible with this build's contract version`, and `runner.adas.contract.compatible` reads `0`.

### Probes

Unchanged: liveness `/actuator/health/liveness`; readiness and startup `/actuator/health/readiness`.
