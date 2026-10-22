## Why

`add-group-dirty-mark` landed the group state layer: a group rule's scan page dirty-marks its groups, and a scheduled sweep
enqueues the ones that are both dirty and ready. Nothing consumes those work items — `enrich-group`
carries a placeholder handler that throws, so a group rule currently accumulates queued evaluations that
can never run.

This change closes the loop: one work item fetches its group's members from
ADAS, derives one label from them with one model call, and writes one enrichment row keyed by the group
key. It is the first path in the runner where the unit of work is coarser than a row, which changes the
failure semantics (a group is 1:0..1, so there are no partial-row outcomes) and introduces an input that
can be arbitrarily large.

## What Changes

- **Group assembly client**: `POST /v1/rules/{id}/groups:fetch` → `{rows[], truncated}`. ADAS applies the
  membership filter, the source's total order, the row cap and the byte cap, and re-checks the sensitive
  entitlement. The runner sends only the group key.
- **Group worker**: replaces `GroupChunkTask`'s throwing placeholder. Fetch members → assemble input →
  render **one** prompt → DIAL → validate (`json_schema` + one repair retry) → output-map → upsert **one**
  row. On success, stamp `group_state.computed_version`, `computed_at` and `computed_truncated`.
- **Group input contract**: the rule's existing `input_bindings` are applied to **each member row**,
  producing one object per member; the collection is exposed to the template as a JSON array under a
  single conventional variable. Member field correlation is preserved and `InputMapper` is reused
  unchanged. `TemplateRenderer` gains collection support (a non-scalar value renders as JSON).
- **Input budget**: ADAS caps by rows and bytes and reports `truncated`; the runner applies its own token
  budget on top, dropping **oldest** members first, then truncating per member. Either kind of trimming
  sets the row's `truncated` provenance.
- **Group provenance is now written, not just recorded locally.** ADAS's permitted provenance set has been
  extended with un-prefixed `group_version` and `truncated`, so the row carries the input version it was
  judged from and whether that input was trimmed — making a partial label distinguishable downstream
  rather than only in runner state. Both are silently dropped by ADAS when the target does not carry them,
  so sending them is always safe. **This supersedes `add-group-dirty-mark`'s statement** that group version and truncation
  cannot leave the runner.
- **Shared model-call pipeline**: the render → DIAL → validate → repair → output-map sequence is extracted
  from `WorkerService` into a collaborator both workers use, rather than duplicated. The same extraction
  is what `add-two-phase-upsert`'s compute/upsert split will act on.
- **Failure taxonomy at group granularity**: `groups:fetch` 5xx/timeout → item retried with state
  untouched; DIAL 429/5xx → whole group delayed; assembly or output-mapping failure → dead-letter at a new
  `group_map` stage; a non-retryable `groups:fetch` rejection → dead-letter at `group_fetch`; exhaustion →
  archived for requeue. The poison-flood circuit breaker applies to group dead-letter rate too.

## Capabilities

### New Capabilities

<!-- none: extends group-enrichment and enrichment-worker rather than introducing a capability -->

### Modified Capabilities

- `group-enrichment`: adds the worker half — group input assembly and its budget, the single-row write
  with group provenance, and the state stamp that makes an evaluated group clean. Also revises "Per-group
  state owned by the runner", whose claim that group version and truncation cannot be written no longer
  holds.
- `enrichment-worker`: "Row enrichment via DIAL", "Response validation with one repair retry" and "Output
  mapping and provenance" become grain-agnostic — the same pipeline serves a row and a group, and the
  permitted provenance set grows.
- `resilience-and-dlq`: "Dead-letter archival and requeue" and "DLQ circuit breaker with rule pause and
  resume" extend to group-granularity failures and the two new stages.
- `service-runtime`: "PostgreSQL runtime-state schema" — `dlq_item.stage`'s CHECK constraint admits
  `group_fetch` and `group_map`.

## Impact

- **New code**: `adas/AdasGroupFetchClient`, `adas/dto/{GroupFetchRequest,GroupFetchResponse}`,
  `worker/group/{GroupWorkerService,GroupInputAssembler,GroupFailureHandler}`,
  `worker/EvaluatorInvoker` (extracted), `worker/ProvenanceColumns` (extracted).
- **Modified code**: `worker/group/GroupChunkTask` (real handler + failure handler), `worker/WorkerService`
  (delegates to the extracted invoker), `worker/TemplateRenderer` (collection rendering), `dlq/DlqStage`
  (+`GROUP_FETCH`, +`GROUP_MAP`), `persistence/GroupStateWriter` (+the computed stamp),
  `configuration/GroupProperties` (+input budget).
- **Migration**: `V4__group_dlq_stages.sql` — relaxes the `dlq_item.stage` CHECK constraint, which V2 pins
  to the five existing values. Additive and backwards-compatible.
- **ADAS contract**: consumes the already-shipped `groups:fetch`; no ADAS change required. Note
  `groups:fetch` has **no pagination** — `truncated=true` means the remainder is unreachable, which is why
  truncation must be recorded rather than worked around.
- **Configuration**: `GROUP_INPUT_TOKEN_BUDGET`, `GROUP_FETCH_LIMIT` documented in
  `docs/configuration.md`.
- **Sensitive data**: a group's members are the largest and most sensitive payload the runner handles.
  `GroupPayload` continues to carry keys only, assembled input and rendered prompts are never logged, and
  the dead-letter `raw_response` remains the sole place model output persists. `add-sensitive-posture` formalises this posture.
- **Not here**: the per-group cost ceiling (`add-group-cost-ceiling`), which `ready_when.cost_ceiling` declares and this
  change's success path makes measurable; and the compute/upsert split (`add-two-phase-upsert`).
