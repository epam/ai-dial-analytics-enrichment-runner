## ADDED Requirements

### Requirement: A recreated pipeline starts from empty runner state

The runner SHALL reset the runner state a pipeline name holds when the feed reports that name with a different
`created_at` from the one last recorded for it. ADAS identifies a pipeline by name, and a delete followed by a create
under that name (a catalog import, or a plain `DELETE` + `POST`) is a different pipeline that would otherwise inherit
the old one's scan cursor and skip history. `generation` cannot tell the two apart, because it restarts at 1 and
often climbs back to the old value; `created_at` can, because ADAS sets it once at registration and never changes it.

The runner SHALL record the `created_at` it last saw for each name on that name's `pipeline_cursor` row and, on every
poll and for every admitted pipeline, before any scheduling:

- where no value is recorded (no row, or a row from before the column), record the feed's value and reset nothing —
  except for a row from before the column last written before the feed's `created_at`, which belonged to a
  predecessor (one not in the feed at the first poll after deploy) and SHALL be reset as below;
- where the recorded value equals the feed's, do nothing and write nothing;
- where it differs, reset the persisted state in one transaction — first empty its `pipeline_cursor` row and record
  the new value on it, as a compare-and-set on the value read, then cancel every `running` or `paused` backfill run of
  the name, delete its `group_state` rows, and delete its unpicked work items (every pipeline-scoped item whose id
  carries the name's prefix, and every backfill item of any of its runs) — then evict its in-memory state through the
  same per-name path as a removal, plus its last fire outcome and last write, and reschedule its recurring instance as
  a generation change would. When the compare-and-set finds the row no longer recording the value read, another
  process has already reset it, and nothing persisted SHALL be touched.

A feed entry without `created_at` SHALL be neither recorded nor reset. A breaker pause SHALL be lifted by the reset
and an operator pause SHALL be kept, since an operator's stop must survive anything the runner concludes. When only
the cached declaration's `created_at` differs, the persisted state having already been reset by another process
during a rolling-update overlap, the runner SHALL reset its in-memory state only, logged at INFO and not counted; the
other process has already rescheduled the shared recurring instance. A reset that fails SHALL be isolated to its
pipeline and retried on the next poll; a failure to read the recorded values skips the check for every pipeline that
poll.

The reset deliberately keeps `dlq_item` and `spend_counter` rows, `run_history` rows (cancelled, not deleted), and
work items already picked, which are executing. Work items and dead letters carry the name and generation but not the
`created_at`, so when the new pipeline's generation coincides with the old one's, a dead letter or same-day spend
lands on the old pipeline's row, and an old item still queued (picked at the reset, or re-queued for retry by it)
shares its id with the new pipeline's first items, which are then not enqueued until it is gone. The catalog import's
re-used-name acknowledgement in ADAS covers what that leaves under the name on the import path.

#### Scenario: A first sighting records the pipeline

- **WHEN** a poll admits a pipeline the runner has no cursor row for
- **THEN** a row is inserted carrying only the pipeline's `created_at`, and the first scan starts from the beginning

#### Scenario: Adoption after deploy resets nothing

- **GIVEN** a cursor row written before the column existed
- **WHEN** the first poll after deploy admits its pipeline
- **THEN** the row records the feed's `created_at` and keeps its cursor

#### Scenario: Disable and re-enable keeps the state

- **WHEN** a pipeline leaves the feed and returns at a higher generation with the same `created_at`
- **THEN** its cursor and group state are unchanged

#### Scenario: Recreation across polls resets the state

- **GIVEN** a pipeline with a cursor, a computed group, queued work and a running and a paused backfill
- **WHEN** it leaves the feed and later returns with a different `created_at`
- **THEN** the cursor is empty, the groups and unpicked work items are gone, both runs are cancelled, a breaker pause
  is lifted, and the reset is logged and counted

#### Scenario: Recreation within one poll at the same generation resets the state

- **WHEN** consecutive polls report the same name at the same generation with different `created_at` values
- **THEN** the state is reset exactly as for a recreation across polls

#### Scenario: An operator pause survives the reset

- **GIVEN** an operator pause on a pipeline
- **WHEN** the pipeline is recreated
- **THEN** the pause is still in place after the reset

#### Scenario: A legacy row older than the pipeline is reset

- **GIVEN** a cursor row written before the column existed, last written before the pipeline now in the feed was
  created
- **WHEN** a poll admits that pipeline
- **THEN** the state is reset as for a recreation, and the row records the feed's `created_at`

#### Scenario: A second process's reset resets nothing

- **GIVEN** two processes that both read the old `created_at`, one of which has already reset the pipeline
- **WHEN** the other one runs its reset
- **THEN** the compare-and-set matches no row, and the new pipeline's cursor, groups and queued work are kept
