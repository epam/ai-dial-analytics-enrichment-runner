## ADDED Requirements

### Requirement: A pipeline state reset is logged and counted

When the sync resets a pipeline recreated under the same name, the runner SHALL log one WARN line naming the pipeline,
the `created_at` it replaced and the new one, how many group rows and queued work items it deleted and how many
backfill runs it cancelled, and whether an operator pause was kept; and SHALL count the reset in
`runner.pipeline.state.resets`. The counter SHALL be untagged: a reset describes the registry rather than one
pipeline's activity, the line names the pipeline, and a per-pipeline series would be evicted with the pipeline it
counts. The line is the runner's, not an operator action: it SHALL NOT be written through the operator-action log.

#### Scenario: A reset is logged and counted

- **WHEN** the sync resets a recreated pipeline
- **THEN** one WARN line names the pipeline, both `created_at` values and what was removed, and
  `runner.pipeline.state.resets` increases by one
