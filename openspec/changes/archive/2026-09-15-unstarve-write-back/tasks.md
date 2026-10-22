## 1. One priority lane per kind of work

- [x] 1.1 Add `worker/WorkPriority` holding `WRITE_BACK = 20`, `LIVE = 10`, `BACKFILL = 1`, with javadoc
      stating the rule the numbers encode — *finish before you start* — and why write-back cannot starve
      what sits below it (pending writes are bounded by completed computes; write-back generates no
      write-back). Remove `PRIORITY_LIVE` / `PRIORITY_BACKFILL` from `EnrichChunkTask`: three tasks and
      five call sites read them, and the one that was wrong was wrong because it was borrowed from the
      task next to it.
- [x] 1.2 Move every enqueue site onto it — `ScanPageCommitter`, `BackfillPageCommitter`,
      `GroupReadinessSweep`, `GroupAdminController` — and give `UpsertEnqueuer` `WorkPriority.WRITE_BACK`.
      The backfill's write-back shares the live one's lane deliberately; a comment says so, because the
      obvious "symmetry" fix is to split it and that reproduces the defect one level down.
- [x] 1.3 `DlqRequeueService.reenqueue`: schedule through `instanceBuilder(...).priority(...)` rather than
      `instance(...)`, at `WRITE_BACK` for an upsert item and `LIVE` for a compute item. An unset priority
      is `0` — below backfill — so a requeue an operator asked for by hand currently lands under every
      automatically enqueued item.

## 2. A depth ceiling the sweep respects

- [x] 2.1 `GroupProperties`: add `maxInflight` with `resolveInflightCeiling(int threads)` returning
      `threads * 10` when the configured value is non-positive, and the configured value otherwise. Derived
      by default because the defect was a constant chosen independently of the pool that drains it; document the factor in the javadoc, not in `application.yml`.
- [x] 2.2 `GroupReadinessSweep.enqueueReady`: compute headroom from the in-flight id set it already
      fetches (`ceiling − inFlight.size()`), stop enqueueing at it, and count the rest as **deferred**.
      Deferred is its own count, not folded into `suppressed`: suppressed is the number an operator drives
      to zero, deferred is the number that is normal during a drain.
- [x] 2.3 Extend the pass's log line and `SweepOutcome` with the deferred count, so a pipeline held at its
      ceiling does not read identically to one with nothing ready.
- [x] 2.4 Leave `SCAN_MAX_INFLIGHT` alone. It is the coarse cross-lane runaway stop and it does not
      fire in a write-back starvation; a comment in the sweep says why the two coexist rather than one replacing the
      other.

## 3. A cold cache is not an absent pipeline

- [x] 3.1 Add `exception/HeldWorkException` as the abstract base for every cause that means *hold this
      item*, with an `isHeld` walk over the cause chain (bounded depth, self-cycle guard) because a lane may
      wrap the original refusal on its way out. A base type for one case, deliberately: the distinction is
      *waiting out* versus *retrying*, and the part that is easy to get wrong — resetting
      `consecutive_failures` rather than letting the ladder run — must not be re-derived per condition.
- [x] 3.2 Add `exception/PipelineCacheColdException extends HeldWorkException`, thrown by
      `PipelineCache.requirePopulated(what)`.
- [x] 3.3 `PipelineCache`: an `AtomicBoolean populated` set by `replaceAll`, **including a reconcile to
      empty** — an empty registry is a real answer, and withholding the flag for it would hold every lane
      forever against a legitimately empty feed.
- [x] 3.4 Add `worker/WorkHold.holdIfHeld`, which reschedules via `ExecutionComplete.success(...)` so
      db-scheduler writes `consecutive_failures = 0` (verified against 15.6.0), and give each of the three
      failure handlers one arm ahead of its ladder. The ADAS contract-skew guard
      (`refuse-skewed-adas-contract`) needs the same mechanism: `AdasContractException extends
      HeldWorkException` and its call sites go through `WorkHold.holdIfHeld`, so one hold exists, not two.
- [x] 3.5 `WorkerService.doProcess`, `GroupWorkerService.doProcess` and `UpsertService.doWrite`: call
      `requirePopulated` **before** the null-pipeline branch, so the three existing responses (drop,
      drop, archive) apply only to a cache that has an answer. Comment each with what the old behaviour
      cost on that lane — irreversibly on the row lane, where the scan cursor advanced with the enqueue.

## 4. A misclassified failure must not destroy the work

- [x] 4.1 `GroupWorkerService.deadLetter`: archive with the group payload. Replace the "a poison group
      gains nothing from replay" comment with what is actually true — replay gains nothing *when the
      ruling is right*, and four scalars are what makes the ruling a latency question instead of a loss
      question. Inject `JsonMapper`, as `GroupFailureHandler` already does.
- [x] 4.2 `RetryableStatus`: add `408` and `425`. Record in the javadoc that a capacity refusal arriving
      as a 4xx is knowingly misclassified and that the payload rule above is what makes that survivable —
      branching on ADAS's `error` code is a two-repo contract change, not a line added here.
- [x] 4.3 `DlqRequeueService` / `DlqItemMapper`: confirm a group item archived this way requeues onto
      `enrich-group` (it carries `task_name`), and that `requeueByPipeline` no longer skips it.

## 5. Metrics and docs

- [x] 5.1 `RunnerMetrics`: add `runner.group.sweep.deferred` and widen `recordGroupSweep` to take it.
- [x] 5.2 `docs/configuration.md`: one row for `GROUP_MAX_INFLIGHT` in the group table, one line, with the
      `0` → derive-from-`DB_SCHEDULER_THREADS` rule stated in it.
- [x] 5.3 `docs/e2e-runbook.md` §11: how to read a starved write lane (`enrich-group` deep, `upsert-rows`
      queued and never picked, `Computed group …` with no `Upserted …`), what the depth ceiling looks like
      in the sweep log, and why raising `GROUP_MAX_INFLIGHT` is not the knob that changes throughput.

## 6. Tests

- [x] 6.1 Priority test: assert the stored `priority` on each task — `upsert-rows` above `enrich-group`
      and `enrich-chunk`, backfill chunks below live ones, and a requeued upsert at the write-back
      priority rather than `0`. Added to `UpsertEnqueuerIntegrationTest` rather than as its own
      `@SpringBootTest`: a new class with its own `@DynamicPropertySource` is a whole extra application
      context, and the suite's shared PostgreSQL container runs out of connections before it runs out of
      contexts (the symptom is `FATAL: sorry, too many clients already`, on whichever context loads last).
- [x] 6.2 Starvation regression test, in the same class: with a compute backlog deeper than the pool and a
      pending write, assert the write is the next execution db-scheduler's own polling order returns. Pinned
      on that ordering rather than on wall-clock drain behaviour, so it cannot pass by being slow.
- [x] 6.3 Sweep test: more ready groups than the ceiling enqueues exactly the headroom, counts the rest
      deferred, and enqueues them on a later pass once pending work completes; a pending write-back
      occupies ceiling space. A **unit** test (`GroupReadinessSweepDepthTest`) for the same context-budget
      reason — the behaviour is arithmetic over the in-flight set.
- [x] 6.4 Cold-cache test, per lane: a chunk, a group item and a write item due against a never-populated
      cache are all held — not dropped, not archived, no attempt consumed — and execute once the cache is
      populated. Plus: a cache populated to **empty** takes the ordinary absent-pipeline path.
- [x] 6.5 Group dead-letter test: a non-retryable group-fetch refusal archives **with** a payload and the
      item requeues onto `enrich-group`; a `408` retries instead of dead-lettering.

## 7. Specs

- [x] 7.1 Confirm the delta specs match what shipped (`group-enrichment`, `enrichment-worker`,
      `resilience-and-dlq`, `observability`) and run `openspec validate unstarve-write-back --strict`.

## 8. Verification

- [x] 8.1 `./gradlew clean build` passes (compile + checkstyle + tests); prefix with
      `TESTCONTAINERS_RYUK_DISABLED=true` if the reaper cannot start in this Docker environment.
