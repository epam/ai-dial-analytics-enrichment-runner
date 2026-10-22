## MODIFIED Requirements

### Requirement: Cursor advances only on a successful response

The runner SHALL persist a Class-1 pipeline's `next_cursor` only after a successful (2xx) materialize
response. A failed, timed-out or rejected call SHALL leave `pipeline_cursor` unmoved so the identical
window is retried on the next fire. An empty window — `rows_written=0`, `has_more=false`, and a
`next_cursor` that is absent or equal to the cursor sent — SHALL NOT overwrite the stored cursor with
a null value. Because ADAS probes a window before it writes it, a replayed cursor re-executes an
identical range whose duplicate rows collapse on the target's `ReplacingMergeTree` grain key read with
`final=1`, giving exactly-once effect without a transaction around the call.

The cursor SHALL be persisted through the same guarded write the scan lanes use, which refuses a window materialized
for a pipeline whose name was recreated during the tick (see the `incremental-scan` capability). The tick SHALL then
end without recording a failure, and the next fire starts the recreated pipeline from the beginning.

#### Scenario: A failed materialize leaves the cursor unmoved

- **GIVEN** a Class-1 pipeline with a stored cursor
- **WHEN** the materialize call returns a 5xx
- **THEN** the stored cursor is unchanged and the tick fails, to be retried on the next fire

#### Scenario: An empty window does not reset the cursor

- **GIVEN** a Class-1 pipeline with a stored cursor
- **WHEN** the materialize call returns `rows_written=0`, `has_more=false` and no `next_cursor`
- **THEN** the stored cursor is left as it was, not replaced with null

#### Scenario: A replayed window is a no-op in effect

- **GIVEN** a window that ADAS has already written but whose cursor the runner did not persist
- **WHEN** the tick fires again with the same cursor
- **THEN** the identical window is re-executed and the queried result read with `final=1` is unchanged


#### Scenario: A window materialized for a recreated pipeline is not recorded

- **GIVEN** a Class-1 pipeline reset because its name was recreated while a tick for the old pipeline was draining
- **WHEN** that tick tries to persist its next window's cursor
- **THEN** the cursor is not written and the tick ends without a failure
