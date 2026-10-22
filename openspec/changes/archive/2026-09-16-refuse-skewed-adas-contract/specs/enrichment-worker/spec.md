## ADDED Requirements

### Requirement: No model call and no write-back while ADAS is incompatible

The worker SHALL NOT call DIAL, and the write-back SHALL NOT be attempted, while the runner has classified
ADAS as incompatible (see the `pipeline-sync` capability). A work item that becomes due in that state
SHALL be left for a later attempt rather than executed, failed or dead-lettered, and SHALL execute
normally once compatibility returns.

The gate SHALL have two entry points, because the classification is derived from an ADAS *response*. A
work item becoming due while the condition is already recorded SHALL be held without making any call; a
work item whose own ADAS call is what discovers the skew SHALL also be held, rather than failed, on that
call's refusal. Without the second arm the first item to meet a fresh skew is precisely the one that pays
for it — the recorded state is still *compatible* when it starts.

Holding SHALL NOT be implemented by letting the item take its lane's ordinary retry path. That path
increments the item's consecutive-failure count and archives it to the DLQ at `WORKER_MAX_ATTEMPTS`, so a
hold expressed as a failure would both consume the budget and — worse — leave the count elevated once the
skew cleared, making the next genuine failure archive on its first attempt. The hold SHALL reschedule the
item so that its failure count is reset, and SHALL NOT archive it however long the condition lasts.

The gate belongs **before** the model call, not at the write. A chunk enqueued before the skew began is
still in the queue when the skew starts, and the lane's shape is derive-then-write: without this gate the
derivation happens first, the DIAL call is made and charged, and only the write-back is refused — so the
retries and the eventual dead-letter all sit downstream of money that has already been spent. Holding the
item costs nothing and loses nothing, because the queue is durable and the derivation is not yet due.

Holding an item is also the right outcome for the write-back specifically: a refusal by a mismatched ADAS
is permanent to the classifier, so the existing retry-then-archive path would consume its attempts and
archive a computed result for a reason that is about to stop being true. What the DLQ exists for is a row
this runner cannot process; a deploy in progress is not that.

#### Scenario: A due chunk is held rather than derived

- **GIVEN** ADAS classified incompatible and a chunk work item already enqueued
- **WHEN** the item becomes due
- **THEN** no DIAL call is made, the item is not failed or dead-lettered, and it remains due for a later
  attempt

#### Scenario: The item that discovers the skew is held too

- **GIVEN** ADAS has become incompatible but no call has yet classified it
- **WHEN** a due work item's own ADAS call meets the missing or mismatched echo
- **THEN** the item is held rather than failed, its attempt count is not consumed, and it is not archived

#### Scenario: A held item does not spend its attempt budget

- **GIVEN** an item held across many due times while the skew lasts
- **WHEN** compatibility returns and the item then fails for an ordinary transient reason
- **THEN** it retries with its full attempt budget, because the holds consumed none of it

#### Scenario: A pending write-back is not attempted

- **GIVEN** ADAS classified incompatible and a computed write-back item
- **WHEN** the item becomes due
- **THEN** no write is attempted, its attempt count is not consumed, and it is not archived

#### Scenario: Held work resumes on its own

- **GIVEN** work held during a skew
- **WHEN** a sync poll receives a matching contract echo
- **THEN** the held items execute on their next due time with no operator action
