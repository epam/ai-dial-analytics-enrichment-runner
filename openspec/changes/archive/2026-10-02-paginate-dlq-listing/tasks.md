## 1. Schema

- [x] 1.1 Add `V11__dlq_listing_keyset.sql`. It rebuilds the pipeline and run listing indexes with an `id DESC` tie-breaker and adds `(created_at DESC, id DESC)`. Drop `dlq_item_created_at_idx`: the new `(created_at DESC, id DESC)` index serves the sweep.

## 2. Persistence and cursor

- [x] 2.1 Add the `DlqCounts(total, requeueable)` record. Add a counts query to `DlqItemRepository` with the same filter clause as the listing.
- [x] 2.2 Change `DlqItemRepository.list` to a keyset query (`createdAt DESC, id DESC`, optional after-cursor predicate). Split it into first-page and after-cursor methods if Postgres can't type a null `Instant`.
- [x] 2.3 Add a `DlqCursor` record (`createdAt`, `id`) with a versioned base64url `encode` and `decode`. `decode` throws `InvalidDlqCursorException`.
- [x] 2.4 Map `InvalidDlqCursorException` to HTTP 400 `invalid_cursor` in `DefaultExceptionHandler`.

## 3. Service and web

- [x] 3.1 Add `DlqListingService` (read-only transactional). It normalises the filters, refuses `lane=live` together with `run_id`, decodes the cursor, probes `limit + 1`, and returns the page with its counts.
- [x] 3.2 Extend `DlqListResponse` with `next_cursor`, `has_more`, `total` and `requeueable_total`, and update `DlqItemMapper`.
- [x] 3.3 `DlqController.list`: add the `cursor` parameter, with a springdoc `@Parameter` description, and delegate to the service.

## 4. Tests

- [x] 4.1 Add a `DlqCursor` unit test: round-trip at nanosecond precision; refuse garbage, the wrong version, malformed fields, and out-of-range nanos, instants and ids.
- [x] 4.2 Add `DlqListingIntegrationTest` (MockMvc). Cover walking to exhaustion with equal `created_at`, an item removed (as a requeue removes it) during the walk, counts that follow the filters but not the cursor, and 400 `invalid_cursor`.
- [x] 4.3 Keep the existing DLQ tests green (`DlqRunAttributionIntegrationTest`, `DlqRequeueIntegrationTest`, `JwtAuthIntegrationTest`).

## 5. Docs and verification

- [x] 5.1 `docs/e2e-runbook.md`: add a paging example to the DLQ section. Reword the javadocs that describe the DLQ listing as unpaged (`BackfillService`, `GroupAdminController`).
- [x] 5.2 `openspec validate paginate-dlq-listing --strict` passes.
- [x] 5.3 `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build` passes.
