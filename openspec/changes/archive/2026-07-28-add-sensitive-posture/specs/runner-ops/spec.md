## MODIFIED Requirements

### Requirement: Structured chunk-boundary logging

The runner SHALL log at work-item boundaries with correlation context in the MDC — rule id and item id for
every grain, plus the group key for a group item — and SHALL NOT log full request/response payloads at INFO.

The logging contract SHALL also state what must never be logged **at any level**: assembled group input,
member rows, and rendered prompts. Unlike the INFO restriction, this is not a verbosity choice but a data
boundary, so it holds for `debug` and `trace` too — which is where a careless future line would otherwise
land. It SHALL be verified by a test rather than left to review.

#### Scenario: A chunk logs a correlated boundary line

- **WHEN** the worker begins processing a chunk
- **THEN** it logs a boundary line carrying the rule id and chunk id, without full payloads

#### Scenario: A group item logs its group key as correlation

- **WHEN** the group worker begins processing a group item
- **THEN** its log records carry the rule id, item id and group key in the MDC

#### Scenario: Source content is absent from logs at every level

- **GIVEN** logging configured down to its most verbose level
- **WHEN** a group is evaluated
- **THEN** no record contains the members' content
