## MODIFIED Requirements

### Requirement: Draining incremental scan

When a pipeline's recurring task fires, the runner SHALL drain scan pages up to a per-fire budget
(`scan.max-pages-per-fire`). Each page SHALL be fetched from ADAS `POST /v1/pipelines/{name}/scan` with the
pipeline's persisted cursor, and the scan SHALL continue while `has_more` is true and the budget is not
exhausted. The request MAY additionally carry a `[from, to]` version window, which bounds the keyset rather
than replacing the cursor — the live tick leaves it absent and a bounded backfill supplies it. The pipeline's
cursor SHALL be read from `pipeline_cursor` at the start of the drain; a pipeline with no stored cursor SHALL scan
from the source start. The scan HTTP call SHALL have no local side effects (it precedes the commit and is safe
to repeat). How a page is turned into committed work SHALL depend on the pipeline's trigger kind: a row-grain
pipeline's page becomes chunk work items, and a group pipeline's page becomes per-group state — the drain loop,
budget, gates and cursor handling are identical for both. After each committed page the runner SHALL
re-evaluate the stop, pause, spend-budget, and backpressure gates and stop the drain early when any trips (so a
mid-fire trip stops within one page, not one whole fire). The runner SHALL also stop the drain when a page
reports `has_more` but does not advance the cursor (an unchanged, absent or blank next cursor), rather than
re-scanning the same range or restarting from the source.

Stopping the loop is not sufficient on its own: an absent or blank `next_cursor` SHALL NOT overwrite the
stored cursor, because the page commit lands **before** the loop's guard is evaluated, and a stored cursor of
null does not mean "no progress" — it means the source start. Every lane's commit SHALL therefore keep the
cursor it holds when the response carries no new position.

#### Scenario: A fire drains a single available page

- **GIVEN** a cached pipeline and a scan page with `has_more=false`
- **WHEN** the pipeline's scan fires
- **THEN** exactly one page is scanned and its work is committed

#### Scenario: A mid-drain gate trip stops the fire

- **GIVEN** a multi-page drain where a page causes the pipeline to become paused, over budget, or backpressured,
  or a sync caches the pipeline as stopped mid-drain
- **WHEN** the next page would be scanned
- **THEN** the drain stops without scanning or enqueuing further pages in that fire

#### Scenario: A non-advancing cursor halts the drain

- **GIVEN** a page that reports `has_more` but returns the same (or a null) cursor
- **WHEN** the drain evaluates the next page
- **THEN** the drain stops and logs, rather than re-scanning the same range or resetting to the start

#### Scenario: A page without a next cursor leaves the stored cursor intact

- **GIVEN** a pipeline whose stored cursor is non-null
- **WHEN** a page returns no `next_cursor`
- **THEN** the stored cursor is unchanged, so the following fire resumes where the pipeline left off rather than
  re-scanning the source from the start

#### Scenario: A group pipeline's page becomes group state

- **GIVEN** a cached group pipeline and a scan page carrying its group key and version column
- **WHEN** the pipeline's scan fires
- **THEN** the page's groups are marked in `group_state` and no chunk work items are enqueued

#### Scenario: A windowed scan bounds the keyset

- **GIVEN** a scan request carrying a `[from, to]` window
- **WHEN** the page is fetched
- **THEN** the window is sent alongside the cursor, and the cursor still governs progress within it

### Requirement: Backpressure and pipeline-lifecycle guard

The runner SHALL skip a scan fire entirely (no scan, no enqueue, no cursor advance) when the pipeline is no longer
present in the pipeline cache, when it is cached as stopped, or when the pipeline's in-flight (pending + delayed)
work depth exceeds `scan.max-inflight`.

Depth SHALL be counted over **every** work-item task a pipeline's ids are prefixed onto — the row chunk, the
group item and the write — not the chunk alone. Counting one of them would report a pipeline whose writes are
backed up behind a slow write-back as idle, and keep enqueuing into a queue that is not draining. The
readiness sweep SHALL apply the same gate, since it, and not the dirty-mark, is what enqueues group work.

#### Scenario: A hot pipeline is throttled

- **WHEN** a pipeline's in-flight work depth exceeds the configured maximum
- **THEN** the scan fire is skipped without scanning or advancing the cursor

#### Scenario: Backed-up writes throttle the pipeline that produced them

- **GIVEN** a pipeline whose pending work is entirely write items, with no chunk queued
- **WHEN** its depth exceeds the configured maximum and the pipeline's scan fires
- **THEN** the fire is skipped, because depth spans every work task rather than the chunk alone

#### Scenario: A removed pipeline does not scan

- **WHEN** a scan fires for a pipeline absent from the cache
- **THEN** nothing is scanned, enqueued, or advanced

#### Scenario: A stopped pipeline does not scan

- **WHEN** a scan fires for a pipeline cached as stopped, before the sync's cancellation of its instance lands
- **THEN** nothing is scanned, enqueued, or advanced, and its stored cursor is kept for the restart

#### Scenario: An underscore in a pipeline name does not broaden the depth count

- **GIVEN** two pipelines named `a_b` and `axb`, each with work in flight
- **WHEN** the depth of `a_b` is counted by its instance-id prefix
- **THEN** only `a_b`'s own work is counted, because the `_` is escaped rather than matching as a
  single-character wildcard
