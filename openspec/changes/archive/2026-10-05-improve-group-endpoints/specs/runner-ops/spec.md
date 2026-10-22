## ADDED Requirements

### Requirement: Group state listing pagination, ordering and count

The group-state listing (`GET /v1/pipelines/{name}/groups`) SHALL page by keyset in a total order over
`last_activity_at`, then `group_key`, in one of two directions. The `order` query parameter selects it:
- `oldest`, the default, is ascending: the groups that have been quiet longest come first.
- `newest` is descending: the most recently active groups come first.

`order` SHALL be matched case-insensitively, and a blank value SHALL count as absent. Any other value SHALL be refused
with HTTP 400.

A page SHALL hold at most `limit` groups; `limit` SHALL default to `100` and be clamped to `1..500`. The response
SHALL carry:
- `groups`
- `has_more`: whether further groups exist past this page
- `next_cursor`: an opaque string to pass back as the `cursor` query parameter for the next page. It is absent when
  `has_more` is false (the API omits null fields).

A request without `cursor`, or with a blank one, SHALL return the first page.

A `cursor` the listing did not issue SHALL be refused with HTTP 400 and error code `invalid_cursor` rather than
answered with the first page. That covers a cursor that is undecodable, of an unknown version, or has malformed or
out-of-range fields. A cursor issued under one `order` and passed with the other SHALL be refused the same way:
under the other direction, "past this group" names a different set of groups.

A walk SHALL return every group exactly once, provided the group exists for the whole walk and its
`last_activity_at` does not change during it. Moving `last_activity_at` forward is ordinary group activity, and it
moves the group's position:
- under `oldest`, a group already returned can appear again;
- under `newest`, a group not yet returned can be passed over.

A group evicted during the walk SHALL simply not be returned.

Every page SHALL also carry `total`, the number of groups the pipeline has. `total` SHALL ignore the cursor and SHALL
be read on the same snapshot as the page's groups.

#### Scenario: Walking the listing to exhaustion

- **GIVEN** a group pipeline with five groups, two of which share one `last_activity_at`
- **WHEN** the caller lists with `limit=2`, following `next_cursor` each time
- **THEN** the pages hold 2, 2 and 1 groups, oldest activity first
- **AND** `has_more` is true, true and false, and the last page carries no `next_cursor`
- **AND** every group appears exactly once
- **AND** every page reports `total` 5

#### Scenario: Newest first

- **GIVEN** a group pipeline with several groups of distinct activity times
- **WHEN** the caller lists with `order=newest`
- **THEN** the groups are returned most recent `last_activity_at` first

#### Scenario: Oldest first is the default

- **GIVEN** a group pipeline with several groups of distinct activity times
- **WHEN** the caller lists without `order`
- **THEN** the groups are returned least recent `last_activity_at` first, as with `order=oldest`

#### Scenario: A cursor replayed under the other order is refused

- **GIVEN** a `next_cursor` from a listing with `order=oldest`
- **WHEN** the caller lists with that cursor and `order=newest`
- **THEN** the system responds HTTP 400 with error code `invalid_cursor`

#### Scenario: A cursor the listing did not issue is refused

- **WHEN** the caller lists a pipeline's groups with a `cursor` the listing did not issue
- **THEN** the system responds HTTP 400 with error code `invalid_cursor`

### Requirement: A group key containing a slash is addressable

The per-group endpoints SHALL accept a group key containing `/` when that slash is percent-encoded as `%2F` in the
`{groupKey}` path segment. The runner SHALL bind the decoded key, so `a%2Fb` addresses the group `a/b`. Group keys
are arbitrary `group_by` values from the source table, so a listed key SHALL be addressable for requeue unless it is
one of the exceptions below.

Route and authorization matching SHALL see an encoded slash as part of one path segment, so an encoded slash SHALL
NOT change which route or which authorization rule a request matches. The request firewall SHALL keep all of its
default checks except the one refusing an encoded slash, and SHALL additionally refuse a request whose
percent-decoded request URI contains a `.` or `..` segment. In particular, an encoded slash that would climb out of
its segment (`x%2F..%2Fcache`) and a double-encoded slash (`%252F`) SHALL be refused before routing, with HTTP 400.
In `oidc` mode that refusal's error dispatch carries no credential, so the client sees HTTP 401 instead; it is never
routed.

These keys SHALL remain unaddressable however they are encoded, because the firewall or the servlet container refuses
the request with HTTP 400 before routing:
- the empty key, and the keys `.` and `..`;
- a key with a `.` or `..` segment between slashes, such as `a/../b`, `./a` or `a/.`;
- a key containing `//`, `%`, `;` or `\`;
- a key containing a line feed, a carriage return, U+2028 or U+2029.

A slash at the start or end of a key, or a key that is `/` alone, SHALL be addressable. A `.` in the key SHALL be sent
literally, since the firewall refuses an encoded period (`%2E`).

#### Scenario: A group whose key contains a slash is re-driven

- **GIVEN** a group pipeline with a group whose key is `team/a`
- **WHEN** an operator requests `POST /v1/pipelines/{name}/groups/team%2Fa/requeue`
- **THEN** the system responds HTTP 202
- **AND** one group work item exists for group `team/a` at its current version

#### Scenario: A listed slash key round-trips to the requeue endpoint

- **GIVEN** a group pipeline with a group whose key is `team/a`
- **WHEN** the operator lists the pipeline's groups
- **THEN** that group is reported with `group_key` `team/a`
- **AND** percent-encoding that value as a path segment addresses the same group for requeue

#### Scenario: An encoded slash cannot escape its segment

- **GIVEN** the runner in `none` security mode
- **WHEN** an operator requests a requeue whose `{groupKey}` segment is `x%2F..%2F..%2Fcache` or `team%252Fa`
- **THEN** the system responds HTTP 400 before routing
- **AND** no group work item is enqueued

#### Scenario: A key outside the addressable set is refused rather than routed

- **GIVEN** a group pipeline with a group whose key is `a/../b`
- **WHEN** an operator requests a requeue with that key percent-encoded as `a%2F..%2Fb`
- **THEN** the system responds HTTP 400 before routing
- **AND** the group is still reported by the listing
