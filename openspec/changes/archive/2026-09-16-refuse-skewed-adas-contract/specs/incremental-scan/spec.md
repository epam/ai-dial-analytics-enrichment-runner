## ADDED Requirements

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
