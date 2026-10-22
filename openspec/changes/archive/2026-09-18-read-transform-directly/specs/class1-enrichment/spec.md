## RENAMED Requirements

- FROM: `### Requirement: Pipeline routing by evaluator type`
- TO: `### Requirement: Pipeline routing by transform type`

## MODIFIED Requirements

### Requirement: Pipeline routing by transform type

The runner SHALL route a pipeline to an execution lane by its authored **`transform.type`**: a `sql`
(Class-1)
pipeline to the materialize tick, and an `llm` (Class-2) pipeline to the row scan. The two lanes SHALL NOT be
interchangeable — ADAS rejects a `sql` pipeline on `POST /v1/pipelines/{name}/scan` and an `llm` pipeline on
`POST /v1/pipelines/{name}/materialize`, both with `422 pipeline_validation_failed`. A Class-1 pipeline SHALL be
recognised from the **`transform` block alone**, without a model, preset, params, request template,
`transform.inputs` or a top-level `response_schema`. The routing rule is unchanged by the evaluator fold;
only where the type is read from has moved, from an `evaluator` object the feed inlined to the
`transform` block the operator authored. The SQL derivation SHALL NOT cross the wire at all: ADAS
evaluates it inside ClickHouse, the `transform` block says nothing about outputs, and what the pipeline
carries is the resolved `outputs` list naming the columns the derivation writes — which the runner reads
only to admit the pipeline, never to interpret.

**Re-derivation over history is unchanged in mechanism and restated in the new vocabulary.** An operator
patches the Class-1 transform's expressions, which bumps the pipeline's `generation`, and then backfills
the affected range. There is no evaluator version to bump, and no second object to register — the two
steps that used to be "register a new evaluator version" and "repoint the pipeline" are now one
`PATCH /v1/pipelines/{name}`.

#### Scenario: A sql pipeline is routed to the materialize tick

- **GIVEN** an enabled pipeline whose `transform.type` is `sql`
- **WHEN** the pipeline sync reconciles
- **THEN** a `class1-materialize` recurring task instance keyed by the pipeline name exists, and no
  `pipeline-scan` instance exists for that pipeline

#### Scenario: An llm pipeline is unaffected

- **GIVEN** an enabled pipeline whose `transform.type` is `llm`
- **WHEN** the pipeline sync reconciles
- **THEN** a `pipeline-scan` recurring task instance keyed by the pipeline name exists, and no
  `class1-materialize` instance exists for that pipeline

#### Scenario: A pipeline that changes type migrates lanes

- **GIVEN** a cached `llm` pipeline with an existing `pipeline-scan` instance
- **WHEN** a later sync returns the same pipeline name with `transform.type: sql` and a higher
  `generation`
- **THEN** the `pipeline-scan` instance is cancelled, a `class1-materialize` instance is created, and the
  pipeline's stored cursor is left untouched

#### Scenario: A Class-1 pipeline needs no model members

- **GIVEN** an enabled pipeline whose `transform.type` is `sql`, carrying no `transform.model`,
  `transform.preset`, `transform.params` or `transform.request_template`, no `transform.inputs`, and no
  top-level `response_schema`
- **WHEN** the runner admits it
- **THEN** the pipeline is cached and scheduled, because none of those members is required for a
  derivation computed in the database
