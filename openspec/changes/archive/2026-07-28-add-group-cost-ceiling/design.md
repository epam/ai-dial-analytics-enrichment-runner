## Context

Track A's cost profile is set by one fact: a group is re-evaluated whenever it changes. The dirty-mark
raises `group_version` on every new member, the sweep sees a dirty group, and the worker spends a
`groups:fetch` plus a model call. For a conversation receiving a message a minute, that is a model call a
minute for as long as the conversation lives — and `add-group-worker`'s design records this as the known gap, with
`max_staleness` and the sweep interval as the only brakes.

`ready_when.cost_ceiling` is the declared bound. ADAS validates it positive at registration and documents
it as a "per-group evaluation budget the caller enforces" — so the number is the rule author's, and the
enforcement is entirely the runner's. `add-group-dirty-mark` and `add-group-worker` carry it on `ReadyWhenDto` and do nothing with it.

What already exists to build on: `group_state` is per group and already read by a per-rule query
(`GroupStateWriter.findDirtyAndReady`); the sweep already holds the rule and therefore its `ready_when`;
a `Clock` bean and a test `SettableClock` arrived with `add-group-dirty-mark` specifically because time thresholds needed
them; and the eviction sweep already deletes quiet groups, so anything stored per group is bounded.

## Goals / Non-Goals

**Goals:** bound the daily evaluations of a single group without bounding the rule; leave a rule that
declares no ceiling behaving exactly as it does today; charge only evaluations that actually spent a model
call; make a ceiling-blocked group visible rather than silently indistinguishable from an idle one; and
keep the whole mechanism inside the query the sweep already runs, so the ceiling costs no extra round trip.

**Non-Goals:** cost or pricing (counts only, by design); a rule-level ceiling, which
`SpendPauseGate` already provides; a global default ceiling; carrying the counter across group eviction;
and any notion of borrowing or rolling budget between days.

## Decisions

- **The counter lives on `group_state`, not in a `spend_counter`-style table.** It is per group, read by a
  query that already selects per group, and evicted by machinery that already exists. A separate table
  keyed `(rule, group, day)` would need its own retention sweep for exactly the rows `group_state` eviction
  already removes, and would add a join to the sweep's hot query. *Rejected* on both counts.

- **Two columns, `evaluations_day` + `evaluations`, reset lazily.** No scheduled reset job: the day is
  stored alongside the count, so a count from a previous day is simply not today's count. The sweep reads
  `evaluations_day <> :today OR evaluations < :ceiling`, and the increment sets the day and restarts at 1
  when it has rolled over. *Rejected:* a nightly reset job (a second scheduled thing to fail, and a window
  where yesterday's count still blocks); and a rolling window, which needs per-evaluation timestamps and
  buys nothing a daily budget does not.

- **UTC day**, matching `spend_counter` and everything else the runner dates. Read through the injected
  `Clock`, so the rollover is testable without sleeping — which is the reason that bean exists.

- **The gate is in the SQL, not in the sweep's Java.** The sweep already runs one indexed query per group
  rule; adding a predicate keeps ceiling-blocked groups out of the result set entirely rather than fetching
  them and discarding them in a loop. It also means the per-pass batch size is spent on groups that can
  actually be enqueued — filtering after the `LIMIT` would let blocked groups crowd out eligible ones,
  which is a subtle starvation bug rather than a mere inefficiency.

- **A null ceiling means unlimited, and is expressed by omitting the predicate** rather than by substituting
  a sentinel like `MAX_VALUE`. Every rule today declares no ceiling, so the common path must be the
  unmodified query.

- **An evaluation is charged when the model is called** — not per enqueue, and not per execution attempt.
  A group whose members ADAS returns as empty, or whose assembly ADAS refuses, consumed no evaluation;
  charging it would let a misconfigured rule exhaust a ceiling without ever producing a label, which is the
  opposite of what a budget should do. A poison group that *did* reach the model is charged, because the
  call was spent whether or not the response was usable. *Rejected:* charging per enqueue (simplest, but
  charges for work that never happened) and charging only successful writes (undercounts real spend, so the
  ceiling would not actually cap calls).

- **The increment shares the transaction with the state stamp.** The worker already stamps
  `computed_version`/`computed_at`/`computed_truncated` after a successful write; the counter moves with it,
  in one statement. That keeps "this group was evaluated" and "this group has been charged" from diverging.

- **The gate is sweep-side only; there is no re-check at execution.** An item enqueued in the same pass that
  exhausted the ceiling still runs, so a group can exceed its ceiling by at most the items already in flight
  for it — which, because work items are keyed by `group_version` and the sweep enqueues one per version, is
  at most one. Re-checking in the worker would buy that single call back at the cost of a second read and a
  second place the rule can be enforced inconsistently.

- **Blocked groups are counted and exposed.** A ceiling that works looks exactly like a group that stopped
  changing: no work items, no errors, no log lines. A gauge of how many groups the last pass declined makes
  the difference observable, which is the minimum needed to tell "the ceiling is protecting us" from "the
  rule is broken". Full group metrics remain `add-phase2-ops`'s.

## Risks / Trade-offs

- **A ceiling silently degrades label freshness** → that is the trade the rule author opted into by
  declaring it, but it is invisible in the data: the enrichment row carries `group_version`, so a reader can
  see the label is behind the group, yet nothing says *why*. Mitigated by the blocked gauge on the runner
  side; a per-row "budget exhausted" marker would need a new provenance column and is not proposed.

- **A group evicted and re-created loses its count** → deliberate. Eviction only removes groups quiet for
  longer than their whole readiness window plus the margin, so a re-created group is a new conversation in
  all but identity; carrying a stale count forward would penalise it for a previous day's activity.

- **The counter counts calls, not tokens** → a ceiling of 5 bounds five evaluations of a group whether each
  reads three members or three hundred. Consistent with the "usage units only, no cost" decision,
  and the runner's own token budget is what bounds a single evaluation's size.

- **A group can exceed its ceiling by one** (the in-flight item described above) → accepted, and cheaper to
  document than to close. A ceiling of 1 therefore means "about one", which is worth knowing before setting
  one that low.

- **Charging a poison group that reached the model** means a rule whose responses never validate burns its
  groups' ceilings → correct (the calls were really spent), and `add-group-worker` already prevents the unbounded case by
  stamping a poison group clean so it is not re-swept until it changes.
