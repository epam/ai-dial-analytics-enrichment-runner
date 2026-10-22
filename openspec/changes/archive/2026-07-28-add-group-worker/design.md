## Context

The existing worker is row-shaped end to end: a chunk carries many rows, each row is independently rendered,
sent to DIAL, validated and mapped, and each row can fail on its own — which is why the taxonomy has a
per-row dead-letter alongside a whole-chunk retry. A group inverts that. One work item is one group, one
model call and one enrichment row, so there is nothing to isolate: either the group produces a label or it
does not.

What `add-group-dirty-mark` left in place: `enrich-group` is declared with a handler that throws, the sweep enqueues items
keyed `(rule, evaluator_version, group_key, group_version)`, and `group_state` carries
`computed_version`/`computed_at`/`computed_truncated` columns that nothing yet writes.

As-built ADAS facts this design depends on:

- `POST /v1/rules/{id}/groups:fetch` body `{group_key, limit?}` → `{rows[], truncated}`, snake_case. ADAS
  resolves the source and membership filter, returns members in the source's total order
  (`version, identity` ascending — the same order the scan uses), and re-checks the sensitive entitlement.
- Caps: `limit` defaults to 200 and is rejected above 2000 (**400, not clamped**); a response-byte cap of
  8 MB applies independently. `truncated` reports either. **There is no pagination** — a truncated group's
  remainder is unreachable.
- A single first member larger than the whole byte cap is still returned, with `truncated=true`, so an
  empty group is never confused with "no members".
- An unknown or empty group returns `200` with `{"rows":[],"truncated":false}`; a non-group rule is `422`.
- ADAS's permitted provenance set is now `{evaluator_version, model, enriched_at, group_version,
  truncated}`, all un-prefixed and written as ordinary user columns, and any the target does not physically
  carry are **silently dropped** rather than failing the write.

## Goals / Non-Goals

**Goals:** turn one group into one enrichment row; make a truncated input identifiable both locally and
downstream; keep the group's members out of every durable store the runner controls; reuse the row lane's
model-call pipeline rather than reimplementing it; and give the group path a failure taxonomy that matches
its 1:0..1 cardinality instead of pretending rows exist.

**Non-Goals:** the per-group cost ceiling (`add-group-cost-ceiling`); the compute/upsert split (`add-two-phase-upsert`); map-reduce or windowing over
a group too large for one prompt — the budget trims and records, it does not fold; 1:N group values;
and pagination over a truncated group, which ADAS does not offer.

## Decisions

- **The model-call pipeline is extracted, not duplicated.** `WorkerService`'s private
  `enrichRow`/`callWithRepair`/`invoke`/`parseResponse`/`parseContent` chain also owns rate-limiter
  acquisition, spend recording, DIAL span attributes and the 429/5xx classification. Copying that into a
  group worker would put two implementations of the retry and cost-accounting rules in the tree, and they
  would drift precisely where drift is expensive. So it moves to a `worker/EvaluatorInvoker` collaborator
  taking `(rule, evaluator, inputs)` and returning mapped output columns; both workers call it. *Rejected:*
  duplication (above), and a shared abstract worker superclass — the two workers differ in their unit of
  work, their failure taxonomy and their persistence, so inheritance would share the wrong axis.

- **Group input is per-member objects under one collection variable.** The rule's `input_bindings` are
  applied to **each member row** with the existing `InputMapper`, producing one object per member; the list
  is exposed to the template as a JSON array under a single conventional variable name. This preserves the
  correlation between a member's fields, keeps `input_bindings` meaningful (they select and name the
  per-member fields), and makes the variable count independent of the binding count.
  *Rejected:* per-var parallel arrays — a member's fields would be correlated only by index, which breaks
  down as soon as members carry more than one field; and a runner-rendered prose block — it invents a
  formatting convention that cannot be tuned per rule and is awkward to change once evaluators depend on
  it. ADAS deliberately fixes no convention here, so this is the runner's contract to define, and the
  choice is load-bearing for every group evaluator's `request_template`.

- **`TemplateRenderer` renders a non-scalar as JSON.** It currently substitutes `{{var}}` with a
  JSON-escaped scalar. A collection value serializes to a JSON array so a template can interpolate the
  member list. Scalar behaviour is unchanged, so the row path is unaffected.

- **The budget trims oldest-first and always records.** Members arrive in ascending source order, so the
  newest are the most likely to matter to a label about the group's current state; dropping from the front
  keeps the tail. Per-member truncation applies only after dropping whole members fails to fit. Either the
  runner's trimming or ADAS's caps set `truncated`, and the two are deliberately not distinguished on the
  row: what a downstream reader needs to know is that the judgement was partial, not who trimmed it.

- **Group provenance is written now that ADAS accepts it.** `group_version` (the version the input was
  judged at) and `truncated` go on the row alongside `evaluator_version`, `model` and `enriched_at`. This
  reverses `add-group-dirty-mark`'s constraint, which was forced by ADAS's old allow-list, and it matters: without them a
  partial judgement reads downstream as a complete one, and only runner-private state knew which version
  of the group was judged. Because ADAS silently drops provenance the target lacks, sending them is safe
  against every existing enrichment table.

- **`group_state` is still stamped, and remains authoritative for readiness.** The row's provenance is
  lineage for readers; `computed_version` is what makes the group clean so the sweep stops re-enqueuing it.
  Those are different jobs, and deriving readiness from the enrichment table would put the sweep's
  correctness behind a ClickHouse read the runner deliberately does not have.

- **The stamp commits with nothing else.** The write to ADAS happens first; the state stamp follows. A
  crash between them leaves the group dirty, so it is re-swept and re-evaluated — a second model call whose
  identical row collapses on the enrichment table's `ReplacingMergeTree`. That is the at-least-once /
  exactly-once-effect bargain the whole runner makes, and paying for it with an occasional duplicate call
  is cheaper than a distributed transaction the runner cannot have. `add-group-cost-ceiling`'s ceiling is what bounds the
  pathological case.

- **A truncated group is still evaluated.** The alternative — refusing to label a group whose members
  exceeded the caps — leaves the group permanently dirty and re-swept forever, converting a degraded label
  into an unbounded retry loop. Labelling it and marking it partial is the honest outcome, and `truncated`
  is what makes it honest.

- **An empty group is a successful no-op, not a failure.** ADAS returns `200` with no rows for an unknown
  or filtered-out group. Nothing is written, and `computed_version` is stamped so the sweep stops
  revisiting it — a group whose members the membership filter excludes must not re-fire on every pass.

- **Failure taxonomy follows the 1:0..1 cardinality.** There is no partial-row concept, so: a retryable
  `groups:fetch` or DIAL failure fails the whole item and is retried with the state untouched (the group
  stays dirty, which is exactly right); a deterministic assembly or mapping failure dead-letters the group
  at `group_map`; a non-retryable `groups:fetch` rejection dead-letters at `group_fetch`; and exhaustion
  archives the payload for requeue, which is cheap because the payload is four scalars. `dlq_item.grain_key`
  holds the group key, so the existing partial unique index, requeue service and circuit breaker all work
  unchanged.

- **`V4` relaxes the stage CHECK rather than dropping it.** V2 pins `dlq_item.stage` to five literals; the
  new stages need admitting. Keeping the constraint (rather than removing it) is what made `add-group-dirty-mark`'s rollback
  test possible — an invalid stage is still the cheapest way to force a transaction failure.

## Risks / Trade-offs

- **A group's members are the largest and most sensitive payload the runner touches** → `GroupPayload`
  carries keys only, so members exist solely in memory for the duration of one execution; assembled input
  and rendered prompts are never logged at any level; the dead-letter `raw_response` (model output, not
  input) keeps its 30-day TTL. `add-sensitive-posture` formalises and tests this posture.

- **A chatty group can be evaluated repeatedly** → every new member makes it dirty again, and each
  evaluation is a model call. Intended (a label must track its group), but unbounded until `add-group-cost-ceiling`'s
  `cost_ceiling` lands. `max_staleness` and the sweep interval are the only throttles in the meantime.

- **Truncation is silent to the model** → the prompt sees a trimmed member list with no marker that
  members are missing, so the model may state conclusions about "the whole conversation". Mitigated on the
  data side (`truncated` on the row) but not on the prompt side; injecting a marker would change the
  template contract and is left to whoever writes a truncation-aware evaluator.

- **The conventional input variable name is now a contract** → every group evaluator's `request_template`
  is written against it, so changing it later breaks registered evaluators. Accepted deliberately, with
  the rationale recorded above.

- **Extracting the invoker touches `add-enrichment-worker` code covered by its own tests** → it is a pure move with no
  behaviour change, `WorkerIntegrationTest` and `WorkerServiceTest` guard the row path, and doing it now is
  what keeps `add-two-phase-upsert` from having to do it under a larger diff.

- **An oversized single member is returned whole by ADAS** (its byte cap tolerates the first member) → the
  runner's token budget is the only thing between that member and the model, so per-member truncation is
  not optional.
