## Why

`GET /v1/pipelines/{name}/groups` returns one page only: the oldest-activity groups, capped by `limit` at 500. A
pipeline with more groups than that cannot be browsed past the first page. There is no way to see the newest groups,
which are usually the ones an operator is asking about, and no way to learn how many groups exist. The DLQ listing
had the same problem and now pages by keyset; the group listing should work the same way.

Separately, `POST /v1/pipelines/{name}/groups/{groupKey}/requeue` cannot reach a group whose key contains `/`, even
though group keys are arbitrary `group_by` values from the source table. Sending the `/` as `%2F` does not help:
Tomcat rejects an encoded slash with a 400, and Spring Security's firewall rejects it too, so such a group can never
be re-driven individually.

## What Changes

- `GET /v1/pipelines/{name}/groups`:
  - Accepts an opaque `cursor` query parameter and pages by keyset over `(last_activity_at, group_key)`.
  - Accepts an `order` query parameter:
    - `oldest` (the default, and today's behaviour) or `newest`.
    - Case-insensitive; a blank value counts as absent.
  - The response gains `next_cursor` (absent on the last page), `has_more`, and `total` (all of the pipeline's
    groups, whatever the page).
  - A cursor the listing did not issue is refused with HTTP 400 and the error code `invalid_cursor`. So is a cursor
    replayed under the other `order`.
  - The listing order becomes a total order, with `group_key` breaking ties on `last_activity_at`.
- `POST /v1/pipelines/{name}/groups/{groupKey}/requeue` accepts a group key containing `/` when the slash is sent as
  `%2F`. The runner allows encoded slashes through Tomcat (decoded into the servlet path only) and through Spring Security's
  `StrictHttpFirewall`.
- A Flyway migration rebuilds the group activity index with a `group_key` tie-breaker.
- The change is additive. The `groups` field and the `limit` parameter (default 100, clamped to 1..500) are
  unchanged, so existing callers keep working.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `runner-ops`: adds requirements for the group-state listing's pagination, ordering and count, and for addressing
  a group key that contains a slash.

## Impact

- **API:**
  - `GET /v1/pipelines/{name}/groups` gets an additive change to its response envelope, two new parameters
    (`cursor`, `order`), and a new 400 `invalid_cursor` error.
  - Requeue now accepts `%2F` in the group key.
- **Code:**
  - `GroupAdminController`
  - new `group/GroupListingService`, `GroupCursor`, `GroupListOrder` and `GroupPage`
  - `GroupStateRepository`, `GroupStateResponse`, `GroupStateMapper` and `WebMvcConfiguration`
  - `DefaultExceptionHandler`, with a new `InvalidGroupCursorException` sharing an `InvalidCursorException` base (and
    one `invalid_cursor` handler) with `InvalidDlqCursorException`
  - a new `EncodedSlashConfiguration` (Tomcat connector customizer) and `EncodedSlashFirewall` (`HttpFirewall` bean)
- **Schema:** `V12__group_listing_keyset.sql`. It touches only indexes; no data changes.
- **Security:** the firewall now accepts `%2F` on every path, and refuses a dot segment in the percent-decoded request
  URI. Every other firewall check stays at its default. Path
  matching runs on the raw URI's segments, so an encoded slash cannot widen what `/v1/**` or any public path
  matches.
- **Docs:**
  - a paging/order example and an encoded-slash requeue example in `docs/e2e-runbook.md`
  - an encoded-slash note for the ingress in `docs/kubernetes-exposure.md`
- **Config:** no new properties.
