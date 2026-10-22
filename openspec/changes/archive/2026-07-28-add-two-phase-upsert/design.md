## Context

Both workers end the same way: derive outputs, build write rows, call `POST /v1/rules/{id}/rows`. A
`RestClientException` from that call becomes a `TransientChunkException(UPSERT)`, which the failure handler
retries with backoff — re-executing the *whole* item, compute included. The model call is made again to
reproduce a result the previous attempt already had.

The row path pays this per chunk. The group path pays it worse: one group is one model call over an input that
may be hundreds of members, so a flaky write turns the most expensive evaluation the runner performs into the
one it repeats most.

What makes the split affordable now is `add-group-worker`'s extraction. Before it there were two compute paths; splitting both
would have meant two sets of phase boundaries. `EvaluatorInvoker` means there is one compute step, and each
worker differs only in what it does with the result — which is exactly the seam to cut.

## Goals / Non-Goals

**Goals:** never re-call the model to retry a write; lose no computed result at the phase boundary; keep the
write idempotent so a duplicated attempt is harmless; keep source rows out of the queue; and leave Class-1
alone, since it has no intermediate result.

**Non-Goals:** a separate retry budget for the write phase; batching results across compute items; changing
the enrichment write contract; and making the two phases transactional across ADAS and Postgres, which is not
possible and not needed.

## Decisions

- **The computed rows travel in the work-item payload, not a staging table.** They are small (a grain key and
  a handful of label columns per row), db-scheduler already durably stores task data, and a table would need
  its own lifecycle, retention and cleanup for rows whose only purpose is to survive between two executions
  seconds apart. *Rejected:* a `staged_row` table (real bookkeeping for no gain at this size); and passing a
  reference to re-read the model response from the DLQ (the DLQ is for failures, not a cache).

- **The payload carries extracted outputs only — never source rows.** This is the same rule as
  `GroupPayload`'s, and for both reasons: a chunk's source rows could be megabytes, and for a body-reading
  rule they are the sensitive data the runner must not persist outside the dead-letter window. Compute has
  already reduced them to labels by the time the payload is built, so the constraint costs nothing to honour
  and is stated so a future change does not "simplify" it away.

- **The enqueue commits with compute's dead-letter writes, in one transaction.** Otherwise a crash between
  "compute succeeded" and "write item enqueued" silently drops the result: the compute item is gone, nothing
  holds the rows, and the row is never written. Reusing the tx-aware scheduler — the same mechanism the scan's
  atomic enqueue uses — makes the boundary safe without new machinery.

- **The upsert item's id derives from the compute item's.** A repeated compute (a retry after a transient
  failure that happened before the enqueue) then produces the same id and `scheduleIfNotExists` suppresses the
  duplicate, so a result is enqueued at most once. This is the same belt-and-braces the chunk ids provide for
  the scan.

- **The write is idempotent by construction, so the retry needs no coordination.** The payload holds fixed
  rows keyed by grain; re-writing them collapses on the target's `ReplacingMergeTree` read with `final=1`.
  That is what makes "retry the write forever" safe, and it is why the write phase needs no dedup beyond its
  own id.

- **`enriched_at` is stamped at compute time.** It records when the label was derived. Stamping it at write
  time would make a row that took four retries to land claim a derivation time the model never saw, and would
  make two attempts at the same result differ — which the RMT would then treat as a real update.

- **Staleness is re-checked at write time, not only at compute time.** A rule re-pinned between the phases
  must not write a row provenance-stamped with the superseded evaluator version. The check is the same guard
  the compute phase already applies; applying it again is one call and closes the window.

- **The group stamp moves to the write phase.** Under `add-group-worker` the stamp followed the write in one execution. Split
  apart, stamping at compute time would mark a group clean before its row landed — so a write that then failed
  permanently would leave a group that the sweep never revisits and an enrichment table with no row for it.
  Stamping after the successful write keeps "clean" meaning "written".

- **Class-1 is explicitly out.** ADAS computes and writes inside one `materialize` call; the runner never holds
  an intermediate result, so there is nothing to split and no recompute to avoid. Saying so prevents the
  symmetry argument from adding a phase boundary with nothing to put in it.

- **The write phase reuses the worker's retry budget.** A separate `upsert.max-attempts` would be a knob
  invented without evidence that the write needs different patience than the compute did. It can be split
  later if a real difference shows up.

## Risks / Trade-offs

- **A crash after the write but before the compute item is removed re-runs compute** → the recompute happens,
  the upsert item is re-enqueued under the same deterministic id, `scheduleIfNotExists` suppresses it, and the
  already-written row is unaffected. At-least-once execution, exactly-once effect — the same bargain everywhere
  else in the runner, now costing one extra model call in a rarer window than the one this change removes.

- **`scheduled_tasks` grows a second item per chunk** → more rows and more payload bytes in the queue table.
  Bounded by the same in-flight backpressure (the upsert id keeps the rule prefix, so it counts), and the
  payload is labels rather than source rows. The queue-depth gauge now covers two item types, which `add-phase2-ops`'s
  metrics work should distinguish.

- **A poison payload is now possible** — an upsert item whose rows ADAS rejects with a non-retryable 4xx (a
  column the rule no longer declares, after a mid-flight rule change) → it dead-letters at `upsert` with its
  payload, so it is requeueable once the rule is fixed. Previously the same failure dead-lettered the whole
  chunk; now the compute is not repeated to reach it.

- **Two phases make the failure taxonomy wider** → each phase has one failure mode (compute: model/mapping; write:
  ADAS), which is arguably clearer than one item with both. The cost is a third task name in the requeue
  dispatch and in anything that reasons about queue contents.

- **Provenance stamped at compute time means a long retry chain writes an older `enriched_at`** → intended.
  The label was derived then; the row's timestamp should say when it was derived, not when the write finally
  succeeded. ClickHouse's own `_updated_at` records the latter.
