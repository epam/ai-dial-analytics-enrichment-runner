## MODIFIED Requirements

### Requirement: Input mapping with per-row dead-lettering

For each kept row the runner SHALL extract the transform's input variables from the pipeline's
`transform.inputs` map. Each entry's **key** is the variable name and its value carries exactly one of a
bound `column` (value = `row[column]`) or a `jsonata` expression evaluated over the row.
`transform.inputs` is the former top-level `vars` map relocated under `transform` by the evaluator fold,
and `vars` had itself replaced the `input_bindings` list *and* the evaluator's `input_vars` together: the
map's own keys are what drive substitution, so there is nothing left to declare twice. A JSONata
evaluation failure SHALL dead-letter that row at stage `input_map` (never a silent drop), naming the
variable, while the rest of the page proceeds; a variable that resolves to nothing SHALL yield a null
value passed through.

Declaration order SHALL be preserved, since it is the order the rendered prompt interpolates.

#### Scenario: A bound column and an expression both resolve

- **GIVEN** a pipeline whose `transform.inputs` declares one variable bound to a column and one carrying
  a JSONata expression
- **WHEN** a row is mapped
- **THEN** the inputs carry both variables under their map keys, the first taking the row's column value
  and the second the expression's result

#### Scenario: An un-evaluable binding dead-letters only its row

- **GIVEN** a page of rows and a pipeline whose input variable fails to evaluate
- **WHEN** the page is processed
- **THEN** each failing row produces a `dlq_item` at stage `input_map` and no chunk is enqueued for
  it, while mappable rows are still chunked, and the cursor advances

### Requirement: Atomic enqueue and cursor advance

The runner SHALL commit a page's work, its dead-letter items, and the advance of `pipeline_cursor` to the
scan's `next_cursor` in a single transaction — all committing together or none. For a row-grain pipeline the
page's work is its chunk enqueues; for a group pipeline it is the page's `group_state` upserts. Chunk work
items SHALL use deterministic instance ids derived from the pipeline name, the pipeline's `generation` and
cursor-before, so a repeated scan of the same range does not create duplicate chunks; group state upserts
SHALL take the maximum of the existing and incoming values, so a repeated scan leaves the state unchanged.

The identity component of every deterministic instance id SHALL be the pipeline **name**, carried both as a
literal `#`-delimited prefix and inside the hashed basis. This depends on ADAS refusing a `#` in a pipeline
name at registration (`^[a-z][a-z0-9_-]{0,63}$`): the delimiter is what keeps one pipeline's prefix from
matching another's work, and the names `a#b` and `a` would otherwise share the prefix `a#`. The runner SHALL
rely on that guarantee rather than re-validating the name (see the `pipeline-sync` admission gate), and the
grammar's other exclusions — uppercase and a leading `_` — carry no meaning here.

Because the identity is in the hashed basis and not only the prefix, every deterministic work-item id changes
if a pipeline's identity changes. Re-identifying a pipeline is therefore an operation that SHALL be performed
only against a drained queue, since `scheduleIfNotExists` cannot dedup an item enqueued under a former
identity against the same work enqueued under the new one.

Substituting the pipeline's `generation` for the evaluator version widens what makes an id change, and the
widening is meaning rather than spelling: every deterministic instance id changes once at the fold's
cut-over, and thereafter changes on **any** declaration edit — prompt, expressions, `filter`, trigger,
cadence, `rate_rpm` or `batch_scan_limit` — where an evaluator version moved only when the prompt or the
expressions changed. The residual overlap window this dedup exists to suppress therefore no longer collapses
across such an edit, which is the same hazard the paragraph above states for a re-identification.

At the fold's cut-over the queue is **not** drained, so that hazard is live rather than avoided, and it is
what decides the group lane's handling of a carried-over work item. A group item enqueued before the fold
carries a generation of `0`, so its instance id cannot match the id the readiness sweep computes at the
pipeline's live `generation`, and the sweep's in-flight suppression therefore does not see it. Running
both would evaluate — and pay for — one group twice, which is why such an item SHALL be discarded rather
than adopted (see `enrichment-worker`). The row and write lanes do not have this problem: their items are
not re-enqueued by any sweep, so adopting one cannot duplicate work.

#### Scenario: Enqueue and cursor advance commit together

- **WHEN** a page commits successfully
- **THEN** the page's chunks are enqueued and the cursor is advanced to `next_cursor`

#### Scenario: A failed commit rolls back both

- **WHEN** the commit fails (e.g. a DLQ write is rejected)
- **THEN** neither the chunks nor the cursor advance are persisted

#### Scenario: Repeated scan of the same range does not duplicate chunks

- **GIVEN** a page already committed for a cursor-before
- **WHEN** the same range is scanned again at the same cursor-before
- **THEN** the deterministic instance ids dedup and no additional chunks are enqueued

#### Scenario: A group page's state and cursor commit together

- **WHEN** a group pipeline's page commits successfully
- **THEN** the page's group state is persisted and the cursor is advanced, or on failure neither is
