## ADDED Requirements

### Requirement: Work deferred for capacity is counted

The runner SHALL count group work the readiness sweep declined to enqueue because the pipeline was at its
depth ceiling, separately from work it declined because an item for that group was already in flight.

The two are opposite conditions that produce the same silence. Work already in flight is the sweep doing
exactly what it should and is the number an operator wants driven to zero; work deferred for capacity says
the pipeline has more ready groups than the pool can hold, which is normal during a backlog drain and a
symptom when it persists. A pipeline enqueueing nothing because it has nothing ready is a third state
again. Folding any two of them into one counter makes the pass's outcome unreadable.

#### Scenario: A deferral is distinguishable from a suppression

- **GIVEN** a pipeline at its depth ceiling with both already-queued groups and deferred ones
- **WHEN** the sweep runs
- **THEN** the deferred groups are counted on their own series and are not added to the suppressed count
