## ADDED Requirements

### Requirement: A backfill's cursor obeys the same page invariants as the live scan

A backfill run SHALL apply the row-accounting and missing-column rules of the `incremental-scan`
capability to its own cursor: a page whose rows do not carry a column the lane requires SHALL fail the
batch and leave the run cursor where it was, and a page whose accounted outcomes do not cover every row it
read SHALL NOT have its advance committed.

The rules are stated for the backfill as well as the live scan because the *cursor* is what they protect
and a backfill holds a second, independent one. The consequence is also strictly worse here. A live scan
that burns a page loses the rows ingested in one tick and keeps meeting new ones; a backfill exists to
re-drive a **history**, drains batch by batch until it reports complete, and marks the run finished when
its cursor reaches the end of the window. A silent skip therefore consumes the whole replay, and the run
reports success — after which there is no signal left that says the window was not covered, because
"already enriched" is not a predicate anywhere and the run row says done.

The shared page builders already produce the same accounting for both lanes; what this requires is that
the backfill's committer refuse on it, rather than the refusal living only in the live path's committer.

#### Scenario: A backfill page missing a required column does not advance the run cursor

- **GIVEN** a backfill batch whose page carries rows that do not carry the pipeline's `grain_key`
- **WHEN** the batch is processed
- **THEN** the batch fails, the run cursor is unchanged, the run is not marked complete, and no
  dead-letter item is written for the page's rows

#### Scenario: A wholly or partly unaccounted backfill page does not advance the run cursor

- **GIVEN** a backfill page whose accounted outcomes do not cover every row it read
- **WHEN** the page would be committed
- **THEN** no advance is persisted, the batch fails, and the refused page is counted

#### Scenario: A backfill page accounted for entirely by declared skips still advances

- **GIVEN** a backfill page whose every row was excluded by sampling or skipped for a null in a required
  column
- **WHEN** the page is committed
- **THEN** the run cursor advances and the run proceeds, because every row is accounted for
