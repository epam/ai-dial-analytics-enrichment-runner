## Why

A group pipeline that resumes against a backlog of hundreds of dirty groups can compute dozens of labels
and write none of them. The queue shows it plainly — the compute lane busy, the write lane never picked.
An illustrative snapshot (round, invented numbers; 500 is one `GROUP_SWEEP_BATCH`, 5 the worker threads):

```
task_name          | queued | picked
enrich-group       |    500 |      5
upsert-rows        |    100 |      0
```

Dozens of `Computed group … queued for writing` lines, not one `Upserted N enrichment row(s)`. The
pipeline pays DIAL for every label and writes none of them, until an operator pauses it.

**The queue is ordered against the write.** db-scheduler orders due executions
`priority desc, execution_time asc` (`JdbcTaskRepository:742`), and the runner gives the write-back the
*same* priority as the compute that produces it — `UpsertEnqueuer:48` schedules `upsert-rows` with
`EnrichChunkTask.PRIORITY_LIVE`, the value `GroupReadinessSweep:193` uses for `enrich-group`. On equal
priority the tiebreak is age, and a write is **by construction younger than the compute that created it**
and younger than every compute queued alongside it. So the write sorts behind the entire compute backlog,
forever: a few hundred items at five threads is the better part of an hour before the first write gets a
thread, and the sweep replenishes the backlog every thirty seconds. Five threads all compute, nothing writes, and the
group is stamped clean only by the write — so nothing in the group's own state moves either.

The starvation is not merely slow. The computed labels live in `scheduled_tasks` as `upsert-rows` rows
carrying the only copy of a paid-for result, and this service **drops queued work across a restart**: a
work item that becomes due before the first pipeline sync has populated `PipelineCache` reads
`pipelineCache.get(...) == null` and is dropped (`GroupWorkerService:115`, `WorkerService:90`) or archived
(`UpsertService:113`). `ADAS_PIPELINES_INITIAL_DELAY_MS` is 10s and the scheduler starts with the Spring
context, so every restart lands squarely in that window. On the row lane the drop is unrecoverable: the scan
cursor advanced in the same transaction that enqueued the chunk (`ScanPageCommitter`), so those rows are
never revisited.

A third defect turns an upstream capacity problem into data loss. A ClickHouse node pinned at its memory
limit makes ADAS refuse group fetches; every 4xx is classified permanent (`RetryableStatus` treats every 4xx as
"never" — only 5xx and 429 retry), so a clean, deterministic refusal becomes a **poison** dead-letter, and `GroupWorkerService`
writes poison group dead-letters **with no payload** ("a poison group gains nothing from replay"). A
group that merely lost a race to a socket timeout is transient, exhausts its attempts, and is archived by
`GroupFailureHandler` *with* its payload — requeueable. The better-behaved failure produces the worse
outcome: a single capacity episode can leave a substantial share of its dead-letters unrecoverable.

## What Changes

- **Write-back gets its own priority lane, above every compute lane.** Finishing work the runner has
  already paid for outranks starting work it has not. The three lanes move to one `WorkPriority` holder —
  `WRITE_BACK` > `LIVE` > `BACKFILL` — because the constants currently live on `EnrichChunkTask` while
  three tasks and five call sites read them, and the one that mattered was wrong precisely because it was
  borrowed.
- **A requeued item keeps its lane.** `DlqRequeueService` schedules with no priority at all, so a
  requeued write-back lands at `0` — below even backfill compute. A requeue is a write an operator has
  asked for by hand; it is not the lowest-value work in the queue.
- **The readiness sweep sizes its batch against the pool, not against a constant.** A per-pipeline depth
  ceiling, defaulting to ten items per worker thread, bounds how much group work may be pending at once;
  the sweep defers the remainder instead of enqueueing it. `GROUP_SWEEP_BATCH` stays as the per-pass
  bound. The point is not throughput — the pool drains at the same rate either way — it is **where the
  backlog waits**. A group waiting in `group_state` is durable, re-derivable and free; the same group
  waiting in `scheduled_tasks` is a row this service is known to drop on restart, and at five threads a
  500-deep queue is thirteen minutes of exposure even when nothing is wrong.
- **A cold cache holds work instead of dropping it.** `PipelineCache` learns whether it has *ever* been
  populated, which is a different question from whether a pipeline is in it. Never populated means the
  runner does not yet know anything; a work item due in that state is **held** — rescheduled without
  consuming an attempt and without archiving — through a new `WorkHold` / `HeldWorkException` pair that
  defines "held" once for all three lanes. Populated but missing the pipeline keeps today's behaviour,
  which is correct: that is a real answer, and it means disabled or deleted.
- **Every group dead-letter carries its payload.** A group payload is four scalars. Storing them costs
  nothing and removes the asymmetry entirely: no group outcome is ever unrecoverable, whether the runner
  classified its cause correctly or not. `408` and `425` join the retryable set.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `group-enrichment`: the sweep's depth ceiling and the deferral it produces.
- `enrichment-worker`: write-back outranks compute in the queue; a cold pipeline cache holds work rather
  than dropping or archiving it.
- `resilience-and-dlq`: every group dead-letter is requeueable; a requeued item is scheduled in its own
  lane; the retryable-status set gains 408 and 425.
- `observability`: the deferred-group counter.

## Impact

- **Code**: new `worker/WorkPriority`, `worker/WorkHold`, `exception/HeldWorkException`,
  `exception/PipelineCacheColdException`; `UpsertEnqueuer`, `GroupReadinessSweep`, `ScanPageCommitter`,
  `BackfillPageCommitter`, `GroupAdminController`, `DlqRequeueService`, `PipelineCache`, `WorkerService`,
  `GroupWorkerService`, `UpsertService`, the three failure handlers (the hold arm), `GroupProperties`,
  `RunnerMetrics`.
- **Relation to the contract-skew guard.** `refuse-skewed-adas-contract` needs the same hold concept for an
  ADAS contract skew. The two are the same mechanism and must not exist twice: `AdasContractException`
  extends `HeldWorkException` and its call sites go through `WorkHold.holdIfHeld`; the failure handlers
  carry one hold arm each.
- **Configuration**: one new property, `GROUP_MAX_INFLIGHT`, defaulting to `0` — *derive from
  `DB_SCHEDULER_THREADS`*. Derived rather than a constant because the coupling at fault here is
  exactly the one a constant hides: the queue depth that is safe is a function of how many threads drain
  it, and `DB_SCHEDULER_THREADS` is the knob an operator reaches for.
- **Operational**: no migration; `priority` already exists on `scheduled_tasks` (the scheduler is built
  with `enablePriority()`). Items already queued at the old priority keep it and drain in age order; the
  new lane applies to everything enqueued after the upgrade, so recovery needs no operator action.
- **Not in scope**: limiting how many group fetches run against ADAS *concurrently*. Five concurrent
  fetches on the order of a GiB each can exceed a small ClickHouse node's memory, but concurrency is
  `DB_SCHEDULER_THREADS`, not queue depth, and nothing in this change moves it. The lever today is that
  setting; a per-lane concurrency limit is a real feature and wants its own change. What this change does
  do is make the resulting refusal survivable rather than terminal.
