## MODIFIED Requirements

### Requirement: Bounded chunk retry with backoff

A transient work-item failure SHALL retry that item on an exponential backoff with jitter, up to a configured
maximum number of consecutive failures. On reaching the maximum the item SHALL NOT be retried again. This
applies per item and therefore per **phase**: a transient compute failure (DIAL 429/5xx, rate-limit
exhaustion) retries the compute, and a transient write failure (an ADAS upsert failure) retries only the
write — never the compute that produced its rows. Both phases SHALL use the same configured budget and
backoff, since there is no evidence the write needs different patience than the compute.

#### Scenario: A chunk is rescheduled before the limit

- **WHEN** a chunk fails and its consecutive-failure count is below the maximum
- **THEN** it is rescheduled to a later time (backoff) rather than dead-lettered

#### Scenario: Backoff grows with consecutive failures

- **WHEN** the backoff delay is computed for a higher consecutive-failure count
- **THEN** it is strictly larger than for a lower count and always positive

#### Scenario: A failing write retries without recomputing

- **GIVEN** an upsert item whose write fails transiently below the retry limit
- **WHEN** it is rescheduled and runs again
- **THEN** only the write is re-attempted, from the item's payload

### Requirement: Dead-letter archival and requeue

On retry exhaustion the runner SHALL archive the work item to `dlq_item` with its serialized payload and
remove the scheduled execution (rather than deleting it silently) — for a row chunk, a group item and an
upsert item alike. The scheduled execution SHALL be removed even when archival fails, so a failed archive
cannot re-loop the execution. Per-row dead-letter writes SHALL be idempotent across chunk retries so
at-least-once retries do not accumulate duplicate DLQ rows; a group's dead-letter SHALL carry the group key in
`grain_key`, so the same idempotency applies to a repeatedly failing group.

The DLQ SHALL be listable and requeueable: requeuing an archived item re-enqueues its payload **onto the task
it came from** and deletes the item in one transaction, so a requeued upsert re-attempts only the write and a
requeued compute re-computes. A per-row poison item (no payload) SHALL NOT be requeueable. Items past the
retention window SHALL be swept.

#### Scenario: Exhausted chunk is archived, not lost

- **WHEN** a chunk exhausts its retries
- **THEN** a `dlq_item` carrying its payload is written and the scheduled execution is removed

#### Scenario: The execution is removed even if archival fails

- **GIVEN** an exhausted chunk whose DLQ archival write fails
- **WHEN** the failure handler runs
- **THEN** the scheduled execution is still removed rather than re-picked into a loop

#### Scenario: Retried per-row failures do not duplicate

- **GIVEN** a chunk with a permanent per-row failure that is retried
- **WHEN** the chunk is re-processed
- **THEN** the row's `dlq_item` is upserted in place, not duplicated

#### Scenario: Requeue re-enqueues and clears the item

- **GIVEN** an archived chunk DLQ item with a payload
- **WHEN** it is requeued
- **THEN** an enrich-chunk work item is re-enqueued and the DLQ item is deleted

#### Scenario: Poison rows are not requeueable

- **WHEN** a requeue targets a per-row poison item that carries no payload
- **THEN** the request is rejected with `422 dlq_not_requeueable` and the item is retained

#### Scenario: An exhausted group item is archived and requeueable

- **WHEN** a group work item exhausts its retries
- **THEN** a `dlq_item` carrying the group key and the item's payload is written, and requeuing it
  re-enqueues one group work item

#### Scenario: An exhausted upsert item requeues as a write

- **WHEN** an upsert item exhausts its retries and is later requeued
- **THEN** an upsert work item is re-enqueued carrying the same rows, and no compute is re-run
