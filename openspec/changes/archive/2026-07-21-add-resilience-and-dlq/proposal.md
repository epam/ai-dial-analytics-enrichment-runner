## Why

`add-enrichment-worker` processes chunks but transient failures retry forever (db-scheduler default) and permanent
failures are only isolated per row — there is no bounded retry, no dead-letter archive, no operator
recovery path, and no protection against a mis-bound rule flooding the DLQ. This change
adds the resilience and DLQ surface so the 429/poison paths behave per the failure taxonomy.

## What Changes

- **Rate limiting**: a per-rule Resilience4j token bucket (`DialRateLimiter`) sized to the rule's
  `rate_rpm` (or a default); a worker that can't get a permit fails the chunk (which then backs off).
- **Backoff + DLQ archive**: a custom `ChunkFailureHandler` reschedules a failed chunk with
  exponential backoff + jitter up to `worker.max-attempts`, then archives the chunk (with its
  serialized payload) to `dlq_item` and removes it — instead of db-scheduler's silent deletion.
- **Failure classification**: the worker wraps transient failures (DIAL 429/5xx, rate-limit
  exhaustion, ADAS upsert failure) as `TransientChunkException` carrying the DLQ stage.
- **DLQ circuit breaker**: `DlqCircuitBreaker` auto-pauses a rule (via `RulePauseRegistry`) when
  its dead-letter rate in a page/chunk crosses a threshold or fraction; the scan skips paused rules.
- **DLQ ops API**: `GET /v1/dlq` (list), `POST /v1/dlq/{id}/requeue` and `POST /v1/dlq/requeue`
  (bulk) re-enqueue archived chunks and delete the item; a TTL sweep deletes items past retention.
- **Rule admin API**: `POST /v1/rules/{id}/pause|resume`, `GET /v1/rules/paused` for recovery.
- **Schema**: `dlq_item.payload` (the re-runnable work item; null for poison rows) — folded into
  `V2__runner_state.sql` since the service is not yet deployed anywhere.
- **Config**: `worker.max-attempts/backoff-base`, `dial.default-rpm/rate-limiter-timeout`,
  `dlq.retention-days/sweep-cron`, `dlq.breaker.threshold/fraction`.

## Capabilities

### New Capabilities
- `resilience-and-dlq`: bounded chunk retry with backoff, dead-letter archival + requeue + TTL,
  per-rule DIAL rate limiting, and the DLQ circuit breaker with rule pause and resume.

### Modified Capabilities
<!-- none: extends the enrichment-worker/incremental-scan handlers without changing their spec'd behavior -->

## Impact

- New dep: `io.github.resilience4j:resilience4j-ratelimiter:2.3.0` (core module).
- `dlq_item.payload` added to `V2__runner_state.sql` (no new migration — undeployed service);
  `DlqItemEntity.payload` + repository queries.
- New code: `dial.DialRateLimiter`, `worker.ChunkFailureHandler`, `pause.*`, `dlq.{DlqRequeueService,
  DlqSweeper}`, `web/controller/{DlqController,RuleAdminController}` + DTOs, config records,
  `TransientChunkException`, DLQ error codes.
- `WorkerService` and `RuleScanExecutor` gain rate-limiting, failure classification, and flood
  detection; `EnrichChunkTask` gets the custom failure handler.
