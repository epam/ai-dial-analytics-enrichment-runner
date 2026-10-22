## Context

`DlqController.list` calls `DlqItemRepository.list` with `PageRequest.of(0, clamp(limit, 1, 1000))`. That is a JPQL
query with optional `pipeline_name`, `run_id` and `lane` filters, `ORDER BY createdAt DESC`. The response is
`DlqListResponse(items)`, an envelope that already exists "so fields can be added later". Each item's
`requeueable` flag is `payload IS NOT NULL` (`DlqItemMapper`).

The DLQ indexes are:
- `(pipeline_name, created_at DESC)`
- `(created_at)`, which the retention sweep uses
- a partial `(run_id, created_at DESC) WHERE run_id IS NOT NULL`

None of them has an `id` tie-breaker.

ADAS has two paging conventions:
- offset paging with totals (`PageDto`, camelCase), used by `POST /v1/activities`;
- an opaque keyset cursor with `next_cursor`/`has_more`, used by the machine-facing scans.

The runner's DLQ wire is snake_case with query-parameter filters, and it was agreed to use the keyset form with the
counters embedded in each page.

## Goals / Non-Goals

**Goals:**
- Let the UI walk a pipeline's DLQ to the end without gaps or duplicates, even while items are being archived or
  requeued at the same time.
- Report `total` and `requeueable_total` for the current filters on every page.
- Keep the change additive for existing callers.

**Non-Goals:**
- Jumping to an arbitrary page number, or walking backwards. The UI can keep a stack of the cursors it has seen.
- Sorting or filtering on anything beyond what the listing already offers.
- Making `requeueable` account for pre-fold payloads (see Risks).
- Signing cursors.

## Decisions

### Keyset over `(created_at, id)`, not offset
An offset page shifts whenever rows ahead of it are requeued or swept, and on a DLQ that happens all the time. A
requeue deletes rows, so an offset walk would skip items. A keyset walk is immune to deletions. New items sort before
the cursor, so a forward walk never repeats a row. The `id` tie-breaker makes the order total. The DLQ writer's
upsert never refreshes `created_at`, so an item's position is stable for as long as it exists.

### Cursor encoding: unsigned, versioned, opaque
The cursor is `base64url(no padding)` of `v1:<epochSecond>.<nanos>:<id>`.

It is not signed. The ADAS scan cursor is signed with an HMAC because it crosses a service trust boundary and drives
paid work. A DLQ cursor only repositions a read-only listing that the caller can already read in full, so a forged
cursor gains the caller nothing.

The cursor is still opaque, and it carries a version prefix so the encoding can change later. A value that fails to
decode, carries an unknown version, or has malformed fields is answered with 400 `invalid_cursor`, the same code ADAS
uses. Nanoseconds are encoded in full, so an `Instant` read back from Postgres (microsecond precision) round-trips
exactly. Decoding accepts only what encoding can emit: nanos in `0..999999999`, a year in `0001..9999` and a
positive id. Anything wider would either be normalised silently by `Instant` or reach PostgreSQL outside its
`timestamptz` range and fail there as a 5xx rather than a 400.

The cursor is not bound to the filters. Applying a cursor under different filters is well defined: it means "older
than this position". Binding it would add state for no benefit.

### `limit + 1` probe for `has_more`
The page query fetches one extra row. If that row exists, `has_more` is true, the extra row is dropped, and
`next_cursor` encodes the last row that is returned. On the last page `next_cursor` is absent: the runner's JSON
mapper omits null fields, and `has_more` is the signal to stop. This differs from the
ADAS scan, which echoes the input cursor; the scan needs that to resume tailing, while for a UI "no cursor" is clearer.

### Counts embedded and filter-scoped
One JPQL aggregate returns
`COUNT(d)` and `COALESCE(SUM(CASE WHEN d.payload IS NOT NULL THEN 1 ELSE 0 END), 0)` under the same filter clause,
without the cursor predicate. It is projected into `DlqCounts(total, requeueable)`. Each page therefore costs two
queries. On a DLQ of operator scale (retention 30 days, circuit breaker at 500 per pipeline) the index-backed count
is cheap.

`requeueable_total` uses exactly the rule behind the item flag, so the badge and the rows can never disagree.

### A listing service
The filter normalisation, the `lane=live` + `run_id` refusal, decoding the cursor, the probe, and the counts move
into `dlq/DlqListingService`, which runs `@Transactional(readOnly = true)`. The controller is left with binding only,
like the requeue endpoints, which already delegate to `DlqRequeueService`.

### Query shape
There are two repository methods sharing one filter constant (`LISTING_FILTER`) and one order constant
(`created_at DESC, id DESC`):
- `list` reads the first page.
- `listAfter` reads the pages after a cursor. It adds the row-value comparison
  `AND (d.createdAt, d.id) < (:afterAt, :afterId)`.

A single method with an optional `:afterAt IS NULL OR …` was rejected: Postgres cannot infer a type for a null
`Instant` that is bound only under `IS NULL`. The row-value comparison is used rather than the expanded
`createdAt < :afterAt OR (createdAt = :afterAt AND id < :afterId)`: Hibernate's HQL renders it natively on
PostgreSQL, which turns it into an index condition that starts the scan at the cursor, whereas the `OR` form
filters every newer row. The counts query reuses the same filter constant, so the counts and the pages
cannot drift apart.

### Indexes (V11)
- Replace `(pipeline_name, created_at DESC)` with `(pipeline_name, created_at DESC, id DESC)`.
- Replace the partial run index with `(run_id, created_at DESC, id DESC) WHERE run_id IS NOT NULL`.
- Add `(created_at DESC, id DESC)` for the unfiltered listing.
- Drop the V2 `(created_at)` index. The new `(created_at DESC, id DESC)` index serves the sweep's
  `created_at < cutoff` range on its leading column just as well, and every archive maintains one index fewer.

The DLQ is small, so a plain `CREATE INDEX` (which blocks writes to `dlq_item` while it builds) inside Flyway's transaction is acceptable.

## Risks / Trade-offs

- **Pre-fold payloads.** A payload with no `pipelineGeneration`, or with one equal to 0, lists as requeueable and
  counts towards `requeueable_total`, but requeueing it is refused with 422. This is already true of the item flag
  today. Such rows come from a build that predates the pipeline-generation fold (swept within the retention window) and from a
  write item archived while its pipeline was absent from the cache. Mitigation:
  document it in the spec; don't parse JSON inside the count.
- **Counts and the page share one snapshot.** Under READ COMMITTED each statement would take its own snapshot,
  so an archive or requeue landing between the two queries could leave a page disagreeing with its own `total`.
  The service runs at REPEATABLE READ, which costs nothing on a read-only transaction. Counts can still move
  between pages of one walk.
- **Items archived during a walk are not returned by it.** `created_at` is the archiving transaction's start
  time, so a new item sorts ahead of the cursor; one whose transaction started before the cursor's position but
  committed after the walk passed it is missed too. The spec promises exactly-once only for items that exist for
  the whole walk; a fresh walk sees the rest.
- **Two queries on every page.** If this ever matters, the UI can ignore the counts on later pages, and a future
  change could skip them when a cursor is present. That isn't worth doing now.

## Migration Plan

V11 only drops and recreates indexes, and the change is additive on the wire. To roll back, deploy the previous
build. It ignores the new indexes, which serve its queries equally well. No flag is needed.
