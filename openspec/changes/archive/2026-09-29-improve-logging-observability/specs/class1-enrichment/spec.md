## ADDED Requirements

### Requirement: A materialize window reports its duration and a failure its drain position

The runner SHALL log each committed materialize window with its duration and its window limit. It SHALL log
that line at WARN when the window took more than half of `adas.read-timeout-ms`, naming `advanced.rows_per_scan`
as the lever, so a call approaching the timeout is visible before it fails. The executor SHALL log a failed fire
once, with:
- the pipeline;
- the window the fire failed on;
- how many earlier windows of the same fire were committed;
- the classified cause.

A transport failure SHALL be logged without a stack trace, by the executor and by the runner's execution failure
line alike (see the `observability` capability), because its classified message already names the call, the
elapsed time and the timeout.

#### Scenario: A slow window warns before the timeout

- **WHEN** a materialize window commits after more than half of `adas.read-timeout-ms`
- **THEN** its line is logged at WARN with the duration, the limit and the `advanced.rows_per_scan` hint

#### Scenario: A failure says how far the drain got

- **GIVEN** a fire whose first window commits and whose second times out
- **WHEN** the failure is logged
- **THEN** the line names window 2, one earlier committed window, and the classified transport cause, and the
  first window's cursor is kept
