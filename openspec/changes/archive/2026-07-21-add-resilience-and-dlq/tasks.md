## 1. Config, migration, DLQ entity

- [x] 1.1 Add `resilience4j-ratelimiter`; extend `WorkerProperties`/`DialProperties`; add
  `DlqProperties` (incl. `breaker`); `application.yml` + `docs/configuration.md`
- [x] 1.2 Add `dlq_item.payload` to `V2__runner_state.sql` (undeployed — no new migration);
  `DlqItemEntity.payload` + repository queries

## 2. Resilience

- [x] 2.1 `RateLimiterConfiguration` + `DialRateLimiter` (per-rule)
- [x] 2.2 `TransientChunkException` (stage-carrying)
- [x] 2.3 `ChunkFailureHandler` (backoff+jitter → DLQ archive+remove); wire `EnrichChunkTask.onFailure`
- [x] 2.4 `WorkerService`: acquire permit; wrap retryable DIAL / upsert failures as transient

## 3. DLQ circuit breaker

- [x] 3.1 `RulePauseRegistry` + `DlqCircuitBreaker`
- [x] 3.2 `RuleScanExecutor` skips paused rules + records page floods; `WorkerService` records chunk floods

## 4. DLQ ops

- [x] 4.1 DLQ DTOs; `DlqRequeueService`; `DlqSweeper` (TTL)
- [x] 4.2 `DlqController` (list, requeue single/bulk); `RuleAdminController` (pause/resume/list)
- [x] 4.3 DLQ error codes in `DefaultExceptionHandler`

## 5. Verification

- [x] 5.1 Unit tests: `ChunkFailureHandler` (reschedule vs archive, backoff), `DlqCircuitBreaker`,
  `DialRateLimiter`
- [x] 5.2 Integration test: requeue archived chunk + poison non-requeueable (Testcontainers PG)
- [x] 5.3 `./gradlew clean build` passes
- [x] 5.4 `openspec validate add-resilience-and-dlq --strict` passes
