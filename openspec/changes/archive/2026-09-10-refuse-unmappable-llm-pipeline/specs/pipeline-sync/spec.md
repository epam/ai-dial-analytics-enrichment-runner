## MODIFIED Requirements

### Requirement: Pipeline admission gate

The runner SHALL refuse to cache or schedule a pipeline it provably cannot execute, logging a
human-readable reason and counting the refusal, so an unexecutable pipeline is visibly absent rather than
cached as healthy and failing on every fire. The gate SHALL be conservative: it rejects only combinations
the runner cannot execute — currently a `sql` evaluator carrying no output vars, a `sql` evaluator that
also declares a model (a contradiction: the lane makes no model call), a `sql` evaluator on the `group`
trigger (the group lane's unit of work *is* a model call, so the two exclude each other), a pipeline on a
model-calling lane that declares no output mapping at all, a group pipeline with no group key, a group
pipeline whose input declares no `version_column` (so it has neither a version to compare nor an activity
timestamp, and could never be dirty-marked), and a group pipeline whose `ready_when` declares **none** of
`signal`, `idle` or `max_staleness` (which would be dirty forever and never ready, so the pipeline would
silently do nothing) — and SHALL NOT reject a pipeline merely for carrying a field the runner does not
recognise. A refused pipeline SHALL have no recurring task instance under any task name.

A pipeline that is **not** Class-1 — one the runner routes to a lane that makes a model call, on either
the row or the group grain — SHALL be refused when it declares no output mapping. This is the most
expensive defect the gate guards against, and the only silent one: with an empty mapping the output mapper
returns no columns, the worker still builds a write row from the grain key and the provenance columns
alone, and that row is written. Against the target enrichment's `ReplacingMergeTree` an all-NULL row at an
existing grain key **replaces a label that has already been paid for** — with no exception, no dead-letter
item, no retry, and the model call made and charged first. Every other outcome the gate prevents merely
wastes work; this one destroys data. The runner SHALL enforce it locally even where ADAS also refuses to
serve such a pipeline, because a remote invariant is not a safe guard for a local, irreversible loss.

The refusal SHALL test the pipeline's **output mapping** — the bindings that say which target column each
model response property is written to — and SHALL NOT be driven by the presence or absence of a per-output
transform expression. An output taken straight from the model response under its own name declares no
transform, and that is the ordinary case, not a defect.

The gate SHALL NOT extend to validating the *content* of a non-empty mapping — that a bound column exists
on the target, or that a bound property is declared by the response schema. ADAS validates both at
registration, a second validator here would drift from the one that gates the registry, and neither
failure is silent at run time.

A `ready_when` declaring `signal` SHALL NOT be a reason to refuse: ADAS computes the predicate inside the
scan and projects the verdict per row, so honouring it needs no predicate support in the runner.

The runner SHALL NOT re-validate the pipeline name's charset. ADAS validates it at registration
(`^[a-z][a-z0-9_-]{0,63}$`), and a second validator on this side would drift from the one that actually
gates the registry. What the runner depends on is stated where it is relied upon — the deterministic
work-item identity requirements in `incremental-scan` and `group-enrichment`.

#### Scenario: An unexecutable pipeline is skipped

- **WHEN** a sync returns an enabled pipeline whose evaluator type and trigger the runner cannot execute
- **THEN** the pipeline is absent from the pipeline cache, no recurring task instance is created for it,
  and the reason is logged and counted

#### Scenario: A row pipeline that would write no output column is refused

- **WHEN** a sync returns an enabled `on_ingest` pipeline with an `llm` evaluator and an empty output
  mapping
- **THEN** the pipeline is refused naming the missing output mapping, is absent from the pipeline cache,
  and has no recurring task instance — so no model call is ever made for it and no all-NULL row is ever
  written over an existing label

#### Scenario: A group pipeline that would write no output column is refused

- **WHEN** a sync returns an enabled `group` pipeline with an `llm` evaluator, a valid readiness
  declaration, and an empty output mapping
- **THEN** the pipeline is refused naming the missing output mapping, is absent from the pipeline cache,
  and has no recurring task instance

#### Scenario: An output with no transform expression is a valid mapping

- **WHEN** a sync returns an `llm` pipeline whose every output is taken directly from the model response
  under its own name, declaring no transform expression
- **THEN** the pipeline is admitted, cached and scheduled — the absent transform is the ordinary spelling
  of "no transform", not a missing mapping

#### Scenario: An unrecognised field does not disable a pipeline

- **WHEN** a sync returns an otherwise executable pipeline carrying a field the runner does not model
- **THEN** the pipeline is cached and scheduled normally

#### Scenario: A group pipeline with any readiness condition is admitted

- **WHEN** a sync returns a group pipeline declaring a group key and a `ready_when` with at least one of
  `signal`, `idle` or `max_staleness`
- **THEN** the pipeline is cached and a `pipeline-scan` recurring task instance is created for it

#### Scenario: A group pipeline with no readiness condition is refused

- **WHEN** a sync returns a group pipeline whose `ready_when` declares none of `signal`, `idle` or
  `max_staleness`
- **THEN** the pipeline is refused with that reason, is absent from the pipeline cache, and has no
  recurring task instance

#### Scenario: A group pipeline over an input without a version column is refused

- **WHEN** a sync returns a group pipeline carrying no `version_column`
- **THEN** the pipeline is refused naming `version_column`, is absent from the pipeline cache, and has no
  recurring task instance
