## Context

`add-enrichment-worker` left transient failures on db-scheduler's default handling and had no dead-letter archive or
operator recovery. This change adds the resilience layer the plan's failure taxonomy requires, all within the
single-active-replica stance (per-JVM rate limiter, in-memory pause set).

## Goals / Non-Goals

**Goals:** bounded backoff retry → DLQ archive; DLQ list/requeue/TTL; per-rule DIAL rate limiting;
DLQ-circuit-breaker auto-pause with manual resume.

**Non-Goals:** cluster-wide (multi-replica) rate limiting and per-tenant fairness (deferred); the
two-phase compute/upsert split that would avoid DIAL recompute on an upsert retry (deferred).

## Decisions

- **Custom `FailureHandler` over db-scheduler defaults.** It reschedules with exponential backoff +
  jitter up to `worker.max-attempts`, then archives the chunk payload to `dlq_item` and removes the
  execution. Archiving the payload (new `payload` column) is what makes requeue possible; per-row
  poison items carry no payload and are list-only.
- **Transient failures carry their stage.** The worker throws `TransientChunkException(stage)` for
  DIAL 429/5xx, rate-limit exhaustion, and upsert failures, so the handler tags the archived item
  (`dial_call` vs `upsert`) without guessing from the exception.
- **Resilience4j core, per-rule, per-JVM.** Built directly (no Spring Boot starter) to avoid Boot 4
  autoconfig risk. A courtesy limiter — DIAL Core's own per-key limit is the real backstop, per the
  plan's single-replica stance.
- **In-memory pause set.** `RulePauseRegistry` gates enqueue (scan skips paused rules), reusing the
  backpressure "skip before scanning" mechanism. Auto-pause on flood; manual resume to recover.
- **Requeue is transactional.** Re-enqueue (tx-aware db-scheduler) + delete (JPA) commit together, so
  a requeue never both re-enqueues and leaves the stale DLQ item.

## Risks / Trade-offs

- **Archive + remove are not one atomic unit** (db-scheduler ops use their own connection) → on a
  rare crash between them the chunk retries and re-archives (harmless dup) or the archive is missed;
  acceptable for now.
- **Per-JVM limiter can't hold a global budget across replicas** → single active replica by design;
  cluster limiter deferred.
- **Pause set is in-memory** → a restart clears pauses; a rule that is still flooding will re-trip the
  breaker on the next fire, so no unbounded flood results.
