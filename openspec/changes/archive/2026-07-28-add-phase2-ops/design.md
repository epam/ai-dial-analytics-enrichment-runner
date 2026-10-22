## Context

Observability for the group and Class-1 lanes accreted one signal at a time: `add-class1-materialize` added Class-1 rows-written and tick failures because
a lane with no queue and no DLQ is otherwise invisible; `add-group-dirty-mark` added the unsupported-rules gauge because a refused
rule must not look healthy; `add-group-cost-ceiling` added the ceiling-blocked gauge because a working budget looks exactly like a
group that stopped changing. Each was the minimum that change needed to be verifiable.

What is left is the aggregate view. The group path has a state machine (`group_state`), a scheduled sweep and a
worker, and nothing reports whether the machine is turning: a dirty backlog that is not draining, a state table
growing without bound, a sweep that takes longer than its own interval, all look like silence. Operability is
similarly thin — the only group-level action is pausing the whole rule.

Two older gaps from the row lane also remain: `spend_counter` is written but never exposed, so the gate is invisible until it
fires; and `RunnerMetrics.recordDialError` files a 429 with 5xx and mapping failures, so rate-limit pressure
cannot be told from model failure.

## Goals / Non-Goals

**Goals:** make "stuck" distinguishable from "quiet" for the group path; expose the two row-lane signals that
exist but are unreadable; give an operator a group-level action; keep every new gauge cheap and bounded; and
leave the runbook exercising the group and Class-1 lanes rather than only the row lane.

**Non-Goals:** alerting rules, thresholds or dashboards — those belong with the deployment, which knows its own
tolerances; per-group time series; distributed tracing changes beyond the one missing span; and anything
multi-replica.

## Decisions

- **Per-rule tags only; never per-group.** A group key is unbounded — one series per conversation would exhaust
  a Prometheus instance and is exactly what `observability`'s cheap-and-bounded requirement exists to prevent.
  Group-level detail belongs on spans and in the MDC, where it already is, and in the introspection endpoint,
  which is pull-based and paged. So the group metrics are aggregates (counts, durations) tagged by rule.

- **The aggregate group gauges are refreshed off the scrape path**, like `runner.dlq.size` already is. Active
  groups and dirty backlog are `COUNT`s over `group_state`; running them inside a scrape makes scrape latency a
  function of table size, and a scrape is not the right place to discover that. A scheduled refresh keeps the
  gauge cheap and the query predictable.

- **Queue depth becomes per-task.** The new lanes turned one work-item type into four (`enrich-chunk`,
  `enrich-group`, `upsert-rows`, plus the recurring ticks), so a single summed gauge now adds unrelated things
  and cannot answer "which lane is backed up" — the only question depth is asked for. `InflightCounter` already
  counts by task name; the gauge just needs to stop collapsing them.

- **A 429 gets its own counter, and stays in the error total.** Rate-limit pressure and model failure need
  telling apart, but a 429 *is* a failed call — removing it from the error count would make the error rate
  under-report. So it is counted twice, in two dimensions, which is what a tagged counter is for.

- **The spend gauge reports the current day's usage per rule, not the budget.** The budget is configuration and
  is already knowable; what is not knowable is how close a rule is to it. Per-rule and evicted with the rule,
  like the lag gauge, so cardinality stays bounded.

- **Group introspection is read-only plus one action.** Listing a rule's groups (version, computed version,
  truncation, evaluation count) answers "why has this group not been evaluated", which today requires database
  access. The one action is a per-group requeue, because the alternative — pausing the rule — punishes every
  other group to re-drive one. *Rejected:* an endpoint to clear a group's ceiling count (it would let an
  operator silently exceed a budget the rule's author set, and waiting for the day to roll over is the honest
  remedy); and mutating `group_version` directly.

- **Per-group requeue re-enqueues through the same deterministic id the sweep uses**, so it cannot create a
  duplicate item for a version already queued, and it respects nothing else — deliberately. It is the operator
  override; the sweep's gates are for the automatic path. It does not bypass the *ceiling*, however, because
  charging still happens on evaluation and the next sweep would still decline the group.

- **The runbook is rewritten around a dataset-agnostic fixture**, per the decision that this change
  ships capability rather than content: a `chat_id`-grained enrichment over `dial_usage_log` and a group rule
  over it, plus a `type=sql` rule. Naming the conversations datasets would tie the runbook to a change that has
  not landed.

- **The placeholder spec Purposes get written now.** Nine specs say `TBD - created by archiving change …`, which
  is what OpenSpec's archive step leaves behind and which every prior change left alone. They are the first
  thing a reader of a capability sees, and fixing them is cheaper than the aggregate cost of nine readers
  bouncing off them. Bundled here because this is the change that already touches docs.

## Risks / Trade-offs

- **New gauges add scheduled queries** → two `COUNT`s over `group_state` per refresh interval, on an indexed
  table that eviction bounds. Cheaper than the scrape-path alternative and cheaper than not knowing.

- **Group introspection exposes group keys over HTTP** → group keys are identifiers, not content: the endpoint
  reports versions, timestamps and counts, never member rows or labels. It sits behind the same operational
  surface as the DLQ listing, which already exposes grain keys.

- **Per-group requeue is an override with no gate** → intended, and bounded by being an explicit operator
  action rather than a schedule. It cannot exceed a ceiling in effect, because the evaluation it triggers is
  charged like any other.

- **Metrics without alerts are only half of operability** → true, and deliberate: thresholds depend on a
  deployment's traffic and tolerance, so the service exposes signals and the deployment decides what is
  abnormal. The runbook shows which signals matter for which failure.

- **Writing nine spec Purposes in a milestone about ops is scope drift** → mild, and the alternative is that
  they are never written, since no future change will have a better claim on them than the one already editing
  the docs.
