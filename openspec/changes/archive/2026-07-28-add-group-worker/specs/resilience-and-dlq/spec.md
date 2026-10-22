## MODIFIED Requirements

### Requirement: Dead-letter archival and requeue

On retry exhaustion the runner SHALL archive the work item to `dlq_item` with its serialized payload and
remove the scheduled execution (rather than deleting it silently) — for a row chunk and for a group item
alike. The scheduled execution SHALL be removed even when archival fails, so a failed archive cannot
re-loop the execution. Per-row dead-letter writes SHALL be idempotent across chunk retries so
at-least-once retries do not accumulate duplicate DLQ rows; a group's dead-letter SHALL carry the group key
in `grain_key`, so the same idempotency applies to a repeatedly failing group. The DLQ SHALL be listable and
requeueable: requeuing an archived item re-enqueues its payload and deletes the item in one transaction. A
per-row poison item (no payload) SHALL NOT be requeueable. Items past the retention window SHALL be swept.

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

### Requirement: DLQ circuit breaker with rule pause and resume

The runner SHALL auto-pause a rule's enqueue when its dead-letter rate within a single scan page or
worker unit of work crosses the configured absolute count or fraction. The threshold is page-scoped (a page
holds up to the scan limit) and the fraction is the effective lever on a worker chunk (whose size is
well below the count); a non-positive threshold or fraction SHALL disable that lever rather than
tripping on the first dead-letter. Group dead-letters SHALL feed the same breaker, so a rule whose groups
poison repeatedly is paused on the same levers as one whose rows do. A scan fire for a paused rule SHALL be
skipped, a readiness sweep for a paused rule SHALL be skipped, and a multi-page scan fire SHALL stop
draining as soon as a page trips the breaker (so the rest of the backlog is not enqueued before the pause
takes effect). An operator SHALL be able to pause and resume a rule and list paused rules.

#### Scenario: A flooding rule is auto-paused and then skipped

- **GIVEN** a rule whose dead-letter rate crosses the flood threshold
- **WHEN** the breaker records the flood
- **THEN** the rule is paused and its subsequent scan fires are skipped

#### Scenario: An in-flight drain halts when a page trips the breaker

- **GIVEN** a scan fire draining multiple pages for a rule
- **WHEN** a page's dead-letter rate trips the breaker and the rule becomes paused mid-fire
- **THEN** the drain stops without scanning or enqueuing further pages in that fire

#### Scenario: An operator resumes a paused rule

- **WHEN** an operator resumes a paused rule
- **THEN** it no longer appears paused and its scans run again

#### Scenario: A paused group rule stops being swept

- **GIVEN** a group rule that the breaker has paused
- **WHEN** the readiness sweep runs
- **THEN** no group work item is enqueued for that rule
