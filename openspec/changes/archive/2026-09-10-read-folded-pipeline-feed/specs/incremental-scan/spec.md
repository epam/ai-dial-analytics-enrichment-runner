## MODIFIED Requirements

### Requirement: Deterministic, monotone sampling

The runner SHALL decide inclusion by `keep ⇔ hash64(key) mod 100 < threshold`, using a portable 64-bit
hash so the decision is stable across re-scans. The key SHALL be the grain key for a row-grain pipeline and
the group key for a group pipeline, so the decision is taken at the grain the work is keyed by. The
threshold SHALL come from the pipeline's `advanced.sample_fraction`; an absent fraction SHALL keep
everything. Raising the fraction SHALL only ever add work. Sampled-out rows SHALL still be considered
"seen" (the cursor advances past them).

#### Scenario: Sampling is stable and monotone

- **WHEN** a key is kept at a lower sampling fraction
- **THEN** it is also kept at any higher fraction, and the same key yields the same decision on every
  evaluation

#### Scenario: A group pipeline samples by group key

- **GIVEN** a group pipeline with a sampling fraction, and a page whose rows have differing grain keys but
  one shared group key
- **WHEN** the page is processed
- **THEN** the whole group is kept or dropped together, according to the group key's decision

### Requirement: Input mapping with per-row dead-lettering

For each kept row the runner SHALL extract the evaluator input variables from the pipeline's `vars` map.
Each entry's **key** is the variable name and its value carries exactly one of a bound `column` (value =
`row[column]`) or a `jsonata` expression evaluated over the row. `vars` replaces the former
`input_bindings` list *and* the evaluator's `input_vars` together: the map's own keys are what drive
substitution, so there is nothing left to declare twice. A JSONata evaluation failure SHALL dead-letter
that row at stage `input_map` (never a silent drop), naming the variable, while the rest of the page
proceeds; a variable that resolves to nothing SHALL yield a null value passed through.

Declaration order SHALL be preserved, since it is the order the rendered prompt interpolates.

#### Scenario: A bound column and an expression both resolve

- **GIVEN** a pipeline whose `vars` declares one variable bound to a column and one carrying a JSONata
  expression
- **WHEN** a row is mapped
- **THEN** the inputs carry both variables under their map keys, the first taking the row's column value
  and the second the expression's result

#### Scenario: An un-evaluable binding dead-letters only its row

- **GIVEN** a page of rows and a pipeline whose input variable fails to evaluate
- **WHEN** the page is processed
- **THEN** each failing row produces a `dlq_item` at stage `input_map` and no chunk is enqueued for
  it, while mappable rows are still chunked, and the cursor advances

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
declaration sets none behaves exactly as one that omitted the former `cadence` did.

#### Scenario: A non-positive chunk size is clamped

- **GIVEN** a pipeline whose `advanced.rows_per_call` is zero or negative and a page with rows
- **WHEN** the page is chunked
- **THEN** chunking uses a size of at least 1 and terminates without looping

#### Scenario: A declared page size above ADAS's ceiling is capped rather than sent

- **GIVEN** a pipeline whose `advanced.rows_per_scan` exceeds `scan.max-scan-limit`
- **WHEN** a scan fire resolves the page size
- **THEN** the request carries `scan.max-scan-limit` rather than the declared value, so the scan is
  served instead of being refused with a `400` on every fire
