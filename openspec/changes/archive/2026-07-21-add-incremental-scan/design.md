## Context

This change fills in the `rule-scan` handler stubbed in `add-rule-sync`. The scan is rule-scoped: the runner sends only
`{cursor, limit}` and ADAS applies the stored filter/projection/`final`/safety-lag. Correctness rests
on committing the enqueue and the cursor advance atomically, backstopped by the enrichment table's
grain-key RMT collapse for exactly-once effect (`add-enrichment-worker`'s write path).

## Goals / Non-Goals

**Goals:** the draining scan, deterministic sampling, input mapping with per-row DLQ, chunking with
deterministic ids, the atomic enqueue+cursor commit, and backpressure.

**Non-Goals:** the worker (DIAL call / validate / output map / upsert) — `add-enrichment-worker`; the DLQ
failure-handler, requeue API and DLQ circuit breaker — `add-resilience-and-dlq`; spend and backfill — `add-spend-and-backfill`.

## Decisions

- **Atomic commit via a tx-aware db-scheduler.** The `Scheduler` is wired over a
  `TransactionAwareDataSourceProxy` (`add-rule-sync`), so `scheduleIfNotExists` inside a `@Transactional`
  `ScanPageCommitter.commit` joins the same transaction as the JPA cursor/DLQ writes — enqueue and
  cursor advance commit together. This is the primary exactly-once mechanism.
- **Deterministic ids as the belt to that suspenders.** Chunk instance ids are
  `ruleId#sha1(ruleId|evaluatorVersion|cursorBefore)#c{index}`, derived only from values the runner
  holds (the cursor is opaque). `scheduleIfNotExists` dedups the residual overlap window. The rule-id
  prefix lets backpressure count a rule's in-flight chunks by id prefix without deserializing
  payloads.
- **Portable FNV-1a 64-bit sampling.** Not `String.hashCode` (32-bit, and not guaranteed stable for
  the use) — a fixed 64-bit hash keeps the keep/drop decision stable across re-scans and JVMs and
  makes `sampling` monotone. Could later be pushed into ADAS SQL.
- **Input mapping is JSONata-eval only.** Syntax is validated by ADAS at registration; only
  evaluation errors reach the runner and are dead-lettered at `input_map`. The ADAS `EvaluatorVarDto`
  exposes no `required` flag, so unresolved optional bindings pass through as null (per the plan);
  richer required/enum enforcement is deferred with the contract that would carry it.
- **ObjectProvider breaks the task↔scheduler cycle.** `RuleScanTask` resolves `RuleScanExecutor`
  lazily via `ObjectProvider`, so the executor can depend on the `Scheduler` (which depends on the
  task) without a definitional cycle.

## Risks / Trade-offs

- **JPA + db-scheduler in one transaction** → Both use the same `DataSource` bean; Spring's
  `JpaTransactionManager` binds the connection so the tx-aware proxy shares it. Verified by the
  rollback test (a rejected DLQ write rolls back the enqueue and the cursor advance together).
- **Backpressure scans the chunk task's scheduled executions each fire** → O(queue) per fire;
  acceptable at initial scale, and it filters by id prefix (no payload deserialization).
- **Recompute-on-retry** is inherited from the design and lives in the worker (`add-enrichment-worker`, `add-resilience-and-dlq`), not here.
