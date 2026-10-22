## MODIFIED Requirements

### Requirement: Draining incremental scan

When a rule's recurring task fires, the runner SHALL drain scan pages up to a per-fire budget
(`scan.max-pages-per-fire`). Each page SHALL be fetched from ADAS `POST /v1/rules/{id}/scan` with the
rule's persisted cursor, and the scan SHALL continue while `has_more` is true and the budget is not
exhausted. The request MAY additionally carry a `[from, to]` version window, which bounds the keyset rather
than replacing the cursor — the live tick leaves it absent and a bounded backfill supplies it. The rule's
cursor SHALL be read from `rule_cursor` at the start of the drain; a rule with no stored cursor SHALL scan
from the source start. The scan HTTP call SHALL have no local side effects (it precedes the commit and is safe
to repeat). How a page is turned into committed work SHALL depend on the rule's trigger kind: a row-grain
rule's page becomes chunk work items, and a group rule's page becomes per-group state — the drain loop,
budget, gates and cursor handling are identical for both. After each committed page the runner SHALL
re-evaluate the pause, spend-budget, and backpressure gates and stop the drain early when any trips (so a
mid-fire trip stops within one page, not one whole fire). The runner SHALL also stop the drain when a page
reports `has_more` but does not advance the cursor (an unchanged or null next cursor), rather than re-scanning
the same range or restarting from the source.

#### Scenario: A fire drains a single available page

- **GIVEN** a cached rule and a scan page with `has_more=false`
- **WHEN** the rule's scan fires
- **THEN** exactly one page is scanned and its work is committed

#### Scenario: A mid-drain gate trip stops the fire

- **GIVEN** a multi-page drain where a page causes the rule to become paused, over budget, or backpressured
- **WHEN** the next page would be scanned
- **THEN** the drain stops without scanning or enqueuing further pages in that fire

#### Scenario: A non-advancing cursor halts the drain

- **GIVEN** a page that reports `has_more` but returns the same (or a null) cursor
- **WHEN** the drain evaluates the next page
- **THEN** the drain stops and logs, rather than re-scanning the same range or resetting to the start

#### Scenario: A group rule's page becomes group state

- **GIVEN** a cached group rule and a scan page carrying its group key and version column
- **WHEN** the rule's scan fires
- **THEN** the page's groups are marked in `group_state` and no chunk work items are enqueued

#### Scenario: A windowed scan bounds the keyset

- **GIVEN** a scan request carrying a `[from, to]` window
- **WHEN** the page is fetched
- **THEN** the window is sent alongside the cursor, and the cursor still governs progress within it
