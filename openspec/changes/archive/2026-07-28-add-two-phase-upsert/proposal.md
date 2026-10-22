## Why

A work item today computes and writes in one execution, so any failure retries the whole thing. When the
failure is the **write** — an ADAS 5xx, a timeout, a Postgres blip behind it — the retry re-renders the
prompt and re-calls the model to reproduce a result the runner already had. That is a duplicated model call
per upsert retry, and with `worker.max-attempts` at 5 a persistently flaky write path can cost five calls to
land one row.

The row lane named this: the archived `add-incremental-scan` design closes with "**recompute-on-retry** is
inherited from the design and lives in the worker". `add-group-worker` then made it worse in the case that matters most — a
group's evaluation is one call over a potentially large input, so recomputing it is the most expensive retry
the runner performs.

This change splits compute from write. An upsert retry re-attempts the write
with the outputs it already holds, and never calls the model again.

## What Changes

- **A separate `upsert-rows` work item** carrying the computed enrichment rows. Compute ends by enqueueing
  it; the write happens in its own execution with its own retry budget.
- **The enqueue and the compute's dead-letter writes commit together**, so a computed result is never lost
  between the two phases: either the upsert item exists or the compute is retried.
- **Both grains split the same way.** A row chunk's compute produces N rows in one upsert item; a group's
  produces one. This is affordable precisely because `add-group-worker` extracted the shared pipeline — there is one compute
  path to split, not two.
- **The upsert item is idempotent by construction.** It carries the rows and nothing else, so re-running it
  writes the same rows, which the target's `ReplacingMergeTree` collapses. Its instance id derives from the
  compute item's, so a repeated compute cannot enqueue a second write of the same result.
- **Provenance is stamped at compute time, not write time.** `enriched_at` records when the label was
  derived; deferring it to the write would make a retried write report a time the model never saw.
- **The stale-evaluator guard is re-checked at write time.** A rule re-pinned between compute and write must
  not have a row written against the old evaluator version.
- **Class-1 is untouched.** There is nothing to recompute: ADAS computes and writes inside one call, so the
  runner never holds an intermediate result.
- **Group state is stamped after the write succeeds**, which now happens in the upsert phase — so a group
  stays dirty until its row actually lands, rather than being marked clean on a write that later failed.

## Capabilities

### New Capabilities

<!-- none: restructures the enrichment-worker and group-enrichment write paths -->

### Modified Capabilities

- `enrichment-worker`: "Rule-scoped upsert of a chunk's rows" becomes a separate phase with its own item and
  retry budget; "Failure isolation taxonomy" — an upsert failure no longer retries compute.
- `group-enrichment`: "Evaluated groups are stamped clean" — the stamp moves to the successful write, so a
  group is not marked clean by a write that has not landed.
- `resilience-and-dlq`: "Bounded chunk retry with backoff" and "Dead-letter archival and requeue" cover a
  third work-item type, whose payload makes it always requeueable.
- `service-runtime`: "PostgreSQL runtime-state schema" — the dead-letter `task_name` records the new item
  type so a requeue re-enqueues the right one.

## Impact

- **New code**: `worker/upsert/{UpsertRowsTask,UpsertPayload,UpsertService,UpsertFailureHandler}`.
- **Modified code**: `worker/WorkerService` (ends at enqueue, no longer writes),
  `worker/group/GroupWorkerService` (same; the group stamp moves to the write),
  `dlq/DlqRequeueService` (a third task to dispatch to), `configuration/DbSchedulerConfiguration`.
- **Migration**: none. `dlq_item.task_name` already exists from `add-group-worker` and already defaults correctly.
- **ADAS contract**: none — the same `POST /v1/rules/{id}/rows` call, made from a different execution.
- **Configuration**: none. The upsert phase reuses `worker.max-attempts` and `worker.backoff-base`; a
  separate budget would be a knob with no evidence behind it.
- **Payload size**: `scheduled_tasks.task_data` now carries extracted output columns for a chunk — labels and
  a grain key, never source rows. That bound is a size *and* a privacy requirement, and is stated as both.
- **Not here**: batching multiple compute results into one write (the chunk is already the batch); and any
  change to Class-1, which has no intermediate result to carry.
