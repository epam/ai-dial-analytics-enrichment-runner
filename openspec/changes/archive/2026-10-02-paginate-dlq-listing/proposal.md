## Why

The DLQ UI shows a pipeline's dead letters, but `GET /v1/dlq` can only return the first page (newest first, capped by
`limit` at 1000), and it gives no indication of how many items match or how many of them can be retried. A pipeline
with more dead letters than fit on one page therefore cannot be browsed, and the UI has no way to show "N failed, M
requeueable" without downloading everything. The listing's ordering also has no tie-breaker: items archived in the
same instant come back in an undefined order. Any form of paging would have to fix that first.

## What Changes

- `GET /v1/dlq` accepts an opaque `cursor` query parameter and pages by keyset over `(created_at DESC, id DESC)`.
  The response gains `next_cursor` (absent on the last page) and `has_more`.
- The response gains `total` (all items that match the filters) and `requeueable_total` (the subset whose item-level
  `requeueable` flag is true). Both counts ignore the cursor, so every page reports the same values.
- A cursor that cannot be decoded is refused with HTTP 400 and the error code `invalid_cursor`.
- The listing order becomes a total order, `created_at DESC, id DESC`, and the spec now states the `limit` default
  and bound, which until now existed only in code.
- A Flyway migration rebuilds the DLQ listing indexes with an `id` tie-breaker.
- The change is additive. The `items` field and the `pipeline_name`, `run_id`, `lane` and `limit` parameters are
  unchanged, so existing callers keep working.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `resilience-and-dlq`: adds a requirement covering the DLQ listing's pagination (cursor, ordering, `limit`) and its
  embedded counters.

## Impact

- **API:** `GET /v1/dlq` gets an additive change to its response envelope and a new `cursor` parameter. It also gets
  a new 400 `invalid_cursor` error.
- **Code:**
  - `DlqController`
  - a new `DlqListingService` and `DlqCursor`
  - `DlqItemRepository` (keyset list and counts query)
  - `DlqListResponse`, `DlqItemMapper` and `DefaultExceptionHandler`
- **Schema:** `V11__dlq_listing_keyset.sql`. It touches only indexes; no data changes.
- **Docs:** a paging example in `docs/e2e-runbook.md`. The javadocs that describe the DLQ listing as unpaged need
  rewording.
- **Config:** no new properties.
