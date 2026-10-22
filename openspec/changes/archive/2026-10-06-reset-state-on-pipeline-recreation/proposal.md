## Why

ADAS identifies a pipeline by `name`, and a delete followed by a create under the same name (a catalog import, or a
plain `DELETE` + `POST`) is a new pipeline. The runner keys all of its state on the name and never deletes any of it,
so the new pipeline inherits the old one's state:

- `pipeline_cursor`: the new pipeline resumes from the old scan position against an empty target and silently skips
  history.
- `group_state`: groups already computed under the old pipeline count as done and are never evaluated.
- Queued work items: they pass the workers' stale check whenever the generations coincide, which they often do.
- Active backfill runs: they can carry on, or be resumed, against the new pipeline.
- In-memory state: breaker pauses, gate state and the last fire outcome.

`generation` cannot detect a recreation: it restarts at 1 and often climbs back to the old value. ADAS's `created_at`
can, because ADAS sets it once at registration. It is already on every feed entry and pinned by ADAS's
`RunnerContractShapeTest`. This change follows up ADAS's catalog export and import, which can recreate a pipeline under
its old name.

## What Changes

- The runner reads `created_at` from the feed (`PipelineDto.createdAt`, truncated to microseconds).
- A Flyway migration `V13__pipeline_cursor_created_at.sql` adds a nullable `pipeline_cursor.pipeline_created_at`.
- On every poll, before scheduling, the sync compares the recorded value with the feed's value for every admitted
  pipeline:
  - **None recorded:** it adopts the feed's value and resets nothing. This also inserts a row on a pipeline's first
    sighting.
  - **Equal:** it does nothing.
  - **Different:** it resets the name, in one transaction:
    - cancels running and paused backfills;
    - deletes the name's `group_state` rows;
    - replaces the cursor row with an empty one;
    - deletes unpicked work items, including backfill items of the name's runs.

    It then evicts in-memory state, lifts a breaker pause (an operator pause is kept), and reschedules the recurring
    instance.
- All three live cursor commits (row scan, group dirty-mark, Class-1) go through one guarded write that locks the row.
  The write refuses a commit whose `created_at` differs from the row's, so a tick that spans a reset cannot write
  the old position back. The executors end the fire quietly on that refusal.
- New WARN line and untagged counter `runner.pipeline.state.resets`.
- No ADAS code change and no contract bump. ADAS docs say the runner now depends on `created_at` being immutable.

## Capabilities

### Modified Capabilities

- `pipeline-sync`: new requirement "A recreated pipeline starts from empty runner state".
- `incremental-scan`: the cursor commit refuses a page scanned for a recreated pipeline.
- `class1-enrichment`: the same for a materialized window.
- `service-runtime`: `pipeline_cursor.pipeline_created_at`, and the migration list through V13.
- `observability`: the reset line and counter.

## Impact

- Kept under the name by design, and covered by ADAS's re-used-name acknowledgement:
  - dead letters and same-day spend;
  - work items already executing at reset time.
- Rollout: runner only, with no window. The first sync after deploy adopts every enabled pipeline without resetting; a row left unadopted, its pipeline then disabled or deleted, is reset later only if it predates the pipeline that comes back under its name.
