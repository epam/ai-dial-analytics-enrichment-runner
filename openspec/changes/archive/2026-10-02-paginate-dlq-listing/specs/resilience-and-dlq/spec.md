## ADDED Requirements

### Requirement: DLQ listing pagination and counts

The DLQ listing SHALL page by keyset in the total order `created_at` descending, then `id` descending, so a
caller can walk the archived items matching its filters with no item repeated, and with every item that
existed for the whole walk returned exactly once, while items are archived, requeued or swept concurrently.
An item archived during the walk sorts ahead of the cursor and is not returned by it. A page SHALL hold at most
`limit` items; `limit` SHALL default to `100` and be clamped to `1..1000`. The response SHALL carry `items`,
`has_more` (whether further items exist past this page) and `next_cursor` — an opaque string to pass back as
the `cursor` query parameter for the next page, absent when `has_more` is false (the API omits null fields).
A request without `cursor`, or with a blank one, SHALL return the first page. A `cursor` the listing did not
issue — undecodable, of an unknown version, or with malformed or out-of-range fields — SHALL be refused with
HTTP 400 and error code `invalid_cursor` rather than answered with the first page, because silently restarting
a walk shows the operator the same items twice.

Every page SHALL also carry `total`, the number of items matching the request's filters, and
`requeueable_total`, the number of those whose item-level `requeueable` flag is true. Both SHALL ignore the
cursor, so every page of one walk reports the same counts absent concurrent change, and both SHALL be read on
the same snapshot as the page's items. `requeueable_total` SHALL count by exactly the rule behind the item flag
(the item carries a payload), so the counter and the rows never disagree; a payload the requeue refuses as
predating the evaluator fold (see Dead-letter archival and requeue) therefore lists with `requeueable: true`
and counts as requeueable here.

#### Scenario: Walking the listing to exhaustion

- **GIVEN** five items match the filters, all archived at the same instant
- **WHEN** the caller lists with `limit=2`, following `next_cursor` each time
- **THEN** the pages hold 2, 2 and 1 items, `has_more` is true, true and false, the last page carries no
  `next_cursor`, and every item appears exactly once

#### Scenario: Requeue during a walk

- **GIVEN** a walk of the listing has returned its first page
- **WHEN** an item not yet listed is requeued before the next page is requested
- **THEN** the walk continues from the cursor, omits that item, and repeats no item

#### Scenario: Counts follow the filters, not the cursor

- **GIVEN** a pipeline has three items, two of which carry a payload
- **WHEN** the caller lists it by `pipeline_name` with `limit=1`, following `next_cursor` each time
- **THEN** every page reports `total` 3 and `requeueable_total` 2

#### Scenario: A cursor the listing did not issue is refused

- **WHEN** the caller lists with a `cursor` that was not issued by the listing
- **THEN** the system responds HTTP 400 with error code `invalid_cursor`
