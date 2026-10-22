## MODIFIED Requirements

### Requirement: Group-key sampling

For a group pipeline the runner SHALL apply the pipeline's `advanced.sample_fraction` to the **group key**
rather than to each row, so the decision is deterministic and monotone at the grain the work is keyed by.
A sampled-out group SHALL NOT be marked and SHALL therefore hold no state, while the cursor still advances
past its rows.

#### Scenario: A sampled-out group holds no state

- **GIVEN** a group pipeline with a sampling fraction that excludes a given group key
- **WHEN** a page containing only that group's rows is committed
- **THEN** no `group_state` row exists for it and the cursor has advanced

### Requirement: Group input assembly

When a group work item executes, the runner SHALL fetch the group's members from ADAS
`POST /v1/pipelines/{name}/group-rows`, sending only the group key and a row limit — ADAS resolves the source,
applies the membership filter, orders members by the source's total order, and enforces its row and
response-byte caps. The runner SHALL then apply the pipeline's `vars` to **each** member row — the same
map, resolved the same way, as on the row path — yielding one object per member keyed by variable name,
and SHALL expose that collection to the evaluator's request template as a single variable rendered as a
JSON array. Member field correlation SHALL be preserved, so a member's bound fields stay together rather
than being split across parallel per-variable collections. The runner SHALL NOT name a table, a column set
or an ordering in the request.

#### Scenario: Members become one object each under a collection variable

- **GIVEN** a group pipeline whose `vars` declares two variables, and a group of three members
- **WHEN** the work item executes
- **THEN** the rendered prompt interpolates a JSON array of three objects, each carrying that member's two
  bound fields

#### Scenario: One prompt, one model call per group

- **WHEN** a group of many members is evaluated
- **THEN** exactly one prompt is rendered and exactly one model call is made for the whole group
