## RENAMED Requirements

- FROM: `### Requirement: Per-rule DIAL rate limiting`
- TO: `### Requirement: Per-pipeline DIAL rate limiting`
- FROM: `### Requirement: DLQ circuit breaker with rule pause and resume`
- TO: `### Requirement: DLQ circuit breaker with pipeline pause and resume`

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
requeued compute re-computes. A per-row poison item (no payload) SHALL NOT be requeueable. Items past the
retention window SHALL be swept.

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

### Requirement: Per-pipeline DIAL rate limiting

DIAL calls SHALL pass through a per-pipeline token-bucket rate limiter sized to the pipeline's rate budget (or
a configured default). A worker that cannot obtain a permit within the configured timeout SHALL fail
the chunk transiently (so it backs off), rather than exceeding the budget.

#### Scenario: Requests beyond the budget are refused within the window

- **WHEN** more permits than the per-minute budget are requested within a window
- **THEN** the excess requests are refused

### Requirement: DLQ circuit breaker with pipeline pause and resume

The runner SHALL auto-pause a pipeline's enqueue when its dead-letter rate within a single scan page or
worker unit of work crosses the configured absolute count or fraction. The threshold is page-scoped (a page
holds up to the scan limit) and the fraction is the effective lever on a worker chunk (whose size is
well below the count); a non-positive threshold or fraction SHALL disable that lever rather than
tripping on the first dead-letter.

Group work items SHALL feed the same breaker through a **fraction measured over a bounded window of the
pipeline's most recent group outcomes**. A group work item is a single unit, so it has no page to be a fraction
of, and a fraction computed over it alone would read as a 100% failure rate and pause the pipeline on its very
first poison group; a running count of failures is no substitute, because a count that never decays reaches
any threshold eventually, so a pipeline with a small permanent failure rate would auto-pause indefinitely
however healthy it is. The window is therefore the denominator: each completed group — including one that
resolves empty and needs no write — and each dead-lettered group SHALL contribute **exactly one** outcome to
it, so a group's compute phase and its write phase together report once. The window SHALL be full before it
can trip, which makes it the minimum sample too, and tripping SHALL drop it so recovery after a resume is
judged on a fresh window rather than on the failures that caused the pause. A non-positive window SHALL
disable the group lane rather than trip on its first outcome, and the absolute count lever SHALL NOT apply
to it — a count is a page/chunk lever and a group has neither.

Conversely a **rejected write** SHALL count as all of its rows having failed, because the whole write was
rejected and the cause — a column the pipeline no longer declares — is what the breaker exists to catch.

A scan fire for a paused pipeline SHALL be
skipped, a readiness sweep for a paused pipeline SHALL be skipped, and a multi-page scan fire SHALL stop
draining as soon as a page trips the breaker (so the rest of the backlog is not enqueued before the pause
takes effect). An operator SHALL be able to pause and resume a pipeline and list paused pipelines.

#### Scenario: A flooding pipeline is auto-paused and then skipped

- **GIVEN** a pipeline whose dead-letter rate crosses the flood threshold
- **WHEN** the breaker records the flood
- **THEN** the pipeline is paused and its subsequent scan fires are skipped

#### Scenario: One poison group does not pause its pipeline

- **GIVEN** a group pipeline whose outcome window is wider than one group
- **WHEN** a single group is dead-lettered
- **THEN** the pipeline is not paused and its other groups continue to be evaluated

#### Scenario: A residual group failure rate never pauses its pipeline

- **GIVEN** a group pipeline a minority of whose groups the model can never label
- **WHEN** it evaluates groups indefinitely at that failure rate
- **THEN** the pipeline is never paused, however many groups it has processed

#### Scenario: A group pipeline that breaks is paused on its recent outcomes

- **GIVEN** a group pipeline whose window is full of successes
- **WHEN** enough of its subsequent groups dead-letter to cross the fraction within the window
- **THEN** the pipeline is paused, and earlier successes that have left the window do not spare it

#### Scenario: A group pipeline's first failures are not enough to pause it

- **GIVEN** a group pipeline that has produced fewer outcomes than its window holds
- **WHEN** every one of them is a dead-letter
- **THEN** the pipeline is not paused, because the window is not yet a sample

#### Scenario: An in-flight drain halts when a page trips the breaker

- **GIVEN** a scan fire draining multiple pages for a pipeline
- **WHEN** a page's dead-letter rate trips the breaker and the pipeline becomes paused mid-fire
- **THEN** the drain stops without scanning or enqueuing further pages in that fire

#### Scenario: An operator resumes a paused pipeline

- **WHEN** an operator resumes a paused pipeline
- **THEN** it no longer appears paused and its scans run again

#### Scenario: A paused group pipeline stops being swept

- **GIVEN** a group pipeline that the breaker has paused
- **WHEN** the readiness sweep runs
- **THEN** no group work item is enqueued for that pipeline
