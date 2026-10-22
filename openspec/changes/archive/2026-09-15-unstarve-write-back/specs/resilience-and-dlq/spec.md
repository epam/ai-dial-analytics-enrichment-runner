## ADDED Requirements

### Requirement: A misclassified failure costs latency, never the work

Every dead-letter the runner writes at **group** granularity SHALL carry the group work item's payload,
whatever the runner concluded about the cause.

The runner classifies an HTTP failure from its status alone: 5xx and 429 are "later", everything else in
the 4xx range is "never". That is right for a rejection of the request as posed — a bad limit, a missing
entitlement, an unknown pipeline — and wrong for a capacity refusal that arrives as a 4xx because an
upstream could not meet a read budget under memory pressure. The two are indistinguishable from the status,
so the classification will sometimes be wrong.

What made that costly was not the retry decision but its second consequence: a failure ruled permanent was
archived **without** a payload, on the reasoning that replaying a deterministic failure gains nothing. That
reasoning holds only while the ruling is correct; when it is wrong the same line converts a recoverable
outcome into a destroyed one, and does so invisibly, since the archived row looks the same either way. The
perverse result is that the better-behaved upstream failure — a clean, deterministic refusal — produces a
worse outcome than a socket timeout, which is ruled transient, exhausts its attempts, and is archived
*with* its payload.

A group work item's payload is four scalars. Storing it on every group dead-letter costs a few dozen bytes
and makes a misclassification a question of **latency** rather than of **loss**: the item is requeueable
whichever way the runner ruled, and an operator's recovery is one requeue instead of a re-derivation that
nothing records the need for. Retryability SHALL additionally treat `408` and `425` as "later", both being
unambiguous statements that the request may be made again.

The runner SHALL NOT branch on an upstream's `error` machine code to make this classification. Doing so
accurately requires the upstream to commit to a stable set of capacity codes, which is a contract between
the two services and a change to both of them; the runner's single existing branch on an ADAS condition is
documented as such deliberately. Until that contract exists, a capacity refusal is dead-lettered — and,
because of the rule above, recovered rather than lost.

#### Scenario: A refused group fetch is recoverable

- **GIVEN** a group fetch that ADAS refuses with a status the runner classifies as permanent
- **WHEN** the group is dead-lettered
- **THEN** the stored item carries the group payload and requeuing it re-enqueues one group work item

#### Scenario: A poison group is still not retried in place

- **GIVEN** the same refusal
- **WHEN** the work item completes
- **THEN** it is not retried against the model or the upstream, and the group is stamped so the readiness
  sweep does not re-derive it

#### Scenario: A timeout-class status is retried rather than dead-lettered

- **WHEN** an upstream answers `408`
- **THEN** the failure is treated as transient and the item retries on its backoff ladder

## MODIFIED Requirements

### Requirement: Dead-letter archival and requeue

On retry exhaustion the runner SHALL archive the work item to `dlq_item` with its serialized payload and
remove the scheduled execution (rather than deleting it silently) — for a row chunk, a group item and an
upsert item alike. The scheduled execution SHALL be removed even when archival fails, so a failed archive
cannot re-loop the execution. A group's dead-letter SHALL carry the group key in `grain_key`.

**Every** dead-letter write carrying a `grain_key` SHALL be an upsert on the identity
`(pipeline_name, grain_key, evaluator_version)`, not an insert. Work items run at-least-once, and the same row or
the same group is therefore dead-lettered on more than one attempt; an insert would raise a constraint
violation instead, which in a failure handler discards the payload it was archiving and in a worker turns a
permanent rejection into an endless retry chain. An item's `created_at` SHALL NOT be refreshed by a repeat,
so a permanently failing item still ages out of the retention window. Every archived item SHALL record the
task it came from, including one with no payload, so a stored row is never mislabelled as another task's.

The DLQ SHALL be listable and requeueable: requeuing an archived item re-enqueues its payload **onto the task
it came from** and deletes the item in one transaction, so a requeued upsert re-attempts only the write and a
requeued compute re-computes. A requeued item SHALL be enqueued at its task's own queue priority, not at
the scheduler's default, which would rank an operator's explicit request below every automatically enqueued
item. A **row-grain** poison item — one dead-lettered per row, which carries no payload because the row it
came from is not reconstructible — SHALL NOT be requeueable. Items past the retention window SHALL be swept.

Both operations SHALL narrow by pipeline under the query parameter `pipeline_name`, and an archived item
SHALL report its owner under the JSON property `pipeline_name`. The `/v1/dlq` paths do not themselves carry
the pipeline in the URL, so this parameter and this property are the only places the identity appears on that
surface — and therefore the only places a caller can be broken by it silently.

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

#### Scenario: The DLQ is narrowed by pipeline name

- **GIVEN** archived items belonging to more than one pipeline
- **WHEN** the DLQ is listed with `pipeline_name` set to one of them, or a requeue is issued for it
- **THEN** only that pipeline's items are listed or requeued, and each listed item reports its owner under
  `pipeline_name`

#### Scenario: Row-grain poison rows are not requeueable

- **WHEN** a requeue targets a per-row poison item that carries no payload
- **THEN** the request is rejected with `422 dlq_not_requeueable` and the item is retained

#### Scenario: An exhausted group item is archived and requeueable

- **WHEN** a group work item exhausts its retries
- **THEN** a `dlq_item` carrying the group key and the item's payload is written, and requeuing it
  re-enqueues one group work item

#### Scenario: An exhausted upsert item requeues as a write

- **WHEN** an upsert item exhausts its retries and is later requeued
- **THEN** an upsert work item is re-enqueued carrying the same rows, and no compute is re-run
