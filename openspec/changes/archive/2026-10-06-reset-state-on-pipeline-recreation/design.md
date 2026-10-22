## Context

This file records the decisions as implemented. The resulting behaviour is specified in
`openspec/specs/pipeline-sync/spec.md` ("A recreated pipeline starts from empty runner state").

## Decisions

### `created_at` on the cursor row, not a new identity key

A full re-key (an incarnation UUID on every table and in every work-item id) was rejected as too heavy for the
scenario. One nullable column on the one table that always exists for a synced pipeline is enough to detect the
change. The sync inserts that row on first sighting, and a commit inserts it if the sync has not, stamped with the
tick's `created_at`, so "no row" means only "never seen". A row from before the column is adopted, unless it was last
written before the feed's `created_at`: then it belonged to a predecessor that was not in the feed at the first poll
after deploy, and is reset.

### The persisted reset is a compare-and-set

The sync resets persisted state only when the row's value differs, and the reset's first statement re-checks it: it
empties the cursor row and records the new value only while the row still records the value the sync read. A second
pod that read the same old value finds the row already handed over and resets nothing. A cache-only mismatch, where
another pod already reset during a rolling-update overlap, resets in-memory state only. That way a second pod never
discards progress the new pipeline has already made.

### The cursor row is the first lock on both sides

- Every live commit (row lane, group lane, Class-1) locks the cursor row first, before any enqueue or mark.
- The reset locks it first too (the compare-and-set), then cancels runs, deletes groups and purges the queue.
- A backfill commit locks `run_history` and then `group_state`, the same order the reset uses after the cursor.

So:
- If the commit locks first, its chunks and marks are committed before the reset goes on, and the reset discards them.
- If the reset goes first, the commit waits, finds a row it does not own, and refuses before writing anything.
- No pair of transactions takes two of these locks in opposite orders, so none deadlocks another.

### One guarded cursor write for all three lanes

`PipelineCursorWriter.lockOwned` joins the commit's transaction and:
1. inserts the row if absent (`ON CONFLICT DO NOTHING`), stamped with the tick's `created_at`;
2. locks it (`PESSIMISTIC_WRITE`);
3. refuses on a mismatch.

`advance` then applies the guarded cursor and lag at the end of the commit. `lockAndAdvance` does both in a
transaction of its own for the Class-1 lane.

The entity maps `pipeline_created_at` as read-only, so no save can overwrite it.

### Queue purge by SQL, unpicked only

The purge reuses the escaped prefix match of `InflightCounter`. Backfill items are namespaced by run, so they are
matched through `run_history`. A picked item is executing and is left alone, as Risks / Trade-offs below
records.

### Operator pauses survive

`PauseOrigin` says an operator pause must survive anything the runner concludes, so the reset clears only a breaker
pause.

### Untagged counter, WARN line, no operator-action line

The reset is a machine decision. A per-pipeline counter would be evicted with the pipeline it counts, so the counter
is untagged and the WARN line names the pipeline.

## Risks / Trade-offs

- A failed reset is retried on the next poll. Until it succeeds, the guarded write refuses every commit for the new
  pipeline, so the stale cursor cannot advance in the meantime.
- Work items, dead letters and spend are keyed by name and generation, not `created_at`. When the new pipeline's
  generation coincides with the old one's (ADAS creates at 1; an import arms at 2), an old item still queued (picked
  at the reset, or re-queued for retry by it) shares its id with the new pipeline's first items, which are not enqueued
  until it is gone, and an old group item can stamp a re-marked group computed. Same-day spend and new dead letters
  land on the old pipeline's rows. Carrying `created_at` in the payloads, ids and stale checks would close this; it is
  left out because it re-keys every work item at deploy.
- A group-readiness sweep that selected dirty groups before the reset can enqueue items after the purge.
