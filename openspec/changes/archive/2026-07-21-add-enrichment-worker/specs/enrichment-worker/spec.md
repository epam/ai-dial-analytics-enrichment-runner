## ADDED Requirements

### Requirement: Row enrichment via DIAL

For each row in a chunk the runner SHALL render the evaluator request template by substituting the
row's input variables, assemble the DIAL request (evaluator params + `response_format` derived from
the evaluator's response schema), call DIAL Core chat/completions, and read the model's structured
output from the first choice's message content.

#### Scenario: A row is rendered and sent to DIAL

- **GIVEN** a chunk row with input variables and an evaluator with a request template
- **WHEN** the row is processed
- **THEN** the rendered request is sent to `.../deployments/{model}/chat/completions` and the
  response content is read

### Requirement: Response validation with one repair retry

The runner SHALL validate the model's structured output against the evaluator's response JSON Schema.
On a validation failure it SHALL re-request once with the validation error carried as a corrective
message; if the response is still invalid, that row SHALL be dead-lettered at stage `validate` with
the raw response, while the rest of the chunk proceeds.

#### Scenario: Invalid output after repair is dead-lettered

- **GIVEN** a row whose model response fails schema validation on both the initial and repair attempt
- **WHEN** the row is processed
- **THEN** a `dlq_item` at stage `validate` is recorded and no enrichment row is written for it

### Requirement: Output mapping and provenance

The runner SHALL map the validated response to physical enrichment columns using each output
variable's JSONata (or a direct field fallback), and SHALL attach provenance columns
`evaluator_version`, `model`, and `enriched_at` to every written row. An output-mapping evaluation
error SHALL dead-letter that row at stage `output_map`.

#### Scenario: A row is written with mapped columns and provenance

- **WHEN** a row is enriched successfully
- **THEN** the written row carries the grain key, the mapped output columns, and the provenance
  columns `evaluator_version`, `model`, `enriched_at`

### Requirement: Rule-scoped upsert of a chunk's rows

The runner SHALL upsert a chunk's successfully-enriched rows through the rule-scoped ADAS endpoint
`POST /v1/rules/{id}/rows`, never naming a table. The upsert SHALL carry the grain key and only the
rule's declared output/provenance columns.

#### Scenario: A chunk's rows are upserted through the rule

- **WHEN** a chunk produces one or more enriched rows
- **THEN** they are posted to `/v1/rules/{id}/rows`

### Requirement: Failure isolation taxonomy

Transient/infrastructure failures (DIAL 429/5xx, ADAS upsert failure) SHALL propagate so the whole
chunk retries. Permanent per-row failures (a non-retryable DIAL 4xx, schema-invalid output after
repair, an output-mapping error) SHALL be isolated to the offending row via the DLQ so one bad row
never blocks its chunk; the chunk's other rows are upserted regardless.

#### Scenario: A non-retryable row error does not block its chunk

- **GIVEN** a chunk where one row hits a permanent error and the others succeed
- **WHEN** the chunk is processed
- **THEN** the failing row is dead-lettered and the successful rows are still upserted

### Requirement: Stale-evaluator and rule-lifecycle guards

Before writing, the runner SHALL drop a chunk whose rule is no longer present in the cache
(disabled/deleted) or whose currently-pinned evaluator version differs from the version the chunk was
built against.

#### Scenario: A stale chunk is dropped without side effects

- **GIVEN** a chunk built against evaluator version N and a rule now pinned to a different version
- **WHEN** the chunk is processed
- **THEN** it is dropped without calling DIAL or upserting
