## MODIFIED Requirements

### Requirement: Body-handling constraints

The runner SHALL NOT log assembled group input, member rows, or rendered prompts at **any** level. This
covers the framework as well as the runner's own code: Spring's HTTP client logs request and response bodies
at DEBUG/TRACE, and for this service those bodies *are* the enrichment payloads — a rendered prompt carries the
source content a pipeline reads. Those loggers SHALL therefore be pinned independently of the application log
level, so raising verbosity for diagnosis cannot silently start writing source content to the log pipeline.
Supplying a logging configuration that overrides the pin re-enables body logging, which a deployment handling
sensitive data SHALL NOT do.

The pinned set SHALL be `org.springframework.web.client` and `org.springframework.http.converter` (bodies) and
`org.hibernate.SQL`, `org.hibernate.orm.jdbc.bind` and `org.springframework.jdbc.core` (statements and bound
values), each declared in `log4j2-spring.xml` with its own logger entry at INFO. Runtime level changes (see the
`observability` capability) SHALL refuse any level below INFO for a pinned logger or any of its descendants.
Because each pin is its own logger entry, a level applied to an **ancestor** leaves it in place.

A response body SHALL NOT reach the log through an exception either. Spring embeds the full response body in a
`RestClientResponseException`'s message, so its `toString()` and the first line of its stack trace carry the
body. A failure path SHALL log such an exception by its classified form only, and SHALL NOT pass it — or any
throwable whose cause chain contains one — as a log call's throwable argument.

The runner SHALL NOT place member rows in a work-item payload: a group work item carries identifying keys only, so member
bodies never reach `scheduled_tasks`. Model output SHALL persist in exactly one place — a dead-letter item's
raw response, bounded by the dead-letter retention window — so an audit of where derived content lands has a
single answer.

These constraints SHALL be verified rather than trusted, because each is a property of every present and
future line in the enrichment path and the cost of breaking one is source content in a log aggregator.

#### Scenario: Member content never reaches the logs

- **GIVEN** a group whose members carry identifiable content, and logging raised to its most verbose level
- **WHEN** the group is evaluated, including its failure paths
- **THEN** no log record contains that content, from the runner's code or from the HTTP client

#### Scenario: A group work item carries no member rows

- **WHEN** a group work item is enqueued and serialized
- **THEN** its payload carries only identifying keys, not the group's members

#### Scenario: An error response body stays out of failure lines

- **GIVEN** DIAL or ADAS answering an error status whose body carries a marker string
- **WHEN** the chunk, group, upsert, backfill or Class-1 failure path logs the failure
- **THEN** no log record — message or stack trace — contains the marker, and the record names the classified
  cause

#### Scenario: An ancestor level change keeps the pins

- **GIVEN** the runtime level file sets `org.springframework` to DEBUG
- **WHEN** the level is applied
- **THEN** `org.springframework.web.client` and `org.springframework.http.converter` stay at INFO
