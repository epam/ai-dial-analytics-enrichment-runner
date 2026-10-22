## Context

`GroupAdminController.list` calls `GroupStateRepository.findByPipelineNameOrderByLastActivityAtAsc` with
`PageRequest.of(0, clamp(limit, 1, 500))` and returns `GroupStateResponse(groups)`. It is one page only, oldest
activity first. The order is not total, because groups that share a `last_activity_at` come back in an undefined
order. Its only index is `group_state_activity_idx (pipeline_name, last_activity_at)` from V3 (its column was renamed
by V8). The sweep's idle predicate, the sweep's `ORDER BY last_activity_at` and eviction also read that index.

The DLQ listing was paged in `2026-10-02-paginate-dlq-listing`:
- a keyset over a total order;
- an unsigned, versioned base64url cursor;
- a `limit + 1` probe;
- counts embedded in each page and read on the same `REPEATABLE_READ` snapshot;
- a listing service, so the controller only binds parameters.

This change reuses that design wholesale. The decisions below are only where the group listing differs.

`POST …/groups/{groupKey}/requeue` binds `{groupKey}` as one path segment. A key containing `/` is refused today by:
- **Tomcat's connector**: `encodedSolidusHandling` defaults to `reject`, which answers 400.
- **Spring Security's `StrictHttpFirewall`**: `allowUrlEncodedSlash` defaults to false, which answers 400.

Spring Boot 4.0.7 offers no `server.tomcat.*` property for the former.

## Goals / Non-Goals

**Goals:**
- Walk a pipeline's groups to the end, in either direction, with a total that tells the operator how far there is to
  go.
- Keep today's response (`groups`, oldest first, `limit` 100 and at most 500) as the default, adding fields only.
- Make every listed group key addressable by the requeue endpoint.

**Non-Goals:**
- Filtering the listing, for example to dirty groups only, or sorting by anything other than activity.
- A `dirty_total` counter. It can be added on the same snapshot later if the UI asks for it.
- An exactly-once walk while groups are active (see Risks).
- Accepting raw, unencoded slashes in the key. That would need a catch-all path and a breaking URL change, which was
  rejected.

## Decisions

### Keyset over `(last_activity_at, group_key)`, both directions
`group_key` is unique within a pipeline, since it is part of the PK `(pipeline_name, group_key)`, so it makes the
order total. Each direction needs two queries:
- **First page**: `findByPipelineName(name, Pageable)`, with
  `Sort.by(direction, "lastActivityAt", "groupKey")` carried on the `PageRequest`.
- **After a cursor**: one JPQL method per direction, with the row-value comparison
  `(g.lastActivityAt, g.groupKey) > (:afterAt, :afterKey)` (asc) or `<` (desc), plus a matching
  `ORDER BY`. There are two methods because neither the operator nor the sort direction can be bound as a
  parameter. They have the same split as `DlqItemRepository.list`/`listAfter`, for the same reason: a null
  `Instant` bound only under `IS NULL` gives PostgreSQL no type to infer.

### The cursor carries its order
`GroupCursor` encodes as unpadded base64url of `v1:<order>:<epochSecond>.<nanos>:<groupKey>`. It is decoded with
`split(":", 4)`, so a key that contains `:` or `/` survives, and so does an empty or blank key: any key a page can
end on must yield a cursor the listing accepts back. The base64url wrapping and the `<epochSecond>.<nanos>` timestamp
with its range checks are shared with `DlqCursor` through `persistence/KeysetCursors`; the DLQ wire format is
unchanged.

Unlike the DLQ cursor, this one is bound to its `order`. Under the opposite direction, "past this group" selects the
complementary set of groups, so the walk would silently restart and repeat every group already seen. A mismatch is
answered with 400 `invalid_cursor`. The cursor is not signed, for the DLQ's reason: it only repositions a read-only
listing the caller can already read.

### `order` vocabulary
The values are `oldest` and `newest`, rather than `asc`/`desc` or a `sort=` field list. They name the operator's
question directly, and there is only one sort field. `GroupListOrder` carries its wire spelling and its `Sort.Direction`
(`getWireValue`, `fromWireValue`, `getDirection`), so the listing service branches on the direction rather than on
the enum constant, and the existing lenient converter in `WebMvcConfiguration` binds it case-insensitively. The default
is `oldest`, which preserves today's response for callers that pass no parameters.

### Exception type
There is a new `InvalidGroupCursorException`, mapped to the same `invalid_cursor` code as the DLQ. Its message tells
the caller to omit the cursor to list from the first page. It and `InvalidDlqCursorException` extend an abstract
`InvalidCursorException(message)`, so `DefaultExceptionHandler` has one `invalid_cursor` handler and each listing
keeps its own message.

### Index (V12)
Replace `group_state_activity_idx (pipeline_name, last_activity_at)` with
`group_state_pipeline_name_activity_key_idx (pipeline_name, last_activity_at, group_key)`. Both directions become
an index range scan, because PostgreSQL scans a b-tree backwards for `newest`. The sweep's idle predicate, its oldest-first ordering
and eviction keep using the leading columns. `group_state` is runner-owned and bounded by eviction, so a plain
`CREATE INDEX` inside Flyway's transaction is acceptable. The upserts are briefly blocked while it builds.

### Encoded slash: decode for the servlet path, match on the raw URI
- **Tomcat**: a `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` sets `EncodedSolidusHandling.DECODE`.
  Decoding only affects the servlet path. Spring MVC's `PathPatternParser`, and the `PathPatternRequestMatcher`
  behind the authorization rules, both parse the raw request URI. In the raw URI `team%2Fa` is still one segment,
  and `@PathVariable` binds its value as `team/a`. An encoded slash therefore cannot change which route or rule
  matches.
- **Spring Security**: an `HttpFirewall` bean, `EncodedSlashFirewall`: a `StrictHttpFirewall` with
  `setAllowUrlEncodedSlash(true)` and nothing else loosened, plus one added check. `WebSecurity` picks it up in both
  the `none` and `oidc` security modes. Two things are refused before routing, with the firewall's 400 (which reads as
  401 in `oidc` mode, because the error dispatch carries no credential):
  - a slash that would climb out of the segment (`x%2F..%2Fcache`). The stock normalisation check cannot see it: the
    raw URI has no literal `/../`, and Tomcat has already normalised the decoded servlet path. The added check
    percent-decodes the request URI (without turning `+` into a space) and refuses any `.` or `..` segment. Review
    found the stock firewall alone let this through to routing, where it was answered 404 as an unknown key;
  - double encoding (`%252F`), because it leaves a literal `%` in the decoded path.
- **Keys that stay unaddressable.** The stock checks still refuse, with 400, the empty key (`//`), the keys `.` and
  `..`, keys containing `//`, `%`, `;`, a line feed, a carriage return, U+2028 or U+2029, and (Tomcat, before the
  firewall) `\`. The added check also refuses keys with a `.` or `..` segment between slashes (`a/../b`). These are
  listed in the spec as the exceptions to "a listed key is addressable"; the readiness sweep still evaluates them.
- **`PASS_THROUGH` was tried and rejected.** It leaves `%2F` literally in the servlet path, and the firewall's
  decoded-path blocklist refuses a bare `%`. Getting past that needs `setAllowUrlEncodedPercent(true)`, which would
  also admit `%25` double encoding. This was found during implementation, not anticipated in the first draft.

Both settings are fixed in code rather than configurable: there is no supported deployment that wants them off.

## Risks / Trade-offs

- **Active groups move during a walk.** `last_activity_at` only moves forward, through the upsert's `GREATEST`.
  Under `oldest`, a group already returned can reappear later in the walk. Under `newest`, a group not yet returned
  can move ahead of the cursor and be passed over. → This is inherent to ordering by a mutable column. The spec
  promises exactly-once only for groups whose activity is stable during the walk, and a fresh walk sees the rest.
  Ordering by the immutable `group_key` would avoid it, but would lose the point of the listing.
- **Encoded slashes are accepted on every path, not just this one.** The firewall bean is global. → Routing and
  authorization both match on the raw URI, where `%2F` stays inside one segment, so it cannot make a request match a
  different pattern. The traversal check runs on the decoded request URI and the double-encoding check on the decoded path. No other endpoint binds a segment that a slash could confuse. The test suite covers the authorized path.
- **Proxies in front of the runner.** An ingress that rejects or decodes `%2F` defeats this. → Document it in
  `docs/kubernetes-exposure.md` for whoever operates the ingress. The default `kubectl port-forward` access
  path is unaffected.
- **Two queries per page.** These are the page and `countByPipelineName`, both index-backed, on a table bounded by
  eviction.

## Migration Plan

V12 only drops and recreates one index. The wire change is additive, and the encoded-slash change only turns a 400
into a working request. To roll back, deploy the previous build. It serves its queries from the new index equally
well, and requests with `%2F` go back to 400. No flag is needed.

## Open Questions

- Whether a given ingress controller (for example Traefik) passes `%2F` through by default. That depends on the
  controller and its version, and is noted in the ingress doc. It does not block the runner change.
