## Why

With rules synced and recurring tasks in place (`add-rule-sync`), each fire must actually scan the rule's source
incrementally and enqueue work for the worker. This change implements the
draining incremental scan: ADAS scan → deterministic sampling → input mapping → chunking →
**atomic** enqueue + cursor advance. The worker that consumes the chunks arrives in `add-enrichment-worker`.

## What Changes

- **Scan client**: `AdasScanClient` calling `POST /v1/rules/{id}/scan` with `{cursor, limit}` and
  the `ScanRequest`/`ScanResponse` DTOs.
- **Deterministic sampling**: `Sampler` — `keep ⇔ hash64(grainKey) mod 100 < threshold` with a
  portable FNV-1a 64-bit hash (stable across re-scans, monotone in `sampling`).
- **Input mapping**: `InputMapper` evaluates each input binding (bound column or JSONata over the
  row); a JSONata evaluation error is dead-lettered at stage `input_map`.
- **Chunking + deterministic ids**: kept rows are chunked into `batch_chunk`-sized work items with
  instance ids `sha1(ruleId|evaluatorVersion|cursorBefore)` + rule prefix + chunk index.
- **Atomic commit**: `ScanPageCommitter` enqueues a page's chunks, records input-map DLQ items, and
  advances `rule_cursor` to `nextCursor` in one transaction (db-scheduler joins the tx via the
  tx-aware datasource).
- **Draining executor + backpressure**: `RuleScanExecutor` drains pages up to a per-fire budget,
  skips a rule that is gone from the cache (lifecycle guard) or whose in-flight chunk depth exceeds
  `max-inflight`. The `rule-scan` handler now delegates to it.
- **Enrich-chunk task**: `EnrichChunkTask` (one-time) is enqueued per chunk; its handler is a
  placeholder until `add-enrichment-worker`.
- **Persistence**: JPA entities/repositories for `rule_cursor` and `dlq_item` (+ `DlqStage`).
- **Config**: `scan.default-scan-limit`, `scan.default-chunk`, `scan.max-inflight`,
  `scan.max-pages-per-fire`.

## Capabilities

### New Capabilities
- `incremental-scan`: the draining, cursor-based scan that samples, input-maps, chunks, and enqueues
  work atomically with the cursor advance, under backpressure.

### Modified Capabilities
<!-- none: the rule-sync capability's rule-scan handler is filled in, but its spec'd behavior is unchanged -->

## Impact

- New dep: `com.dashjoin:jsonata:0.9.10` (same version as ADAS).
- New code under `com.epam.aidial.enrichment`: `adas` (`AdasScanClient`, scan DTOs), `scan`
  (`Sampler`, `InputMapper`, `DeterministicId`, `ScanPageCommitter`, `RuleScanExecutor`),
  `worker` (`EnrichChunkTask`, `ChunkPayload`, `ChunkRow`), `persistence` (cursor + DLQ),
  `dlq.DlqStage`, `exception.InputMappingException`.
- No new migration (tables exist from `bootstrap-runner-service`). The DLQ table gains its first writer here; the DLQ
  failure-handler/requeue surface is `add-resilience-and-dlq`.
