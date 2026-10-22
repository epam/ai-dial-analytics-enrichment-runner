## MODIFIED Requirements

### Requirement: Row enrichment via DIAL

For each unit of work — a row of a chunk, or a whole group — the runner SHALL render the evaluator request
template by substituting that unit's input variables, assemble the DIAL request (evaluator params +
`response_format` derived from the evaluator's response schema), call DIAL Core chat/completions, and read
the model's structured output from the first choice's message content. A variable whose value is a
collection SHALL render as a JSON array, so a group's member list can be interpolated; a scalar SHALL
render as it does today. The render → call → validate → map pipeline SHALL be shared by both grains rather
than reimplemented per grain, so the rate-limiter acquisition, spend accounting and retryable-status
classification have one implementation.

#### Scenario: A row is rendered and sent to DIAL

- **GIVEN** a chunk row with input variables and an evaluator with a request template
- **WHEN** the row is processed
- **THEN** the rendered request is sent to `.../deployments/{model}/chat/completions` and the
  response content is read

#### Scenario: A collection variable renders as a JSON array

- **GIVEN** an input variable whose value is a list of objects
- **WHEN** the template is rendered
- **THEN** the variable is substituted with a JSON array, and scalar variables are unaffected

### Requirement: Output mapping and provenance

The runner SHALL map the validated response to physical enrichment columns using each output
variable's JSONata (or a direct field fallback), and SHALL attach provenance columns to every written row.
The permitted provenance columns SHALL be `evaluator_version`, `model` and `enriched_at` for every grain,
plus `group_version` and `truncated` for a group-grain row — the version of the input the label was derived
from and whether that input was trimmed. A provenance column the target enrichment does not physically
carry SHALL be tolerated rather than avoided, since ADAS drops it silently. An output-mapping evaluation
error SHALL dead-letter the unit of work — the row at stage `output_map`, the group at stage `group_map`.

#### Scenario: A row is written with mapped columns and provenance

- **WHEN** a row is enriched successfully
- **THEN** the written row carries the grain key, the mapped output columns, and the provenance
  columns `evaluator_version`, `model`, `enriched_at`

#### Scenario: A group row additionally carries its input version and truncation

- **WHEN** a group is enriched successfully
- **THEN** the written row additionally carries `group_version` and `truncated`
