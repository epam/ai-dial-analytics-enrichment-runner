## MODIFIED Requirements

### Requirement: Defensive page and chunk sizing

The runner SHALL floor a pipeline's effective `advanced.rows_per_scan` and `advanced.rows_per_call` at 1,
so a pipeline configured with a zero or negative value cannot request a degenerate page or spin the
chunking loop.

The runner SHALL also **cap** a pipeline's effective `advanced.rows_per_scan` at `scan.max-scan-limit`
before sending it, mirroring what the Class-1 lane already does with `class1.max-limit`. ADAS answers an
over-limit scan with a `400` rather than clamping it, and it does not validate the knob at registration —
so a pipeline declaring a `rows_per_scan` above ADAS's `enrichment.scan.max-limit` is accepted by the
registry and then fails on **every** fire, and its lane never advances at all. The cap is applied on the
live scan and on a bounded backfill's pages alike, since both send the pipeline's declared page size.

An absent knob SHALL resolve to the runner's configured default for that lane, so a pipeline whose
declaration sets none behaves exactly as one that omitted the knob under its former name did. The
scan's clock is not one of these knobs: `advanced.scan_every` (once `cadence`) is retired, and the
schedule comes from the trigger (see pipeline-sync, "Recurring task reconciliation").

#### Scenario: A non-positive chunk size is clamped

- **GIVEN** a pipeline whose `advanced.rows_per_call` is zero or negative and a page with rows
- **WHEN** the page is chunked
- **THEN** chunking uses a size of at least 1 and terminates without looping

#### Scenario: A declared page size above ADAS's ceiling is capped rather than sent

- **GIVEN** a pipeline whose `advanced.rows_per_scan` exceeds `scan.max-scan-limit`
- **WHEN** a scan fire resolves the page size
- **THEN** the request carries `scan.max-scan-limit` rather than the declared value, so the scan is
  served instead of being refused with a `400` on every fire
