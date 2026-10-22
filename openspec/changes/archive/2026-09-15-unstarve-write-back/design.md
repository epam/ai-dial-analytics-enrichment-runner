# Design — unstarve the write-back

## The mechanism, as the code has it

`enablePriority()` is already on the scheduler (`DbSchedulerConfiguration:39`), and db-scheduler's polling
query then orders `priority desc, execution_time asc` (`JdbcTaskRepository:742`, verified against
db-scheduler 15.6.0 sources). So the runner is **already** priority-aware; the defect is that it declares
the write-back to be in the same lane as the compute:

| call site | task | priority |
|---|---|---|
| `ScanPageCommitter:60` | `enrich-chunk` | `PRIORITY_LIVE` = 10 |
| `GroupReadinessSweep:193` | `enrich-group` | `PRIORITY_LIVE` = 10 |
| `BackfillPageCommitter:62` | `enrich-chunk` | `PRIORITY_BACKFILL` = 1 |
| `GroupAdminController:85` | `enrich-group` | `PRIORITY_LIVE` = 10 |
| **`UpsertEnqueuer:48`** | **`upsert-rows`** | **`PRIORITY_LIVE` = 10** |
| `DlqRequeueService:78,84,89` | all three | *unset* = 0 |

Within a lane the tiebreak is age, and a write is created **by** a compute, so it is always younger than
that compute and younger than every compute enqueued in the same sweep pass. That is not a race the write
sometimes loses; it is an ordering it can never win. It escapes only in the gaps — when the compute pool
momentarily empties — and a sweep that tops the queue back up every thirty seconds is precisely what
removes those gaps.

Two details of the starvation are worth pinning because they correct the obvious reading:

- A queue plateau at ~500 is **not** `SCAN_MAX_INFLIGHT` (1000) binding. It is `GROUP_SWEEP_BATCH`: the
  sweep reads at most 500 dirty-and-ready rows ordered `last_activity_at` ascending, and once the head 500
  are all in flight the suppression check skips every one of them — each pass enqueues fewer and skips
  more, until it enqueues nothing and skips all 500. Backpressure never fires.
- The retry path partially *masks* the starvation: a failing group reschedules to `now + backoff`, which
  sorts it **behind** the pending writes. While ClickHouse is refusing, that is what lets any write through
  at all. A healthy pipeline has no such relief valve, so the starvation is worst when nothing else is
  wrong.

## Decision 1 — a priority lane for write-back

`WRITE_BACK = 20 > LIVE = 10 > BACKFILL = 1`, on a `WorkPriority` holder that all five call sites read.

The principle is *finish before you start*: a write is bounded (one HTTP POST over rows already in hand,
no model call), it is idempotent, and it is the only step that converts spend into a stored label. Nothing
downstream of it can be starved by it, because the set of pending writes is bounded by the set of computes
that have already completed — write-back cannot generate more write-back. The inverse is not true, which
is the whole asymmetry.

Backfill write-backs share the lane with live ones deliberately. A backfill's *compute* is throttled to
`BACKFILL` on purpose; its result, once paid for, is worth exactly as much as a live one and carries the
same restart exposure. Splitting the write lane in two would re-create the bug one level down.

### Alternatives rejected

- **A dedicated executor or a reserved thread for `upsert-rows`.** db-scheduler has one pool and one
  poller; carving a lane out means a second `Scheduler` over the same table, with its own heartbeat,
  its own dead-execution detection and two pollers competing for the same rows. It buys a hard guarantee
  the priority lane gives softly, at the cost of the most delicate machinery in the service.
- **Making db-scheduler priority-aware.** Already done — `enablePriority()` has been on since the
  backfill lane landed. Reaching for it again would have left the actual defect (a borrowed constant)
  in place.
- **Scheduling `upsert-rows` at an `execution_time` in the past, so it sorts ahead by age.** It works,
  and it is a lie in a column other things read: `execution_time` is what the admin queries, the
  queue-depth gauges and any operator's `ORDER BY` interpret as "when this is due". Encoding precedence
  in a timestamp means every future reader of that column has to know it is not one. Priority is the
  column that means precedence.
- **Sizing the sweep batch against free threads and nothing else.** It reduces the starvation window but
  does not close it: any batch larger than the pool re-establishes the same ordering, and a batch the size
  of the pool makes the sweep interval the throughput ceiling. It is a useful second measure, not a fix —
  which is why this change makes it a *depth ceiling* argued on restart exposure rather than on ordering.

## Decision 2 — a depth ceiling, and what it is actually for

`GROUP_MAX_INFLIGHT` defaults to `0`, meaning `DB_SCHEDULER_THREADS × 10` (50 at the shipped default). The
sweep computes `ceiling − current depth` from the in-flight id set it already fetches, enqueues up to that,
and defers the rest with a log line and a counter.

The ceiling does **not** make the pipeline faster and is not claimed to. The pool drains at the rate the
pool drains. What it changes is where a group waits:

- in `group_state`: durable, one row, re-derivable from scratch, and the sweep re-selects it for free;
- in `scheduled_tasks`: a row this service drops on restart (Decision 3 narrows that, but a held item
  still has to wait out the cold window), and one that pins a `group_version` snapshot which may be stale
  by the time it runs.

Ten items per thread is two poll intervals' worth of work at any plausible item duration — enough that a
thread never idles waiting for the next sweep, small enough that a restart exposes tens of items rather
than hundreds. Derived from the thread count rather than fixed because the failure mode was a constant
that stopped being right when the pool size was chosen independently of it.

`SCAN_MAX_INFLIGHT` (1000) stays where it is and keeps its meaning: a coarse, cross-lane stop for a
pipeline that has run away. It is not a substitute — it does not fire in the starvation above, and at ten
times the new ceiling it is not meant to.

## Decision 3 — "not yet populated" is not "not present"

`PipelineCache` starts empty and is first written by a sync that is ten seconds behind process start
(`ADAS_PIPELINES_INITIAL_DELAY_MS`), while `SchedulerLifecycle` starts polling with the context. Every
work service reads a null from that cache as *the pipeline is disabled or deleted* and acts on it: the row
lane drops the chunk (permanent — the cursor advanced with the enqueue), the group lane drops the compute
(re-swept later, so the cost is a wasted drain rather than lost data), and the write lane archives the
computed result to the DLQ for an operator to requeue by hand.

Those responses are right for the question they think they are answering. They are answering the wrong
one. The fix is to give the cache the distinction: `isPopulated()` flips on the first successful
reconcile, **including a reconcile to empty**, because an empty feed is a real answer about the registry
and not an absence of one. A work item that becomes due while the cache has never been populated is held —
rescheduled, attempt budget untouched, never archived. That treatment is defined once, in `WorkHold`, and
matched on a base type rather than on a single exception: `HeldWorkException` is what
`WorkHold.holdIfHeld` looks for, `PipelineCacheColdException` is its one case today, and each failure
handler gains one arm ahead of its ladder.

A base type for a single case needs justifying. The distinction it draws is the useful one — a condition
the runner is *waiting out* versus a failure it is *retrying* — and the treatment is not something a
second condition should be free to re-derive, because the part that is easy to get wrong is invisible
until the day it costs an archive: the hold must reschedule with an **OK** result, since db-scheduler
writes `consecutive_failures = 0` only then. Expressed on the ordinary failure arm, a hold would leave the
counter elevated for the whole length of the condition, and the first *real* failure after it cleared
would archive on its first attempt.

**Concretely: the ADAS contract-skew guard (`refuse-skewed-adas-contract`) is that second case.** It
needs exactly this mechanism for an ADAS contract skew, so `AdasContractException` extends
`HeldWorkException` and its call sites go through `WorkHold.holdIfHeld` rather than a parallel hold type.
The three failure handlers carry one hold arm each.

Starting the scheduler only after the first successful sync was the other candidate. Rejected: it couples
lifecycle to an upstream that can be down for an hour, it changes `db-scheduler.auto-start` semantics that
tests depend on, and it holds *every* lane for a condition that only affects the three that read the
cache. The hold is precise and applies exactly where the cache is read.

## Decision 4 — make the classification not matter

`RetryableStatus` calls a 4xx permanent. That is right for `400 bad limit`, `403 not entitled` and
`404 unknown pipeline`, and wrong for a capacity refusal that arrives as a `422` because ADAS's read
budget could not be met on a node under memory pressure. The runner cannot tell them apart from the status
alone.

It does not have to. The damage was never the retry decision — it was that
`GroupWorkerService.deadLetter` stores no payload, on the reasoning that "a poison group gains nothing
from replay". That reasoning holds only if the classification is right. When it is wrong, the same line
turns a recoverable outcome into a destroyed one, and it does so *silently* — the DLQ row looks identical.
A `GroupPayload` is four scalars: pipeline name, evaluator version, group key, group version. Storing it
on every group dead-letter costs a few dozen bytes and makes the classification a **latency** question
instead of a **loss** question, which is the only version of it worth arguing about.

`408 Request Timeout` and `425 Too Early` join the retryable set, being unambiguous "later" answers.

**Deliberately deferred: branching on ADAS's `error` machine code.** Classifying
`422 pipeline_read_budget_exceeded` as transient is the accurate fix and it is a *cross-service* contract:
ADAS would have to commit to a stable set of capacity codes the runner may rely on, which is a paired
change in both repos rather than a line added in passing — and the runner deliberately keeps
its dependence on ADAS's error vocabulary as narrow as it can. Until then a budget refusal dead-letters,
and — because of the paragraph above — is requeued rather than lost.

## Risks

- **Write-back starving compute.** It cannot, in steady state: pending writes are bounded by completed
  computes. A large DLQ requeue of write-backs *can* transiently outrank live compute, which is the
  intended behaviour — those rows are already paid for.
- **db-scheduler's fetch buffer.** The default `FETCH` polling strategy holds up to `3 × threads`
  candidates in memory, fetched in priority order at fetch time. A write enqueued while that buffer is
  full of compute waits for it to drain below `0.5 × threads` — bounded by roughly ten items, not by the
  backlog. Acceptable; noted so it is not mistaken for the bug recurring.
- **Held items look like idle ones.** A held item stamps `last_success` (`WorkHold`'s documented price for
  resetting the attempt counter), so a lane held on a cold cache reads as due-but-not-executing. The
  window is one sync interval in the ordinary case; a longer one means the pipeline sync is failing, which
  has its own readiness signal.
