## MODIFIED Requirements

### Requirement: Output mapping and provenance

The runner SHALL map the validated response to physical enrichment columns using the pipeline's
**`outputs`** list — one resolved entry per output, in binding order, carrying the model response
property `name`, the physical `column` written, and an optional `jsonata` transform. For each entry the
runner SHALL evaluate `jsonata` over the response when present and otherwise read `response[name]`
directly, writing the value under `column`. An absent `jsonata` is the only spelling of "no transform"
(an identity expression is refused at registration), and it is the ordinary case.

The runner SHALL NOT join two lists by name to obtain this mapping, and SHALL NOT infer either of `name`
and `column` from the other. ADAS resolves the mapping once and serves it whole: `name` is the target
column's *exposed* name and the property the composed response schema declares, while `column` is the
physical column the value is written to. They are often equal in practice and equal by
construction on none, so collapsing them writes to a column that does not exist or reads a property that
was never declared.

The runner SHALL attach provenance columns to every written row. The permitted provenance columns SHALL
be `evaluator_version`, `model` and `enriched_at` for every grain, plus `group_version` and `truncated`
for a group-grain row — the version of the input the label was derived from and whether that input was
trimmed. A provenance column the target enrichment does not physically carry SHALL be tolerated rather
than avoided, since ADAS drops it silently. An output-mapping evaluation error SHALL dead-letter the unit
of work — the row at stage `output_map`, the group at stage `group_map`.

#### Scenario: A row is written with mapped columns and provenance

- **WHEN** a row is enriched successfully
- **THEN** the written row carries the grain key, the mapped output columns, and the provenance
  columns `evaluator_version`, `model`, `enriched_at`

#### Scenario: An output whose exposed name differs from its column

- **GIVEN** a pipeline with an output whose `name` and `column` differ
- **WHEN** a response carrying that property is mapped
- **THEN** the value is read from the response under `name` and written under `column`, and neither name
  appears in the other's place

#### Scenario: An output with no transform is read straight from the response

- **GIVEN** a pipeline with an output declaring no `jsonata`
- **WHEN** the response is mapped
- **THEN** the value is `response[name]`, with no expression evaluated

#### Scenario: A group row additionally carries its input version and truncation

- **WHEN** a group is enriched successfully
- **THEN** the written row additionally carries `group_version` and `truncated`
