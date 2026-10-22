## ADDED Requirements

### Requirement: Bounded chunk retry with backoff

A transient chunk failure (DIAL 429/5xx, rate-limit exhaustion, ADAS upsert failure) SHALL retry the
whole chunk on an exponential backoff with jitter, up to a configured maximum number of consecutive
failures. On reaching the maximum the chunk SHALL NOT be retried again.

#### Scenario: A chunk is rescheduled before the limit

- **WHEN** a chunk fails and its consecutive-failure count is below the maximum
- **THEN** it is rescheduled to a later time (backoff) rather than dead-lettered

#### Scenario: Backoff grows with consecutive failures

- **WHEN** the backoff delay is computed for a higher consecutive-failure count
- **THEN** it is strictly larger than for a lower count and always positive

### Requirement: Dead-letter archival and requeue

On retry exhaustion the runner SHALL archive the chunk to `dlq_item` with its serialized work-item
payload and remove the scheduled execution (rather than deleting it silently). The DLQ SHALL be
listable and requeueable: requeuing an archived chunk re-enqueues its payload and deletes the item in
one transaction. A per-row poison item (no payload) SHALL NOT be requeueable. Items past the
retention window SHALL be swept.

#### Scenario: Exhausted chunk is archived, not lost

- **WHEN** a chunk exhausts its retries
- **THEN** a `dlq_item` carrying its payload is written and the scheduled execution is removed

#### Scenario: Requeue re-enqueues and clears the item

- **GIVEN** an archived chunk DLQ item with a payload
- **WHEN** it is requeued
- **THEN** an enrich-chunk work item is re-enqueued and the DLQ item is deleted

#### Scenario: Poison rows are not requeueable

- **WHEN** a requeue targets a per-row poison item that carries no payload
- **THEN** the request is rejected with `422 dlq_not_requeueable` and the item is retained

### Requirement: Per-rule DIAL rate limiting

DIAL calls SHALL pass through a per-rule token-bucket rate limiter sized to the rule's rate budget (or
a configured default). A worker that cannot obtain a permit within the configured timeout SHALL fail
the chunk transiently (so it backs off), rather than exceeding the budget.

#### Scenario: Requests beyond the budget are refused within the window

- **WHEN** more permits than the per-minute budget are requested within a window
- **THEN** the excess requests are refused

### Requirement: DLQ circuit breaker with rule pause and resume

The runner SHALL auto-pause a rule's enqueue when its dead-letter rate within a single scan page or
worker chunk crosses the configured absolute count or fraction. A scan fire for a paused rule SHALL
be skipped. An operator SHALL be able to pause and resume a rule and list paused rules.

#### Scenario: A flooding rule is auto-paused and then skipped

- **GIVEN** a rule whose dead-letter rate crosses the flood threshold
- **WHEN** the breaker records the flood
- **THEN** the rule is paused and its subsequent scan fires are skipped

#### Scenario: An operator resumes a paused rule

- **WHEN** an operator resumes a paused rule
- **THEN** it no longer appears paused and its scans run again
