## RENAMED Requirements

- FROM: `### Requirement: Backpressure and rule-lifecycle guard`
- TO: `### Requirement: Backpressure and pipeline-lifecycle guard`

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
re-evaluate the pause, spend-budget, and backpressure gates and stop the drain early when any trips (so a
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

- **GIVEN** a multi-page drain where a page causes the pipeline to become paused, over budget, or backpressured
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

### Requirement: Deterministic, monotone sampling

The runner SHALL decide inclusion by `keep ⇔ hash64(key) mod 100 < threshold`, using a portable 64-bit
hash so the decision is stable across re-scans. The key SHALL be the grain key for a row-grain pipeline and
the group key for a group pipeline, so the decision is taken at the grain the work is keyed by. A null
`sampling` SHALL keep everything. Raising `sampling` SHALL only ever add work. Sampled-out rows SHALL
still be considered "seen" (the cursor advances past them).

#### Scenario: Sampling is stable and monotone

- **WHEN** a key is kept at a lower sampling fraction
- **THEN** it is also kept at any higher fraction, and the same key yields the same decision on every
  evaluation

#### Scenario: A group pipeline samples by group key

- **GIVEN** a group pipeline with a sampling fraction, and a page whose rows have differing grain keys but
  one shared group key
- **WHEN** the page is processed
- **THEN** the whole group is kept or dropped together, according to the group key's decision

### Requirement: Input mapping with per-row dead-lettering

For each kept row the runner SHALL extract the evaluator input variables from the pipeline's input
bindings — a bound column value or a JSONata expression over the row. A JSONata evaluation failure
SHALL dead-letter that row at stage `input_map` (never a silent drop) while the rest of the page
proceeds; a binding that resolves to nothing SHALL yield a null value passed through.

#### Scenario: An un-evaluable binding dead-letters only its row

- **GIVEN** a page of rows and a pipeline whose input binding fails to evaluate
- **WHEN** the page is processed
- **THEN** each failing row produces a `dlq_item` at stage `input_map` and no chunk is enqueued for
  it, while mappable rows are still chunked, and the cursor advances

### Requirement: Atomic enqueue and cursor advance

The runner SHALL commit a page's work, its dead-letter items, and the advance of `pipeline_cursor` to the
scan's `next_cursor` in a single transaction — all committing together or none. For a row-grain pipeline the
page's work is its chunk enqueues; for a group pipeline it is the page's `group_state` upserts. Chunk work
items SHALL use deterministic instance ids derived from the pipeline name, evaluator version and cursor-before,
so a repeated scan of the same range does not create duplicate chunks; group state upserts SHALL take the
maximum of the existing and incoming values, so a repeated scan leaves the state unchanged.

The identity component of every deterministic instance id SHALL be the pipeline **name**, carried both as a
literal `#`-delimited prefix and inside the hashed basis. This depends on ADAS refusing a `#` in a pipeline
name at registration (`^[a-z][a-z0-9_-]{0,63}$`): the delimiter is what keeps one pipeline's prefix from
matching another's work, and the names `a#b` and `a` would otherwise share the prefix `a#`. The runner SHALL
rely on that guarantee rather than re-validating the name (see the `pipeline-sync` admission gate), and the
grammar's other exclusions — uppercase and a leading `_` — carry no meaning here.

Because the identity is in the hashed basis and not only the prefix, every deterministic work-item id changes
if a pipeline's identity changes. Re-identifying a pipeline is therefore an operation that SHALL be performed
only against a drained queue, since `scheduleIfNotExists` cannot dedup an item enqueued under a former
identity against the same work enqueued under the new one.

#### Scenario: Enqueue and cursor advance commit together

- **WHEN** a page commits successfully
- **THEN** the page's chunks are enqueued and the cursor is advanced to `next_cursor`

#### Scenario: A failed commit rolls back both

- **WHEN** the commit fails (e.g. a DLQ write is rejected)
- **THEN** neither the chunks nor the cursor advance are persisted

#### Scenario: Repeated scan of the same range does not duplicate chunks

- **GIVEN** a page already committed for a cursor-before
- **WHEN** the same range is scanned again at the same cursor-before
- **THEN** the deterministic instance ids dedup and no additional chunks are enqueued

#### Scenario: A group page's state and cursor commit together

- **WHEN** a group pipeline's page commits successfully
- **THEN** the page's group state is persisted and the cursor is advanced, or on failure neither is

### Requirement: Defensive page and chunk sizing

The runner SHALL floor a pipeline's effective `batch_scan_limit` and `batch_chunk` at 1, so a pipeline
configured with a zero or negative value cannot request a degenerate page or spin the chunking loop.

The runner SHALL also **cap** a pipeline's effective `batch_scan_limit` at `scan.max-scan-limit` before
sending it, mirroring what the Class-1 lane already does with `class1.max-limit`. ADAS answers an
over-limit scan with a `400` rather than clamping it, and it does not validate the knob at registration —
so a pipeline declaring a `batch_scan_limit` above ADAS's `enrichment.scan.max-limit` is accepted by the
registry and then fails on **every** fire, and its lane never advances at all. The cap is applied on the
live scan and on a bounded backfill's pages alike, since both send the pipeline's declared page size.

#### Scenario: A non-positive chunk size is clamped

- **GIVEN** a pipeline whose `batch_chunk` is zero or negative and a page with rows
- **WHEN** the page is chunked
- **THEN** chunking uses a size of at least 1 and terminates without looping

#### Scenario: A declared page size above ADAS's ceiling is capped rather than sent

- **GIVEN** a pipeline whose `batch_scan_limit` exceeds `scan.max-scan-limit`
- **WHEN** a scan fire resolves the page size
- **THEN** the request carries `scan.max-scan-limit` rather than the declared value, so the scan is
  served instead of being refused with a `400` on every fire

### Requirement: Backpressure and pipeline-lifecycle guard

The runner SHALL skip a scan fire entirely (no scan, no enqueue, no cursor advance) when the pipeline is
no longer present in the pipeline cache, or when the pipeline's in-flight (pending + delayed) work depth
exceeds `scan.max-inflight`.

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

#### Scenario: An underscore in a pipeline name does not broaden the depth count

- **GIVEN** two pipelines named `a_b` and `axb`, each with work in flight
- **WHEN** the depth of `a_b` is counted by its instance-id prefix
- **THEN** only `a_b`'s own work is counted, because the `_` is escaped rather than matching as a
  single-character wildcard
