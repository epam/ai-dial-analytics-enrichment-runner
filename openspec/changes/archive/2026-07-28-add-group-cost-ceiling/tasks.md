## 1. Schema & rule model

- [x] 1.1 `db/migration/postgres/V5__group_cost_ceiling.sql` — `group_state` += `evaluations_day DATE` and
  `evaluations INT NOT NULL DEFAULT 0`. Additive, no backfill: an existing group starts counting at its next
  evaluation. `add-group-dirty-mark` kept these out of `V3` rather than shipping columns nothing read
- [x] 1.2 `persistence/GroupStateEntity` += `evaluationsDay`, `evaluations`
- [x] 1.3 `sync/RuleRouting.costCeiling(rule)` — the declared ceiling, or null for unlimited. Keep the null
  meaning explicit rather than substituting a sentinel: every rule today declares none, so unlimited is the
  common path

## 2. Sweep gate

- [x] 2.1 `persistence/GroupStateWriter` — add the budget predicate to the dirty-and-ready selects:
  `(evaluations_day <> :today OR evaluations < :ceiling)`. Two query variants, with and without the
  predicate, so a null ceiling runs the unmodified query
- [x] 2.2 The predicate goes **inside** the query, before `LIMIT` — filtering after it would let
  ceiling-blocked groups consume the per-pass batch and starve eligible ones
- [x] 2.3 `GroupStateWriter.countCeilingBlocked` — a companion count, not a value folded into the select.
  Keeping declined groups out of that result set is precisely what stops them consuming the batch, so they
  cannot also be counted from it. Runs only for a rule that declares a ceiling
- [x] 2.4 `sweep/GroupReadinessSweep` — pass the rule's ceiling and the current UTC day (from the injected
  `Clock`) into the query; log a pass that declined groups

## 3. Charging an evaluation

- [x] 3.1 `persistence/GroupStateWriter.stampComputed` — extend to charge the evaluation in the same
  statement: set `evaluations_day = :today` and `evaluations = CASE WHEN evaluations_day = :today THEN
  evaluations + 1 ELSE 1 END`, so the day rollover needs no scheduled reset
- [x] 3.2 Keep a **non-charging** stamp for the paths that consumed no model call. `stampComputed` is called
  from three places in `GroupWorkerService` and only some of them spent a call
- [x] 3.3 `worker/group/GroupWorkerService` — charge on the success path and on a poison group that reached
  the model; do **not** charge an empty group or a `group_fetch` refusal, which consumed no evaluation.
  Required splitting assembly into its own try block: the previous catch-all could not tell an
  `InputMappingException` (assembly, no model call) from an `OutputMappingException` (after the call), and
  both mapped to `group_map`
- [x] 3.4 `observability/RunnerMetrics` — `runner.group.ceiling.blocked` gauge, published per sweep pass. A
  working ceiling otherwise looks exactly like a group that stopped changing

## 4. Verification

- [x] 4.1 Unit tests: `RuleRouting.costCeiling` returns the declared value, and null when `ready_when`
  declares none or is absent
- [x] 4.2 Sweep test (`SettableClock` + Testcontainers PG): a group at its ceiling is not enqueued; one
  below it is; a rule with no ceiling is never blocked; a stale group at its ceiling stays blocked
- [x] 4.3 Sweep test: the day rollover unblocks a group that was at its ceiling — advance the clock past UTC
  midnight and assert it is enqueued and its count restarts
- [x] 4.4 Sweep test: blocked groups do not consume the per-pass batch — seed blocked groups with older
  activity than eligible ones (so they sort first) with a batch size smaller than the total, and assert
  **which** instance ids were enqueued. Asserting the count alone passes either way (both cases enqueue two),
  so the first version of this test did not discriminate; verified by mutating the predicate out and watching
  it fail
- [x] 4.5 Worker test: the success path charges one evaluation; a poison group that reached the model
  charges one; an empty group and a `group_fetch` refusal charge none
- [x] 4.6 Component test: a group evaluated up to its ceiling stops being enqueued while its rule keeps
  sweeping its other groups
- [x] 4.7 `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build` passes
- [x] 4.8 `openspec validate add-group-cost-ceiling --strict` passes
