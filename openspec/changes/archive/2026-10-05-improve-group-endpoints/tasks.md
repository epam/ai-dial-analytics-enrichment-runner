## 1. Schema and repository

- [x] 1.1 Add `V12__group_listing_keyset.sql`, which replaces `group_state_activity_idx` with `group_state_pipeline_name_activity_key_idx (pipeline_name, last_activity_at, group_key)`. Give it a header comment in the style of V11.
- [x] 1.2 In `GroupStateRepository`, replace `findByPipelineNameOrderByLastActivityAtAsc` with:
  - `findByPipelineName(String, Pageable)` for the first page (sorted by the caller)
  - the row-value keyset methods `listAfterAsc` and `listAfterDesc`
  - `countByPipelineName`

## 2. Listing service and cursor

- [x] 2.1 Add `group/GroupListOrder` (`OLDEST` and `NEWEST`, carrying `wireValue`/`fromWireValue` and the keyset's `Sort.Direction`), and register it in `WebMvcConfiguration` with the lenient converter.
- [x] 2.2 Add `group/GroupCursor` (`v1:<order>:<epochSecond>.<nanos>:<groupKey>` as unpadded base64url, decoded with `split(":", 4)`, an empty or blank key round-trips; base64url and timestamp handling shared with `DlqCursor` via `persistence/KeysetCursors`). Also add `exception/InvalidGroupCursorException` and, with `InvalidDlqCursorException`, give it an `InvalidCursorException` base mapped once to `400 invalid_cursor` in `DefaultExceptionHandler`.
- [x] 2.3 Add `group/GroupPage`, and `group/GroupListingService.list(pipelineName, limit, order, cursor)`:
  - clamp the limit to 1..500 (default 100)
  - read `limit + 1` rows to learn whether there is a next page
  - refuse a cursor whose order differs from the request's
  - read `total`
  - run as `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`

## 3. Web layer

- [x] 3.1 Give `GroupStateResponse` the fields `next_cursor`, `has_more` and `total`, and change `GroupStateMapper.toResponse` to take a `GroupPage`.
- [x] 3.2 Make `GroupAdminController.list` bind `limit`, `order` and `cursor` (with `@Parameter` docs) and delegate to `GroupListingService`. Remove the local `MAX_LIMIT`, and document `%2F` on `requeue`.
- [x] 3.3 Add `configuration/EncodedSlashConfiguration`, containing:
  - a Tomcat connector customizer that sets `EncodedSolidusHandling.DECODE` (route and auth matching use the raw URI)
  - an `HttpFirewall` bean, `EncodedSlashFirewall`: a `StrictHttpFirewall` with `allowUrlEncodedSlash` only, plus a refusal of any `.`/`..` segment in the percent-decoded request URI

## 4. Tests

- [x] 4.1 Add a `GroupCursorTest` unit test:
  - round trip, including keys containing `:` and `/`
  - round trip of an empty and a blank key
  - rejection of garbage, an unknown version, an unknown order, and nanos out of range
- [x] 4.2 Add `GroupListingIntegrationTest` (MockMvc):
  - the default is oldest first
  - `order=newest` and `order=NEWEST` both work
  - a walk with a tie on `last_activity_at` returns every group exactly once
  - `total` is stable across pages
  - the last page has no `next_cursor`
  - a cursor under the other order gives `invalid_cursor`
  - a garbage cursor gives `invalid_cursor`
  - `order=bogus` gives 400 `bad_request` naming the permitted values
  - a newest-first walk whose tied pair straddles a page boundary
  - `limit` 0, -1 and over 500 are clamped, not refused
- [x] 4.3 Add a real-Tomcat test (`RANDOM_PORT`) that requeues the key `team/a:b` through a pre-encoded `%2F` URI. It expects 202 and one group work item carrying the decoded key, and checks that the listing reports that key. A `none`-mode companion (`EncodedSlashFirewallIntegrationTest`) asserts the exact 400 for traversal and `%252F`, 400 for each documented unaddressable key, and 202 for the edge keys that stay addressable.
- [x] 4.4 Extend `OpsSignalsIntegrationTest.groupStateIsInspectableWithoutMemberContent` to assert `total` and `has_more`.

## 5. Docs and verification

- [x] 5.1 In `docs/e2e-runbook.md`, add a group listing paging/order example and a `%2F` requeue example (plain `curl`: it never decodes `%2F`).
- [x] 5.2 In `docs/kubernetes-exposure.md`, note that slash keys must be sent as `%2F`, that the ingress must pass `%2F` through, and which keys stay unaddressable.
- [x] 5.3 Run `openspec validate 2026-10-05-improve-group-endpoints --strict`.
- [x] 5.4 Run `./gradlew clean build`; it must pass.
