## Why

The group and Class-1 work added two execution lanes, a per-group state machine and a scheduled sweep. Its observability arrived
piecemeal: each change added the one or two signals it could not be verified without, and stopped there.
The result is a runner whose group path can be stuck in ways nothing reports — a dirty backlog that is not
draining, a `group_state` growing without bound, a sweep taking longer than its own interval — and whose only
group-level operator action is pausing the whole rule.

Two older row-lane gaps are still open too: spend is recorded but not exposed, so the gate that pauses a rule is
invisible until it fires; and a DIAL 429 is counted as an ordinary error, so rate-limit pressure looks
identical to model failure.

This change closes the observability and operability gaps,
and updates the end-to-end runbook to exercise the group and Class-1 lanes as shipped.

## What Changes

- **Group metrics**: active-group count, dirty backlog, sweep duration and enqueue rate, group-worker
  throughput, and per-rule group lag. These are the signals that distinguish "quiet" from "stuck", which is
  the question the group path cannot currently answer.
- **The two row-lane gaps**: a spend gauge (so an approaching budget is visible before it pauses a rule) and a
  distinct DIAL-429 counter (so rate-limit pressure is not filed as model failure).
- **Queue depth per task**, since the new lanes turned one work-item type into four — a single depth gauge now sums
  unrelated things and cannot show which lane is backed up.
- **Tracing completes the set**: `enrichment.dirty-mark` is the one span the new lanes never added, leaving the
  group path's first half untraced.
- **Group-state introspection and per-group requeue**: an operator can see a rule's groups (version, computed
  version, truncation, evaluation count) and re-drive one, instead of choosing between pausing the whole rule
  and waiting.
- **The local e2e runbook covers the new lanes**: a group rule end to end (mark → sweep → evaluate → one row per
  group key, then a re-ingested member re-firing it) and a `type=sql` rule populating its column with **zero**
  DIAL traffic.
- **Opportunistic debt**, cheap to fix while here: nine main specs still carry the placeholder Purpose
  `TBD - created by archiving change …`; `README.md` points at a non-existent `openspec/project.md`; and it
  advertises port 5100 while `CLAUDE.md` says 5000.

## Capabilities

### New Capabilities

<!-- none: completes observability and operability over what the group and Class-1 lanes already shipped -->

### Modified Capabilities

- `runner-ops`: "Runner metrics exposed via Prometheus" — the metric set covers the group and Class-1 lanes and
  the two row-lane gaps; "Rule-cache introspection" extends to group state and per-group requeue.
- `observability`: "Runner gauges are cheap and bounded" — new gauges must keep that property, which for
  per-group state means aggregate counts rather than per-group series.

## Impact

- **New code**: `web/controller/GroupAdminController` + its DTOs and mapper;
  `observability/GroupMetrics` (or additions to `RunnerMetrics`).
- **Modified code**: `observability/RunnerMetrics` (the new meters and per-task queue depth),
  `scan/InflightCounter` (count by task name), `scan/RuleScanExecutor` (the dirty-mark span),
  `sweep/GroupReadinessSweep` (duration and enqueue-rate instrumentation),
  `persistence/GroupStateRepository` (the introspection and aggregate queries),
  `spend/SpendService`/`RunnerMetrics` (the spend gauge), `worker/EvaluatorInvoker` (429 classification).
- **Docs**: `docs/e2e-runbook.md` gains the group and Class-1 walkthroughs;
  `docs/configuration.md` and `README.md` lose their stale row-only framing; the nine placeholder spec Purposes
  are written.
- **Migration**: none.
- **ADAS contract**: none.
- **Configuration**: none beyond what already exists — new gauges read state the runner already holds.
- **Cardinality discipline**: per-rule tags only. A group key is unbounded, so it stays on spans and in the MDC
  and never becomes a metric tag — the constraint `observability` already states and which per-group metrics
  would be the natural way to violate.
- **Not here**: alerting rules or dashboards (they belong with the deployment, not the service); and
  multi-replica concerns, still out of scope.
