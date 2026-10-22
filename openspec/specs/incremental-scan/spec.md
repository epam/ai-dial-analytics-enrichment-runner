# incremental-scan Specification

## Purpose
Draining ADAS's pipeline-scoped keyset scan under a per-fire budget: sampling, input mapping, and committing
a page's work atomically with the cursor advance. What a page becomes depends on the pipeline's grain — chunk
work items for a row pipeline, per-group dirty marks for a group pipeline — while the drain loop, gates and
cursor handling are shared.
## Requirements
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
the group key for a group pipeline, so the decision is taken at the grain the work is keyed by. The
threshold SHALL come from the pipeline's `advanced.sample_fraction`; an absent fraction SHALL keep
everything. Raising the fraction SHALL only ever add work. Sampled-out rows SHALL still be considered
"seen" (the cursor advances past them).

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

For each kept row the runner SHALL extract the transform's input variables from the pipeline's
`transform.inputs` map. Each entry's **key** is the variable name and its value carries exactly one of a
bound `column` (value = `row[column]`) or a `jsonata` expression evaluated over the row.
The map's own keys are what drive substitution, so no variable is declared twice. A JSONata
evaluation failure SHALL dead-letter that row at stage `input_map` (never a silent drop), naming the
variable, while the rest of the page proceeds; a variable that resolves to nothing SHALL yield a null
value passed through.

Declaration order SHALL be preserved, since it is the order the rendered prompt interpolates.

#### Scenario: A bound column and an expression both resolve

- **GIVEN** a pipeline whose `transform.inputs` declares one variable bound to a column and one carrying
  a JSONata expression
- **WHEN** a row is mapped
- **THEN** the inputs carry both variables under their map keys, the first taking the row's column value
  and the second the expression's result

#### Scenario: An un-evaluable binding dead-letters only its row

- **GIVEN** a page of rows and a pipeline whose input variable fails to evaluate
- **WHEN** the page is processed
- **THEN** each failing row produces a `dlq_item` at stage `input_map` and no chunk is enqueued for
  it, while mappable rows are still chunked, and the cursor advances

### Requirement: Atomic enqueue and cursor advance

The runner SHALL commit a page's work, its dead-letter items, and the advance of `pipeline_cursor` to the
scan's `next_cursor` in a single transaction — all committing together or none. For a row-grain pipeline the
page's work is its chunk enqueues; for a group pipeline it is the page's `group_state` upserts. Chunk work
items SHALL use deterministic instance ids derived from the pipeline name, the pipeline's `generation` and
cursor-before, so a repeated scan of the same range does not create duplicate chunks; group state upserts
SHALL take the maximum of the existing and incoming values, so a repeated scan leaves the state unchanged.

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

Because the version component is the pipeline's `generation`, every deterministic instance id changes on
**any** declaration edit — prompt, expressions, `filter`, trigger, cadence, `rate_rpm` or
`batch_scan_limit`. The residual overlap window this dedup exists to suppress therefore no longer collapses
across such an edit, which is the same hazard the paragraph above states for a re-identification.

At the fold's cut-over the queue is **not** drained, so that hazard is live rather than avoided, and it is
what decides the group lane's handling of a carried-over work item. A group item enqueued before the fold
carries a generation of `0`, so its instance id cannot match the id the readiness sweep computes at the
pipeline's live `generation`, and the sweep's in-flight suppression therefore does not see it. Running
both would evaluate — and pay for — one group twice, which is why such an item SHALL be discarded rather
than adopted (see `enrichment-worker`). The row and write lanes do not have this problem: their items are
not re-enqueued by any sweep, so adopting one cannot duplicate work.

The page commit SHALL lock the pipeline's `pipeline_cursor` row before it enqueues or marks anything, and SHALL
refuse the commit, writing nothing, when the row records a different `created_at` from the one of the declaration the
scan ran under: the pipeline was recreated during the tick and the row now belongs to the new one (see the
`pipeline-sync` capability). Taking that lock first is what orders the commit against the reset, which takes the same
lock first. The scan SHALL then end the fire without recording a failure, and without its fire summary. A commit
whose declaration carries no `created_at`, or whose row records none, SHALL advance unchecked. The commit SHALL NOT
write the recorded `created_at` of an existing row; it SHALL stamp it only on a row it inserts.

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

#### Scenario: A page scanned for a recreated pipeline is refused

- **GIVEN** a pipeline reset because its name was recreated while a scan for the old pipeline was running
- **WHEN** that scan commits a page
- **THEN** the commit is refused, no chunk or mark from it persists, and the cursor stays empty

### Requirement: Defensive page and chunk sizing

The runner SHALL floor a pipeline's effective `advanced.rows_per_scan` and `advanced.rows_per_call` at 1,
so a pipeline configured with a zero or negative value cannot request a degenerate page or spin the
chunking loop.

The runner SHALL also **cap** a pipeline's effective `advanced.rows_per_scan` at `scan.max-scan-limit`
before sending it, mirroring what the Class-1 lane already does with `class1.max-limit`. ADAS answers an
over-limit scan with a `400` rather than clamping it, and it does not validate the knob at registration —
so a pipeline declaring a `rows_per_scan` above ADAS's `enrichment.scan.max-limit` is accepted by the
registry and then fails on **every** fire, and its lane never advances at all. The cap is applied on the
live scan and on a bounded backfill's pages alike, since both send the pipeline's declared page size.

An absent knob SHALL resolve to the runner's configured default for that lane, so a pipeline whose
declaration sets none behaves exactly as one that omitted the knob under its former name did. The
scan's clock is not one of these knobs: `advanced.scan_every` (once `cadence`) is retired, and the
schedule comes from the trigger (see pipeline-sync, "Recurring task reconciliation").

#### Scenario: A non-positive chunk size is clamped

- **GIVEN** a pipeline whose `advanced.rows_per_call` is zero or negative and a page with rows
- **WHEN** the page is chunked
- **THEN** chunking uses a size of at least 1 and terminates without looping

#### Scenario: A declared page size above ADAS's ceiling is capped rather than sent

- **GIVEN** a pipeline whose `advanced.rows_per_scan` exceeds `scan.max-scan-limit`
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

### Requirement: A page that does not carry a column the lane requires is refused, not skipped

The runner SHALL distinguish a scanned row that **does not carry** a required column from one that carries
it with a null value, and SHALL treat the two differently:

- A row **missing the key** for the pipeline's `grain_key` (row lane), `trigger.group_by` (group lane) or
  the `version_column` the group lane reads alongside it means the page is not the projection this lane
  asked for. The runner SHALL fail the tick, naming the pipeline and the missing column at ERROR, SHALL
  leave the cursor untouched, and SHALL retry on the next cadence. It SHALL NOT dead-letter the page's
  rows, because the defect is in the page, not in the rows.
- A row carrying the key with a **null value** is data, and SHALL keep the outcome that column already
  has: a null grain or group key SHALL continue to be skipped, with the skip counted by reason (see the
  `observability` capability) rather than being invisible, and a version value that is null or cannot be
  read as an instant SHALL continue to be dead-lettered, because a group whose activity time is unknowable
  must surface rather than silently never fire.

Naming the version column here matters as much as the other two, and for the sharper reason: its absent
key is *already* handled, by dead-lettering the row. A page whose version column is simply not in the
projection therefore dead-letters every row of every page — tens of thousands of items that are not row
defects, recoverable only by bulk requeue. Failing the tick replaces that with a retry.

The distinction is the difference between a stall and a loss. A missing key is what a projection skew
looks like from inside the scan: the request succeeded, the rows are real, and every one of them silently
yields nothing to chunk or mark — the failure mode that drained a whole backlog while lag fell to zero.
Failing the tick makes that a loud stall that recovers by itself once the page is right, and it holds the
line this capability already draws, that no cursor is advanced past unprocessed rows.

#### Scenario: A page without the grain column fails the tick and holds the cursor

- **GIVEN** a row-grain pipeline whose scan page carries rows that do not carry its `grain_key` at all
- **WHEN** the scan tick processes the page
- **THEN** the tick fails naming the pipeline and the missing column, the `pipeline_cursor` row is
  unchanged, no chunk is enqueued and no dead-letter item is written

#### Scenario: A group page without the group-by column fails the tick

- **GIVEN** a group pipeline whose scan page carries rows that do not carry its `trigger.group_by`
- **WHEN** the page is processed
- **THEN** the tick fails, no group state is upserted, and the cursor is unchanged

#### Scenario: A group page without the version column fails the tick rather than flooding the DLQ

- **GIVEN** a group pipeline whose scan page carries rows that do not carry its `version_column`
- **WHEN** the page is processed
- **THEN** the tick fails and the cursor is unchanged, and **no** dead-letter item is written for any row
  of the page

#### Scenario: An unreadable version value in a present column is still dead-lettered

- **GIVEN** a group page whose rows carry the version column with a null or non-instant value
- **WHEN** the page is processed
- **THEN** those rows are dead-lettered as before, the remaining rows are marked, and the cursor advances

#### Scenario: A null value in a present column is still a counted skip

- **GIVEN** a page whose rows carry the grain column with a null value in some of them
- **WHEN** the page is processed
- **THEN** those rows are skipped and counted by reason, the remaining rows are chunked, and the cursor
  advances

### Requirement: The cursor SHALL NOT advance over a page whose rows are not all accounted for

A page of one or more source rows SHALL account for every row it read as exactly one of: chunked (or
group-marked), dead-lettered, or skipped for a declared reason — sampling, or a null value in a required
column. When the accounted count does not **equal** the page's row count, the runner SHALL NOT commit the
cursor advance: it SHALL fail the tick, count the refused page, and leave the position where it was.

Equality rather than "at least one accounted": the page builders route every row they read to exactly one
of those outcomes, so equality is what the code already satisfies and costs nothing to assert, while the
weaker form would leave a *partially* unaccounted page — a loop that exits early, a branch that forgets to
count — exactly as invisible as the wholly unaccounted one this rule exists to catch.

This is the structural form of the rule the capability already states, and it is deliberately blind to the
cause. Every silent-skip defect this service has had — and any future one — reaches the same place: a page
of real rows that produced no work item and no dead letter, committed together with an advance that cannot
be undone. Refusing that commit converts the whole class from a permanent loss into a retry, without the
committer needing to know which invariant was broken upstream. The dead-letter circuit breaker cannot serve
this purpose because it is only consulted when something *was* dead-lettered, so a page that produced
nothing at all never reaches it.

#### Scenario: A wholly unaccounted page does not advance the cursor

- **GIVEN** a scan page carrying rows, none of which produced a chunk, a group mark, a dead-letter item or
  a counted skip
- **WHEN** the page would be committed
- **THEN** no cursor advance is persisted, the tick fails, and the refused page is counted

#### Scenario: A partially unaccounted page does not advance either

- **GIVEN** a scan page whose accounted outcomes cover some but not all of its rows
- **WHEN** the page would be committed
- **THEN** no cursor advance is persisted, the tick fails, and the refused page is counted

#### Scenario: A page accounted for entirely by declared skips still advances

- **GIVEN** a page whose every row was excluded by sampling, or skipped for a null in a required column
- **WHEN** the page is committed
- **THEN** the cursor advances, because every row is accounted for

#### Scenario: An empty page is unaffected

- **WHEN** a scan returns no rows
- **THEN** the commit behaves exactly as it does today, since there is nothing to account for
