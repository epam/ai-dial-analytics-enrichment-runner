## 1. Persistence — the migration and the column rename

- [x] 1.1 Add `V8__rekey_and_discard_runner_state.sql`: `rule_cursor` → `pipeline_cursor` (with `rule_id` → `pipeline_name` and its PK), and `rule_id` → `pipeline_name` on `run_history`, `dlq_item`, `spend_counter` and `group_state` (the last two composite PKs).
- [x] 1.2 Rename the indexes and constraints that carry the column: `run_history_rule_id_idx` → `run_history_pipeline_name_idx`, `dlq_item_rule_id_created_at_idx` → `dlq_item_pipeline_name_created_at_idx`, `spend_counter_rule_id_day_idx` → `spend_counter_pipeline_name_day_idx`. Keep `dlq_item_row_identity_uk`, `group_state_dirty_idx` and `group_state_activity_idx` — those names stay accurate.
- [x] 1.3 In the same migration, truncate the five state tables together, after counting them and announcing the counts as `NOTICE`s. All five plus the queue go at once: the cursor advances at chunk enqueue, and `deadLetter` stamps a group computed *before* writing its DLQ row, so any partial discard either double-processes rows or strands dead-lettered groups as computed-but-unlabelled.
- [x] 1.4 In the same migration, `DELETE FROM scheduled_tasks` for every task name this runner registers — `rule-scan` and `pipeline-scan` (both spellings of the scan task), `class1-materialize`, `enrich-chunk`, `enrich-group`, `upsert-rows`, `backfill-run` — and report the count. Work items are deleted rather than asserted absent: their ids are unreachable after the fold, and the rows they stood for are re-scanned from the beginning anyway once the cursor is truncated.
- [x] 1.5 Rename `RuleCursorEntity` → `PipelineCursorEntity` and `RuleCursorRepository` → `PipelineCursorRepository`; re-map `@Table`/`@Column`/`@Id` to `pipeline_cursor` / `pipeline_name`.
- [x] 1.6 Re-map the `pipeline_name` column on `RunHistoryEntity`, `DlqItemEntity`, `GroupStateEntity` and `GroupStateId` (`@IdClass`), and rename the derived-query methods on `RunHistoryRepository`, `DlqItemRepository` and `GroupStateRepository`.
- [x] 1.7 Update `persistence/GroupStateWriter`'s hand-written SQL: change only the constants that name the column (7 references across the 12 constants). Leave the readiness/budget/ordering **fragments** alone — they name only their own columns, and a rename driven off the constant names would corrupt them.
- [x] 1.8 Update `spend/SpendService`: the `ON CONFLICT (rule_id, evaluator_version, day)` upsert and `DAILY_TOTALS`.
- [x] 1.9 Verify `scan/InflightCounter`'s `LIKE ? ESCAPE '!'` needs no column change, and leave `escapeLike` as it is — it already escapes `!`, `%` and `_`.

## 2. ADAS client

- [x] 2.1 Add `PipelineDto` (replacing `RuleDto`): `id` dropped, `name` the key; nested `TriggerDto(kind, cron, group_by, ready_when)`; drop `filterSql`, `source`, `targetEnrichment`, `evaluatorName`, `priority`, `createdAt`, `updatedAt`. Keep `generation` a `long`.
- [x] 2.2 Delete `RulePriority` along with the field it typed.
- [x] 2.3 Add `PipelineListDto(List<PipelineDto> pipelines)` and switch `AdasRegistryClient.fetchRules` → `fetchPipelines`: path `/v1/pipelines`, query `kind=enrich&enabled=true[&updated_since=…]`, envelope parsing, blank body still an empty list. The class keeps its own name.
- [x] 2.4 Re-path `AdasScanClient`, `AdasMaterializeClient`, `AdasGroupFetchClient` and `AdasUpsertClient` to `/v1/pipelines/{name}/…` and rename their `ruleId` parameter to `pipelineName`. Request and response records are unchanged, `ScanResponse.SIGNAL_COLUMN = "__signal"` included.

## 3. Sync, routing, cache and identity

- [x] 3.1 Rename `RuleCache` → `PipelineCache`, `RuleRouting` → `PipelineRouting` (and `RULE_TASK_NAMES` → `PIPELINE_TASK_NAMES`), `RuleSyncService` → `PipelineSyncService`.
- [x] 3.2 Read the trigger from `trigger.kind` / `trigger.cron` / `trigger.group_by` / `trigger.ready_when` in `ScheduleFactory`, `PipelineRouting` and the admission gate.
- [x] 3.3 Rename the db-scheduler task `rule-scan` → `pipeline-scan` (`RuleScanTask` → `PipelineScanTask`, `RuleScanExecutor` → `PipelineScanExecutor`), and `WorkTasks.RULE_SCOPED` → `PIPELINE_SCOPED`. Leave `class1-materialize`, `enrich-chunk`, `enrich-group`, `upsert-rows` and `backfill-run` unchanged.
- [x] 3.4 Change `DeterministicId`'s prefix to `pipelineName#` (`rulePrefix` → `pipelinePrefix`) and its sha1 basis.
- [x] 3.6 Move `backfillChunkInstanceId`'s namespace from `bf#` to `#bf#` (design §8): a backfill chunk must stay outside every pipeline's in-flight prefix, and under a name identity `bf` is a registrable pipeline name where a UUID never was. Pin it with a regression test.
- [x] 3.5 Rename `RulePauseRegistry` → `PipelinePauseRegistry` and `RuleNotFoundException` → `PipelineNotFoundException` (message → "Pipeline not found or not enabled: {name}").

## 4. Lanes, sweeps, worker, backfill, DLQ, spend

- [x] 4.1 Rename through the call graph — `ChunkBuilder`, `ScanPageCommitter`, `GroupMarkBuilder`, `GroupDirtyMarkCommitter`, `InflightCounter`, `Class1MaterializeExecutor`, `GroupReadinessSweep`, `GroupStateEvictor`, `WorkerService`, `EvaluatorInvoker`, `ChunkFailureHandler`, `ChunkPayload`, `GroupWorkerService`, `GroupFailureHandler`, `GroupPayload`, `UpsertService`, `UpsertFailureHandler`, `UpsertPayload`, `DialRateLimiter`, `DlqCircuitBreaker`, `DlqRequeueService`, `DlqItemWriter`, `SpendService`, `SpendPauseGate`, `BackfillService`, `BackfillExecutor` — `ruleId` → `pipelineName` throughout.
- [x] 4.2 Re-word the ~25 operator-facing log messages (`PipelineSyncService` 5, `PipelineScanExecutor` 6, `Class1MaterializeExecutor` 3, worker/upsert/group 9, sweeps 4, breaker, backfill, group admin). Keep `PipelineRouting`'s seven refusal reasons' wording apart from the noun.

## 5. This service's own API

- [x] 5.1 Rename `RuleAdminController` → `PipelineAdminController` and re-path to `/v1/pipelines` with `{name}`: `cache`, `paused`, `{name}/pause`, `{name}/resume`.
- [x] 5.2 Re-path `GroupAdminController` to `/v1/pipelines/{name}/groups[/{key}/requeue]` and `BackfillController`'s trigger to `POST /v1/pipelines/{name}/backfill`. The run-scoped `/v1/backfills/{runId}` verbs are unchanged.
- [x] 5.3 Rename `RuleCacheEntryDto` → `PipelineCacheEntryDto` (**dropping `id`**), `RuleCacheResponse` → `PipelineCacheResponse`, `PausedRulesResponse` → `PausedPipelinesResponse`, `RuleCacheMapper` → `PipelineCacheMapper`. MapStruct will fail the build on a target property with no source — that is the intended check.
- [x] 5.4 Change `DlqController`'s `rule_id` query parameter to `pipeline_name` on both list and requeue, and the `rule_id` JSON property on `DlqItemDto` and `BackfillRunDto` to `pipeline_name`.

## 6. Config, health and observability

- [x] 6.1 Rename `adas.rules.poll-interval-ms` / `adas.rules.initial-delay-ms` → `adas.pipelines.*` in `application.yml` **and** in the `@Scheduled` placeholder string literals, and the env vars to `ADAS_PIPELINES_*`.
- [x] 6.2 Rename `RuleSyncHealthIndicator` → `PipelineSyncHealthIndicator` (bean `ruleSync` → `pipelineSync`) **and** `management.endpoint.health.group.readiness.include` in the same edit. Determine empirically how Spring Boot 4 treats an unknown contributor name there, and record the answer in the design's open question.
- [x] 6.3 Rename `ReadinessState.markRuleSyncSucceeded()` / `isRuleSyncSucceeded()` to `markPipelineSyncSucceeded()` / `isPipelineSyncSucceeded()`.
- [x] 6.4 In `RunnerMetrics`: gauges `runner.rules.{cached,paused,unsupported}` → `runner.pipelines.*`, and the `rule` tag → `pipeline` on `runner.spend.calls.today`, `runner.scan.lag.seconds` and `runner.class1.rows.written`.
- [x] 6.5 Change `WorkerMdc.RULE_ID` to `pipeline_name`, and the span attribute `rule.id` → `pipeline.name` in all 7 places (`PipelineScanExecutor` ×2, `GroupReadinessSweep`, `EvaluatorInvoker`, `GroupWorkerService`, `UpsertService`, `Class1MaterializeExecutor`). Span **names** are unchanged.

- [x] 6.6 **One deliberate scope addition, found by the cross-service consistency check:**
      add `scan.max-scan-limit` (`SCAN_MAX_SCAN_LIMIT`, default `10000`, mirroring ADAS's
      `ENRICHMENT_SCAN_MAX_LIMIT`) and cap `ScanProperties.resolveScanLimit` at it with
      `Math.clamp(limit, 1, maxScanLimit)`, exactly as `Class1Properties.resolveLimit` already caps the
      materialize window. ADAS answers an over-limit scan with a `400` and does **not** validate
      `batch_scan_limit` at registration, so a pipeline declaring one above ADAS's ceiling was accepted by
      the registry and then failed on *every* fire — the scan lane never advancing at all, while the
      Class-1 lane on the same knob was already protected. Also corrects `docs/configuration.md`, which
      stated `batch_scan_limit` "has no upper clamp" in the DLQ-breaker crossover argument.

## 7. Tests

- [x] 7.1 Rename the fixture `support/TestRules` → `support/TestPipelines` and adjust every lane test to the new DTO arity.
- [x] 7.2 Rename and retarget `RuleSyncIntegrationTest` → `PipelineSyncIntegrationTest` (WireMock stub `GET /v1/rules` → `GET /v1/pipelines?kind=enrich&enabled=true`, response body becomes the envelope), plus `RuleRoutingTest`, `RuleCacheTest` and `RuleScanExecutorTest`.
- [x] 7.3 Re-path the WireMock stubs in `ScanIntegrationTest`, `WorkerIntegrationTest`, `GroupWorkerIntegrationTest`, `BackfillLanesIntegrationTest`, `Class1MaterializeIntegrationTest` and `TwoPhaseUpsertIntegrationTest`.
- [x] 7.4 Update the column names in `InflightCounterIntegrationTest`, `GroupDirtyMarkIntegrationTest`, `GroupSweepIntegrationTest`, `SpendServiceIntegrationTest`, `DlqRequeueIntegrationTest` and `BackfillIntegrationTest`.
- [x] 7.5 Update the path fixtures in `JwtAuthIntegrationTest`, `ApiKeyAuthIntegrationTest`, `OpaqueAuthIntegrationTest`, `OpsIntegrationTest` and `OpsSignalsIntegrationTest`.
- [x] 7.6 **Grep for `adas.rules.`** rather than working from a list, and move every `@DynamicPropertySource` registration (~20 classes, including `PublicEndpointsAuthIntegrationTest` and `UpsertEnqueuerIntegrationTest`, which appear in no other row).
- [x] 7.7 New test: the sync feed's envelope is parsed and a bare array is rejected rather than reconciling to zero pipelines.
- [x] 7.8 New test: `adas.pipelines.poll-interval-ms` actually resolves, so a future half-rename fails loudly instead of falling back to the placeholder default.
- [x] 7.9 New test: a pipeline name containing `_` does not broaden `InflightCounter`'s prefix match. Do **not** add a test asserting the runner rejects a `#` — ADAS is the gate.
- [x] 7.10 New test: `V8` applies over a database seeded with UUID-keyed rows, not only over an empty one — asserting the renames, that all five tables and `scheduled_tasks` come back empty, and that work items are cleared rather than refused.
- [x] 7.11 New test: `actuator/health/readiness` reports exactly the three contributors `readinessState`, `db` and `pipelineSync`.

## 8. Specs

- [x] 8.1 Author the `pipeline-sync` ADDED delta and the `rule-sync` REMOVED delta (three requirements each, with `**Reason**` and `**Migration**` on every removal).
- [x] 8.2 Author the 11 vocabulary deltas (`incremental-scan`, `class1-enrichment`, `enrichment-worker`, `group-enrichment`, `resilience-and-dlq`, `spend-tracking`, `backfill`, `runner-ops`, `observability`, `service-runtime`, `api-authentication`), including the RENAMED-then-MODIFIED capabilities and the new observability requirement pinning the metric, tag, MDC and span spellings.
- [x] 8.3 **At archive time:** apply every delta to `openspec/specs/`, applying each RENAMED section before the MODIFIED requirement that carries its new heading.
- [x] 8.4 **At archive time:** `rm -r openspec/specs/rule-sync/` — sync empties the requirements and leaves the directory behind, which would otherwise stand as a second source of truth.
- [x] 8.5 Rewrite `openspec/config.yaml`'s "ADAS integration contract (as-built; the runner is a client)" section against the shared contract, word for word where they overlap. Leave the file's own top-level `rules:` key alone.

## 9. Docs

- [x] 9.1 `docs/configuration.md`: the `adas.pipelines.*` keys, and every per-pipeline fallback description across the DIAL, DLQ breaker, Spend, Scan, Class-1, Group and ADAS integration sections, plus the exposure table.
- [x] 9.2 `docs/e2e-runbook.md`: the ADAS registration bodies (`POST /v1/pipelines` with `"kind": "enrich"` and the nested trigger), the `runner_class1_rows_written_total{pipeline=…}` assertions, the `/v1/dlq?pipeline_name=` parameter, and **replace the re-keying appendix with the discard choreography** — the deploy sequence, what to capture before the window, and what looks wrong afterwards but is not.
- [x] 9.3 `docs/kubernetes-exposure.md`: the `/v1/rules` Ingress prefix → `/v1/pipelines`, the three `curl` examples, the probe prose, and the method-vs-path callout. Say in the exposure table which host owns which verb now that both services speak `/v1/pipelines`. Leave every `rules:` block in the Ingress and NetworkPolicy manifests untouched.
- [x] 9.4 `README.md` and `CLAUDE.md`.

## 10. Verification

- [x] 10.1 `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build`, then `openspec validate fold-rule-into-pipeline --strict`.
- [x] 10.2 `V8` applies from empty — covered by every `@SpringBootTest`, which migrates the shared Testcontainer to the latest version at startup.
- [x] 10.3 `V8` applies over a database **seeded with UUID-keyed state** — `PipelineRenameMigrationIntegrationTest` migrates to `V7`, seeds all five tables and `scheduled_tasks`, then migrates to `V8` and asserts the renames landed, every state table and the queue are empty, and both spellings of the scan task are cleared.
- [x] 10.4 `GET /v1/pipelines/cache` (asserting the entry no longer carries `id`) and `actuator/health/readiness` (asserting exactly `readinessState`, `db`, `pipelineSync`) — covered by `OpsIntegrationTest`.
- [x] 10.5 `bootRun` against a live local runner database, exercising `GET /v1/pipelines/paused` and `GET /v1/dlq?pipeline_name=…` by hand, against a local ADAS built from the sibling change.
- [x] 10.6 Local end to end against a local ADAS built from the sibling plan, via `docs/e2e-runbook.md`: all three lanes plus one bounded backfill.
- [x] 10.7 Rehearse choreography steps 1–7 locally, against a real update and redeploy of both services.

      The post-window assertions this task originally carried — `pipeline_cursor` for
      `usage-client-identity-live` holding its pre-window value, `group_state` counts and today's
      `spend_counter` unchanged — belonged to the **re-keying** plan that task 9.2 replaced. Under the
      discard choreography `V8` truncates all five state tables on purpose, so those quantities are
      *expected* to reset; `docs/e2e-runbook.md`'s "What is expected to look wrong, and is not" section
      is what governs. What the rehearsal checked instead: the five state tables come back empty, the
      sync feed recreates one recurring instance per enabled pipeline under its name, each pipeline's
      `enabled` value is restored to the value recorded at step 0, and the ADAS-side Class-1 marks stay
      at their pre-window position because nothing bumped `generation` during the window.

      The rehearsal also produced the finding now recorded in the runbook: a declaration `PATCH` bumps
      `generation` exactly as an `enabled` toggle does, and a `cadence`-only patch moved a Class-1
      pipeline's mark back by weeks. That is why step 1's warning now covers declaration edits
      and not only disabling.
