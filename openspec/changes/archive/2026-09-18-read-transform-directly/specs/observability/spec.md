## ADDED Requirements

### Requirement: A work item predating the evaluator fold is counted, not only logged

The runner SHALL count every work item it recognises as predating ADAS's evaluator fold as
`runner.workitem.prefold`, tagged by `lane` (`chunk`, `group`, `upsert`). The series SHALL be registered
at startup for all three lanes rather than on first use, for the same reason the cursor-stall counters
are: this fires only in the deploy window that follows the fold, and an alert cannot be written against a
series that springs into existence at the moment it is already too late.

The tag SHALL be the lane and SHALL NOT be the pipeline. The condition is a property of the **roll-out** —
a job queue that outlived the build that filled it — and no pipeline owns it; what the lane says is what
became of the item, which is the only question the signal is asked. `chunk` and `upsert` mean an item
normalised onto the pipeline's live `generation` and run, and `group` means an item discarded because the
readiness sweep re-derives it unaided.

This counter SHALL be the **only** record of the event, which is what makes registering it non-optional.
Nothing is dead-lettered for a pre-fold item on any lane, so the dead-letter queue does not move and
there is nothing to list afterwards; the log line, though at WARN, is a line. The counter SHALL be
expected to **rise during the fold's deploy window and then stay flat on every lane permanently**, so the
alertable condition is an increment after that window rather than any non-zero value.

#### Scenario: A tolerated pre-fold item increments its lane's counter

- **GIVEN** a work item enqueued before the fold, still queued when the post-fold build starts
- **WHEN** it becomes due and is adopted or discarded rather than dropped as stale
- **THEN** `runner.workitem.prefold` increases for that item's lane, and no dead-letter row is written

#### Scenario: All three lanes are scrapeable before any item arrives

- **WHEN** the Prometheus endpoint is scraped on a freshly started process
- **THEN** `runner.workitem.prefold` is present for each of `chunk`, `group` and `upsert`, reading zero
