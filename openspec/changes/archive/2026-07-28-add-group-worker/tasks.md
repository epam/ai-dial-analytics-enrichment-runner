## 1. Group assembly client

- [x] 1.1 `adas/dto/GroupFetchRequest` (`group_key` — note snake_case on the wire — and `limit`) and
  `GroupFetchResponse` (`rows`, `truncated`)
- [x] 1.2 `adas/AdasGroupFetchClient` — `POST /v1/rules/{id}/groups:fetch` (literal colon in the path
  segment), mirroring `AdasScanClient`; blank body → `IllegalStateException`
- [x] 1.3 `configuration/GroupProperties` += `fetchLimit` (200; ADAS rejects above 2000 with a **400**, so
  clamp before asking) and `inputTokenBudget`; wire `application.yml` + `docs/configuration.md`

## 2. Shared model-call pipeline (extraction)

- [x] 2.1 `worker/ProvenanceColumns` — extract the private constants from `WorkerService` and add
  `GROUP_VERSION` / `TRUNCATED`
- [x] 2.2 `worker/EvaluatorInvoker` — move `enrichRow`'s pipeline out of `WorkerService`:
  render → `DialRequestBuilder` → `callWithRepair`/`invoke` → `ResponseValidator` → parse →
  `OutputMapper`, together with rate-limiter acquisition, spend recording, DIAL span attributes and the
  429/5xx classification. Pure move, no behaviour change
- [x] 2.3 `worker/WorkerService` — delegate to `EvaluatorInvoker`; keep the row-specific chunking,
  per-row DLQ isolation and parallelism where they are
- [x] 2.4 `worker/TemplateRenderer` — render a collection as JSON **and escape it like a string**. The
  pre-existing non-string branch spliced it in raw, which corrupts the request body: a member list belongs
  inside the template's content string, where unescaped quotes are invalid JSON. Numbers/booleans stay raw
  literals (value position); scalars unchanged

## 3. Group worker

- [x] 3.1 `worker/group/GroupInputAssembler` — members → one object per member via the existing
  `InputMapper`, exposed to the template as a single collection variable; then the token budget: drop
  **oldest** members first, truncate per member only if dropping still does not fit; report whether it
  trimmed
- [x] 3.2 `worker/group/GroupWorkerService` — fetch → assemble → invoke → build the single row keyed by
  `group_by` with all five provenance columns → `AdasUpsertClient.upsert`; then stamp
  `computed_version`/`computed_at`/`computed_truncated`. `truncated` is the OR of ADAS's flag and the
  runner's own trimming
- [x] 3.3 Empty group → successful no-op: no write, no dead-letter, still stamped clean (so a group the
  membership filter excludes stops re-firing)
- [x] 3.4 Stale-evaluator and rule-lifecycle guards, mirroring `WorkerService`
- [x] 3.5 `persistence/GroupStateWriter` += the computed stamp (`computed_version`, `computed_at`,
  `computed_truncated`)
- [x] 3.6 `worker/group/GroupChunkTask` — replace the throwing placeholder with the real handler and
  attach `GroupFailureHandler`

## 4. Failure taxonomy

- [x] 4.1 `dlq/DlqStage` += `GROUP_FETCH`, `GROUP_MAP`
- [x] 4.2 `db/migration/postgres/V4__group_dlq_stages.sql` — relax `dlq_item.stage`'s CHECK to admit the
  two new stages. Keep the constraint (it is what makes the rollback tests able to force a failure)
- [x] 4.2a Same migration: add `dlq_item.task_name`. `DlqRequeueService.reenqueue` hardwires
  `ChunkPayload` + `EnrichChunkTask`, so requeuing a group item would deserialize a `GroupPayload` as a
  `ChunkPayload` and enqueue garbage. The column records which task the payload belongs to; existing rows
  default to `enrich-chunk`. *(Not in the original task list — required by the "an exhausted group item is
  requeueable" scenario, which cannot pass otherwise.)*
- [x] 4.2b `DlqRequeueService` — dispatch on `task_name`: deserialize and re-enqueue a `GroupPayload` onto
  `enrich-group`, a `ChunkPayload` onto `enrich-chunk`; keep the `ruleId#requeue#` instance-id prefix so
  requeued work still counts toward the rule's backpressure
- [x] 4.3 `worker/group/GroupFailureHandler` — backoff retry then archive, mirroring
  `ChunkFailureHandler`; the archived payload is four scalars, so a group item is always requeueable
- [x] 4.4 Retryable (`groups:fetch` 5xx/timeout, DIAL 429/5xx, rate-limiter exhaustion) → fail the item,
  state untouched; deterministic assembly/mapping failure → DLQ `group_map`; non-retryable
  `groups:fetch` → DLQ `group_fetch`
- [x] 4.5 `DlqCircuitBreaker.recordGroupFailure` — group dead-letters feed the **count** lever only. A group
  is one item, so `record(1, 1)` reads as a 100% failure rate and would pause the rule on the first poison
  group; a per-rule running count trips at the threshold instead and resets on trip
- [x] 4.6 A poison group is dead-lettered **and stamped clean**, and its execution completes rather than
  retrying. Leaving it dirty would have the sweep re-enqueue the same doomed evaluation forever — the DLQ
  row dedups on its unique key, so the loop would be invisible while still spending a fetch and a model
  call per pass. A new member raises `group_version` and makes it dirty again, which is when a re-attempt
  could actually succeed. Carried by `worker/group/PoisonGroupException`

## 5. Verification

- [x] 5.1 Unit tests: `GroupInputAssembler` — one object per member with fields correlated; oldest-first
  dropping; per-member truncation only after dropping; the trimmed flag
- [x] 5.2 Unit test: `TemplateRenderer` renders a list of objects as a JSON array and leaves scalars alone
- [x] 5.3 Unit tests: `GroupWorkerService` — the row carries `group_by` + outputs + all five provenance
  columns; `truncated` is the OR of both sources; empty group writes nothing but stamps; stale item makes
  no model call
- [x] 5.4 `WorkerServiceTest` drives a **real** `EvaluatorInvoker` over the same leaf mocks, so every
  pre-existing assertion still exercises the extracted pipeline. Mocking the invoker instead would have let
  those tests pass without the moved code running at all
- [x] 5.5 Component test (WireMock ADAS + DIAL, Testcontainers PG): scan(dirty-mark) → sweep →
  `groups:fetch` → DIAL → `rows`, asserting **one** upserted row keyed by `chat_id` with the provenance
  set, and `group_state.computed_version` stamped
- [x] 5.6 Component test: each failure branch — `groups:fetch` 5xx retries with state untouched; a
  mapping failure writes `dlq_item` at `group_map`; a non-retryable fetch writes `group_fetch`; a
  truncated group is still labelled and marked
- [x] 5.7 Component test: a target without `group_version`/`truncated` still enriches (ADAS drops them) —
  stub the write endpoint and assert the runner sends them regardless
- [x] 5.8 `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build` passes
- [x] 5.9 `openspec validate add-group-worker --strict` passes
