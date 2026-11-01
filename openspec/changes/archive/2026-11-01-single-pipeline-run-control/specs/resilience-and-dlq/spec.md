## MODIFIED Requirements

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

The breaker's pause is the **only** pause the runner holds. An operator stops a pipeline in ADAS, through its
`enabled` flag, which is the pipeline's single run control; the runner offers no operator pause of its own, so a
pipeline cannot read as running in one place and stopped in the other. A pause SHALL still record its
**origin**, which is always `breaker`, so the listing and the runtime view keep their shape.

A breaker pause SHALL resume on its own after a bounded window. What the breaker asserts is a statement about the
world outside the runner, inferred from a failure rate, and most of the conditions that produce it — an upstream
outage, a refusing model endpoint — clear without anyone touching the runner; a pause that cannot expire records
that inference as permanent and converts a bounded outage into an unbounded silent stop. A breaker pause SHALL
NOT change the pipeline's `enabled` flag in ADAS, which the runner has no path to write and which records a
decision, not an inference. A breaker pause on a pipeline that is then stopped SHALL be kept and SHALL keep its
own clock.

The auto-pause window SHALL widen on repetition and SHALL reset on recovery. Because tripping drops the
outcome window, a pipeline that auto-resumes into an unfixed upstream must produce a **full window** of
fresh outcomes before it can trip again — at the shipped defaults, fifty group assemblies and fifty model
calls spent to re-derive a verdict the runner held minutes earlier. Each consecutive auto-pause of the same
pipeline SHALL therefore last longer than the last, doubling to a configured cap, so the cost of probing a
long outage grows with its logarithm rather than in proportion to it. A pipeline that fills a full outcome
window **without** tripping SHALL be treated as recovered and its consecutive-pause count reset, that being
the same evidence the trip was made from. An operator's early resume SHALL NOT reset it, an early resume
being a decision rather than evidence about the upstream. A non-positive window SHALL disable expiry, so a
deployment that wants a trip to be sticky can have one.

A scan fire for a paused pipeline SHALL be
skipped, a readiness sweep for a paused pipeline SHALL be skipped, and a multi-page scan fire SHALL stop
draining as soon as a page trips the breaker (so the rest of the backlog is not enqueued before the pause
takes effect). An operator SHALL be able to list paused pipelines and to **lift a breaker pause early**, through
`POST /v1/pipelines/{name}/resume`, once they know the upstream has recovered. That endpoint SHALL lift only a
breaker pause: for a pipeline the breaker has not paused it SHALL answer `409` with the stable error code
`pipeline_not_paused` and change nothing, because it is not a second run control and cannot stop or start a
pipeline. The runner SHALL NOT offer `POST /v1/pipelines/{name}/pause`.

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

#### Scenario: An operator lifts a breaker pause early

- **GIVEN** a pipeline the breaker has paused
- **WHEN** an operator calls `POST /v1/pipelines/{name}/resume`
- **THEN** it no longer appears paused and its scans run again

#### Scenario: Resume refuses a pipeline the breaker has not paused

- **GIVEN** a pipeline the breaker has not paused, whether active or stopped
- **WHEN** an operator calls `POST /v1/pipelines/{name}/resume`
- **THEN** the response is `409` with error code `pipeline_not_paused`, and the pipeline's state is unchanged

#### Scenario: There is no operator pause

- **WHEN** an operator calls `POST /v1/pipelines/{name}/pause`
- **THEN** the response is `404` and no pause is recorded, because the runner serves no such endpoint

#### Scenario: A paused group pipeline stops being swept

- **GIVEN** a group pipeline that the breaker has paused
- **WHEN** the readiness sweep runs
- **THEN** no group work item is enqueued for that pipeline

#### Scenario: An auto-pause outlives the outage by its window and no longer

- **GIVEN** a pipeline the breaker auto-paused during an upstream outage that has since ended
- **WHEN** the pause window elapses
- **THEN** the pipeline resumes without operator action, the resume is logged, and its work is enqueued
  again

#### Scenario: A breaker pause does not stop the pipeline in ADAS

- **GIVEN** a pipeline the breaker has paused
- **WHEN** its pause window elapses, or an operator lifts it early
- **THEN** its `enabled` flag in ADAS was never changed, and it runs again without any ADAS patch

#### Scenario: A persistent outage costs a logarithm of probes, not a multiple

- **GIVEN** a pipeline that auto-pauses, resumes, and immediately trips again
- **WHEN** it trips repeatedly without an intervening recovery
- **THEN** each pause lasts longer than the previous one up to the configured cap

#### Scenario: Recovery resets the widening

- **GIVEN** a pipeline that has auto-paused several times and then completes a full outcome window without
  tripping
- **WHEN** it next auto-pauses
- **THEN** the pause is the shortest configured one again

#### Scenario: Expiry can be switched off

- **GIVEN** a deployment configuring a non-positive auto-pause window
- **WHEN** the breaker trips
- **THEN** the pause is held until an operator lifts it through `POST /v1/pipelines/{name}/resume`
