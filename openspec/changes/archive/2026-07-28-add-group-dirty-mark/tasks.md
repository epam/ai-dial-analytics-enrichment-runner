## 1. Rule model & admission

- [x] 1.1 `adas/dto/ReadyWhenDto` (`signal`, `idle`, `maxStaleness`, `costCeiling`) with
  `@JsonProperty` on the snake_case fields
- [x] 1.2 `adas/dto/RuleDto` += `groupBy`, `readyWhen`; update the positional construction in
  `support/TestRules` and add `groupBy`/`readyWhen` builder knobs plus a `group(...)` factory
- [x] 1.3 `sync/RuleRouting` — narrow `unsupportedReason`: a group rule is executable unless it has no
  `group_by`, its `ready_when` is absent, declares `signal`, or declares neither `idle` nor
  `max_staleness`. Add `idle(rule)` / `maxStaleness(rule)` resolving the declared duration (Spring short
  form **and** ISO-8601, via `DurationStyle.detectAndParse`) against `group.default-idle`

## 2. Persistence

- [x] 2.1 `db/migration/postgres/V3__group_state.sql` — `group_state(rule_id, group_key,
  group_version BIGINT NOT NULL, last_activity_at TIMESTAMPTZ NOT NULL, computed_version BIGINT,
  computed_at TIMESTAMPTZ, computed_truncated BOOLEAN NOT NULL DEFAULT FALSE,
  PK(rule_id, group_key))` + index on `(rule_id, group_version, computed_version)` for the dirty
  predicate and on `(rule_id, last_activity_at)` for eviction. **No `add-group-cost-ceiling` counter columns** — `add-group-cost-ceiling` adds its
  own migration rather than shipping schema nothing reads
- [x] 2.2 `persistence/GroupStateEntity` + `GroupStateRepository`
- [x] 2.3 `GroupStateWriter` — a `GREATEST`-based batch upsert (JdbcTemplate, mirroring
  `DlqItemWriter`'s idempotent-upsert style), the dirty-and-ready select bounded by a limit, and the
  eviction delete. Named `…Writer`, not on the repository: a `JpaRepository` interface cannot carry
  JdbcTemplate methods, and `DlqItemRepository` + `DlqItemWriter` is the existing precedent

## 3. Dirty-mark tick

- [x] 3.1 `scan/group/GroupMarkBuilder` — page → per-group marks: group key from `group_by`, version
  from the source's version column parsed defensively (ISO-8601 string **or** epoch-millis number) to
  epoch millis, `max` per group, `Sampler.keep(groupKey, sampling)`, rows with no group key skipped and
  rows with an uninterpretable version dead-lettered at `input_map`
- [x] 3.2 `scan/group/GroupMarkBatch` record (marks, dlqItems, rowCount), mirroring `ChunkBatch`
- [x] 3.3 `scan/group/GroupDirtyMarkCommitter` — `@Transactional` upsert + DLQ items + `rule_cursor`
  advance, mirroring `ScanPageCommitter`
- [x] 3.4 `scan/RuleScanExecutor` — branch page disposition on `RuleRouting.isGroup(rule)`; leave the
  drain loop, gates, budget and cursor-stall guard untouched

## 4. Readiness sweep & eviction

- [x] 4.1 `configuration/ClockConfiguration` — `@Bean Clock clock()` returning `Clock.systemUTC()`
- [x] 4.2 `configuration/GroupProperties` (`group.sweep-batch` 500, `group.eviction-margin` 7d,
  `group.default-idle` 10m, `group.version-column` `_ingested_at`); wire `application.yml` +
  `docs/configuration.md`. `group.sweep-interval` and `group.eviction-cron` are yaml-only —
  `@Scheduled` resolves them as placeholders, so binding them would be dead config (as with
  `dlq.sweep-cron`)
- [x] 4.2a `group.version-column` names the source's version column in a projected row. ADAS's sync
  feed does not expose that name, so it is configured; see `design.md` for the rejected alternatives
  and the ADAS follow-up that makes this key redundant
- [x] 4.3 `worker/group/GroupChunkTask` — declare the `enrich-group` one-time task with a
  **placeholder handler** (`add-group-worker` implements it); register in `configuration/DbSchedulerConfiguration`
- [x] 4.4 `scan/DeterministicId.groupInstanceId(ruleId, evaluatorVersion, groupKey, groupVersion)` —
  keep the `ruleId#` prefix so the backpressure count still works
- [x] 4.5 `sweep/GroupReadinessSweep` — `@Scheduled` pass over cached group rules: dirty-and-ready
  select per rule bounded by `group.sweep-batch`, then `scheduleIfNotExists` at `PRIORITY_LIVE`;
  honours the rule-lifecycle, pause and spend gates; reads time through the `Clock`
- [x] 4.6 `sweep/GroupStateEvictor` — `@Scheduled` delete of non-dirty rows quiet longer than
  `max(idle, maxStaleness) + group.eviction-margin`

## 5. Verification

- [x] 5.1 Unit tests: `GroupMarkBuilder` — `group_version = max` as epoch millis, ISO-string and
  epoch-number versions both parsed, unparseable version → `input_map` DLQ item, missing group key
  skipped, group-key sampling keeps/drops a whole group
- [x] 5.2 Unit tests: `RuleRouting` group admission cases (no `group_by`, absent `ready_when`, `signal`
  declared, neither `idle` nor `max_staleness`, supported rule admitted) and the duration resolution in
  both Spring short form and ISO-8601
- [x] 5.3 Unit test: `DeterministicId.groupInstanceId` is stable, version-sensitive and keeps the rule
  prefix
- [x] 5.4 Test support: `SettableClock` in `support/`, and a `TestRules` group factory
- [x] 5.5 Transaction test (Testcontainers PG): dirty-mark upsert + cursor advance commit together;
  a forced failure rolls back both (reuse `ScanIntegrationTest`'s invalid-`stage` CHECK-violation
  trick); a repeated tick creates no duplicate state; an older window does not move a group backwards
- [x] 5.6 Sweep test (`SettableClock` + Testcontainers PG): an idle group is enqueued exactly once per
  `group_version`; a still-active group is not; a stale computed group re-fires on `max_staleness`; a
  clean group never fires; a pass stops at `group.sweep-batch`
- [x] 5.7 Eviction test: a long-quiet clean group is deleted, a dirty one is retained
- [x] 5.8 Component test (WireMock ADAS + Testcontainers PG): scan(dirty-mark) → `group_state` → sweep
  → one `enrich-group` work item per dirty-and-ready group, with **zero** `enrich-chunk` items; and a
  group rule declaring `signal` is refused at sync with no task instance
- [x] 5.9 `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build` passes
- [x] 5.10 `openspec validate add-group-dirty-mark --strict` passes
