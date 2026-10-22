## 1. The upsert work item

- [x] 1.1 `worker/upsert/UpsertPayload` — a mutable Lombok POJO (db-scheduler's serializer is Jackson 2, as
  for `ChunkPayload`): `{ruleId, evaluatorVersion, rows}` where rows are the computed write rows. **Never**
  source rows — a privacy bound as much as a size one
- [x] 1.2 `worker/upsert/UpsertRowsTask` — `Tasks.oneTime("upsert-rows", UpsertPayload.class)` with
  `UpsertFailureHandler`; register in `configuration/DbSchedulerConfiguration`
- [x] 1.3 `worker/upsert/UpsertService` — re-check the rule-lifecycle and stale-evaluator guards, then
  `AdasUpsertClient.upsert`; a `RestClientException` → `TransientChunkException(UPSERT)` so only the write
  retries; a non-retryable rejection dead-letters at `upsert` with the payload retained
- [x] 1.4 `worker/upsert/UpsertFailureHandler` — backoff retry then archive, reusing `RetryBackoff` and
  `worker.max-attempts`; archive with `taskName = upsert-rows` so a requeue re-enqueues a write
- [x] 1.5 `scan/DeterministicId.upsertInstanceId(computeInstanceId)` — derived from the compute item's id so a
  repeated compute enqueues one write; keep the `ruleId#` prefix for backpressure counting

## 2. Splitting the row worker

- [x] 2.1 `worker/WorkerService` — stop writing. Build the write rows as today, then enqueue one upsert item
- [x] 2.2 `worker/upsert/UpsertEnqueuer` (or a `@Transactional` method) — the enqueue and the compute's
  per-row DLQ writes commit in **one** transaction, so a crash cannot drop a computed result. Reuse the
  tx-aware scheduler, as `ScanPageCommitter` does
- [x] 2.3 Keep provenance stamping in the compute phase — `enriched_at` records derivation time, not write
  time, and must not differ between write attempts

## 3. Splitting the group worker

- [x] 3.1 `worker/group/GroupWorkerService` — enqueue an upsert item instead of writing; carry the group's
  single row
- [x] 3.2 Move the group stamp to the write phase: `UpsertService` stamps `computed_version`/`computed_at`/
  `computed_truncated` after a successful write, so "clean" means "written". The empty-group stamp stays in
  the evaluating item, since there is no write to defer to
- [x] 3.3 Carry what the stamp needs on the payload (group key, group version, truncated) without carrying
  members; a row-grain payload leaves those absent
- [x] 3.4 Keep the charge in the same statement as the stamp, so an evaluation still cannot be recorded
  without being charged (`add-group-cost-ceiling`'s invariant)
- [x] 3.5 `UpsertService` dead-letters through `DlqItemRepository`, **not** `DlqItemWriter` — the latter's
  per-row upsert carries neither `payload` nor `task_name` and keys on a grain key a multi-row write does not
  have, so a requeued write would have been deserialized as a compute chunk. Caught by task 5.7's test

## 4. Requeue and wiring

- [x] 4.1 `dlq/DlqRequeueService` — dispatch a third `task_name` to `upsert-rows`
- [x] 4.2 Confirm `dlq_item.task_name` needs no migration (`add-group-worker` added it, defaulting to `enrich-chunk`)

## 5. Verification

- [x] 5.1 Unit test: `DeterministicId.upsertInstanceId` is stable, derived from the compute id, and keeps the
  rule prefix
- [x] 5.2 Unit tests: `UpsertService` — a transient ADAS failure raises a retryable failure; a non-retryable
  rejection dead-letters at `upsert`; a stale rule drops the write; a group payload stamps its group
- [x] 5.3 Unit test: `WorkerService` no longer calls the upsert client, and enqueues an item carrying the
  computed rows
- [x] 5.4 **The headline test**: inject an upsert failure, let the write retry, and assert the DIAL call count
  is unchanged. This is the whole point of the milestone and must fail if the phases are re-merged
- [x] 5.5 Transaction test: the compute's enqueue and its DLQ writes commit together; a forced failure rolls
  back both
- [x] 5.6 Component test: a group's write failing and retrying leaves the group **dirty** until the write
  lands, then clean
- [x] 5.7 Component test: requeuing an archived upsert item re-enqueues a write and makes no model call
- [x] 5.8 `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build` passes
- [x] 5.9 `openspec validate add-two-phase-upsert --strict` passes
