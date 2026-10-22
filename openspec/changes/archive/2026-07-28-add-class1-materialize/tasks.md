## 1. Rule model & routing

- [x] 1.1 `adas/dto/EvaluatorType` += `SQL`; drop the "supports only LLM" comment
- [x] 1.1a `adas/dto/TriggerKind` += `GROUP`. Defensive: ADAS can already register group rules, and an
  unmodelled enum value fails the whole `GET /v1/rules` parse, stopping sync for **every** rule. The
  admission gate rejects group rules with a clear reason until `add-group-dirty-mark` implements them.
- [x] 1.2 `adas/dto/EvaluatorVarDto` += `sql` component (`{name, type, jsonata, sql}`) and update the
  positional construction in `support/TestRules`
- [x] 1.3 `sync/RuleRouting` — static `isClass1`, `isGroup`, `evaluatorVersion`, `unsupportedReason`
- [x] 1.4 Delete the four duplicated evaluator-version resolutions, delegating to
  `RuleRouting.evaluatorVersion`: `scan/RuleScanExecutor`, `worker/WorkerService`,
  `backfill/BackfillExecutor`, `web/mapper/RuleCacheMapper`

## 2. Materialize client & config

- [x] 2.1 `adas/dto/MaterializeRequest` `{cursor, limit, from, to}` — `from`/`to` modelled now, driven
  by `add-bounded-window-backfill` later
- [x] 2.2 `adas/dto/MaterializeResponse` `{rows_written, next_cursor, has_more, lag_seconds}` with
  `@JsonProperty` on every multi-word field
- [x] 2.3 `adas/AdasMaterializeClient` — `POST /v1/rules/{id}/materialize`, mirroring
  `AdasScanClient` (blank body → `IllegalStateException`)
- [x] 2.4 `class1/Class1Properties` (`class1.default-limit` 10000, `class1.default-cadence` 5m) with a
  `resolveLimit(Integer)` helper clamping to ≥1 and below the ADAS ceiling; wire `application.yml`
  + `docs/configuration.md`

## 3. Materialize tick

- [x] 3.1 `class1/Class1MaterializeTask` — `Tasks.recurringWithPersistentSchedule("class1-materialize",
  PlainScheduleAndData.class)`; register in `configuration/DbSchedulerConfiguration`
- [x] 3.2 `class1/Class1MaterializeExecutor` — cache + pause gates (no spend gate, no backpressure),
  drain loop bounded by `scan.max-pages-per-fire`, cursor-stall guard, MDC `rule_id`
- [x] 3.3 Persist `next_cursor` + `last_scan_at` + `lag_seconds` on 2xx only; never overwrite the
  stored cursor with null on an empty window
- [x] 3.4 Account `rows_written` on a `runner.class1.rows.written` counter and log it per window.
  **Not** `run_history`: the row lane creates no `live` run row, and a perpetual one would make
  `BackfillService.start`'s `findByRuleIdAndState(ruleId, "running")` conflict check 409 every
  backfill. `run_history` accounting for Class-1 lands with `add-bounded-window-backfill`, which gives a Class-1 run a real row.

## 4. Sync reconciliation

- [x] 4.1 `sync/RuleSyncService` — select the task name per rule via `RuleRouting`; fetch scheduled
  instances for **both** task names; create/reschedule under the selected name
- [x] 4.2 Cancel any instance whose rule is absent from that task name's desired set, so a
  type-changed rule migrates lanes and leaves no stale instance
- [x] 4.3 Admission gate — skip, log and count a rule with an `unsupportedReason`; do not cache it and
  do not schedule it
- [x] 4.4 `spend/SpendPauseGate` — exclude Class-1 rules from the gate
- [x] 4.5 `observability/RunnerMetrics` — `runner.rules.unsupported` counter

## 5. Verification

- [x] 5.1 Unit tests: `RuleRouting` (routing by `evaluator.type`, `unsupportedReason` cases,
  evaluator-version resolution parity with the deleted copies); `Class1Properties.resolveLimit`
- [x] 5.2 Unit test: `Class1MaterializeExecutor` drain-budget accounting from `rows_written`/
  `has_more`, and the cursor-stall halt
- [x] 5.3 Extend `support/TestRules.Builder` with `evaluatorType` + SQL output vars so a Class-1 rule
  can be built without positional-constructor churn in every test
- [x] 5.4 Integration test (WireMock ADAS + Testcontainers PG): a Class-1 tick advances the cursor on
  2xx; a 5xx leaves it unmoved; an empty window does not null it; a multi-window drain stops at
  `has_more=false`
- [x] 5.5 Integration test: a Class-1 rule makes **zero** DIAL calls, enqueues **zero**
  `scheduled_tasks` rows, and writes **zero** `spend_counter` and `dlq_item` rows
- [x] 5.6 Integration test: sync routes a `sql` rule to `class1-materialize` and an `llm` rule to
  `rule-scan`; a type flip across a generation bump migrates the instance and leaves the cursor
  untouched; an unexecutable rule is neither cached nor scheduled
- [x] 5.7 `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build` passes
- [x] 5.8 `openspec validate add-class1-materialize --strict` passes
