## Why

The runner and ADAS are never simultaneously correct across a breaking feed change, which is why the
cut-over is a coordinated window with the runner scaled to zero. Nothing enforces the window: the two services
are released independently, and the order is whatever the two rollouts happen to do. Tracing what each
order costs across the two preceding breaking releases, the worst arm is this service's:

- Against an ADAS deployed **first**, the feed answers with the authored projection — no inlined
  evaluator, no `grain_key`, no `outputs`. In the preceding runner release the only outputs-shaped
  admission arm was gated on `isClass1()`, and `isClass1()` reads the evaluator's `type`, so an absent
  evaluator made it `false`, `isModelCalling` `false` too, and neither the output-mapping nor the
  `response_schema` arm fired. A row-grain LLM pipeline was therefore **admitted** to the scan lane, its
  scan succeeded, `ChunkBuilder` dropped every row because `row.get(null)` is null, and
  `ScanPageCommitter` still advanced the cursor — up to `SCAN_MAX_PAGES_PER_FIRE` pages per fire. No chunk,
  no dead letter, no tripped breaker, no log above DEBUG, and `runner.scan.lag.seconds` **falls to zero**
  as the cursor catches up. Every row passed is permanently unenriched; recovery is deleting the cursor
  row and re-deriving at full model cost.

  That exact entry no longer reaches the scan lane: the same release moved the `outputs` arm out from
  under `isClass1()`, so an entry carrying no `outputs` — which an authored projection never carries — is
  refused today. What survives is the shape, not the instance. `fold-evaluator-into-pipeline`, in flight
  on the ADAS side, produces an entry with **no evaluator and `outputs` still present**: every current arm
  passes it, `taskName()` routes it to the scan lane as an ordinary row-grain pipeline, and `EvaluatorInvoker`
  then dereferences an evaluator that is not there. That one at least throws; the arms below close it
  before it is scheduled, and the cursor and hold guards close the ones that do not throw.
- Against an ADAS deployed **first** in the earlier release, a successful HTTP 200 carrying an *empty*
  envelope was treated as an authoritative "zero enabled pipelines": every recurring instance cancelled,
  the cache cleared, readiness latched UP, and enrichment silently stopped.

Both arms share a shape: the runner acted on a definition it could not tell was wrong, and the only guards
that saved the rest were remote (ADAS's 422 on scanning a sql evaluator). The admission gate already
records the principle this change generalises — *a remote invariant is not a safe guard for a local,
irreversible loss.*

## What Changes

- **The runner declares and verifies an ADAS contract version.** Every ADAS call carries an
  `Adas-Contract-Version` request header naming the contract this build was written against, and every
  ADAS response is checked for the matching echo. A response without it comes from an ADAS older than the
  contract; a response with a different value comes from one on the other side of a bump. Either way the
  runner treats ADAS as incompatible, and the one component that sees every response — the client
  interceptor — is what records that, so a data-plane call discovers a skew as fast as a sync poll does
  rather than waiting up to a poll interval for the feed to notice.
- **An incompatible ADAS stalls the runner instead of steering it.** The sync keeps its cache and tasks,
  does **not** reconcile, and does **not** mark the sync succeeded — so a freshly started pod never
  reaches readiness and its predecessor keeps serving. No model call is made and no write-back is
  attempted while incompatible, and a work item that meets the skew is **held**: rescheduled without
  consuming an attempt and without archiving its computed result. Recovery is automatic on the next poll
  once ADAS matches.
- **The admission gate covers every field its lane dereferences:** a pipeline whose feed entry carries no
  evaluator, or a blank `grain_key`, is refused rather than routed.
- **A scan page that does not carry a column the lane requires is refused, not skipped.** Absence of the
  grain, group-by or version *column* (as opposed to a null value in it) means the page is not the page
  this lane asked for; the tick fails, the cursor is untouched, and it retries. The version column is the
  one where this changes existing behaviour for the better: today its absence dead-letters every row of
  every page.
- **The cursor cannot advance over rows nothing accounted for**, on all three cursors. A page whose
  accounted outcomes — chunk or group mark, dead letter, counted skip — do not cover every row it read
  does not commit an advance, in the live committer, the group committer **and** the backfill's. The
  backfill is where it matters most: a run drains a whole history and then reports itself complete, so a
  silent skip there is not recoverable by waiting for the next tick.
- **Reconciling to empty needs confirming.** One successful empty feed logs and meters; two consecutive
  ones cancel the tasks. The first one still marks the sync succeeded — it *was* a successful poll, and
  withholding readiness for it would keep a fresh pod out of service over a registry that is legitimately
  empty. Zero enabled pipelines is not an expected steady state for a runner.
- **The skew is visible**: a compatibility gauge, a counter for rows skipped by reason, and a counter for
  refused pages. Readiness semantics are deliberately unchanged, so a skew leaves the pod's own admin
  API reachable through its Service.

**BREAKING (its own deploy):** this build cannot read an ADAS that predates the contract, and an ADAS
carrying the contract refuses this service's predecessor on the data plane. Both halves ship in the same
window. Getting the order wrong is recoverable either way, but only the runner-first order is free: it
stalls the rollout and the outgoing pod keeps serving. ADAS-first is survived by the *predecessor* image,
which has none of the machinery below — it still derives already-queued chunks, archives the refused
write-backs with their rows, and dead-letters group items while stamping them clean. See design.md's
migration plan for what that costs and what to requeue.

No new configuration property: the contract version is a constant, for the same reason `view=compiled` is
hard-coded — its only wrong setting disables every pipeline at once, silently.

## Capabilities

### New Capabilities

None. Each change belongs to a capability that already exists.

### Modified Capabilities

- `pipeline-sync`: the contract handshake and what an incompatible ADAS does to the poll; two more
  admission arms; empty-feed confirmation before cancelling every task.
- `incremental-scan`: a page missing a required column is refused rather than skipped, and the cursor
  advance requires every row of the page to be accounted for.
- `backfill`: the same two rules over the backfill's own independent cursor, where a silent skip consumes
  a whole replay and the run still reports complete.
- `enrichment-worker`: no DIAL call, no group assembly and no write-back while ADAS is incompatible —
  across all three work-item lanes, the group lane included.
- `observability`: the compatibility gauge and the skip/refusal counters, and the per-pipeline series
  enumeration that names them.

## Impact

- **Code**: one `ClientHttpRequestInterceptor` on the shared `RestClient` (so none of the five
  `adas/*Client` classes can forget the header), a small compatibility-state component, `PipelineRouting`,
  `PipelineSyncService`, `ChunkBuilder`, `GroupMarkBuilder`, `ChunkBatch`/`GroupMarkBatch`,
  `ScanPageCommitter`, `GroupDirtyMarkCommitter`, `BackfillPageCommitter`, the three work-item services
  (`WorkerService`, `GroupWorkerService`, `UpsertService`) plus their three failure handlers (the hold
  branch), and `RunnerMetrics`.
- **Contract**: the runner now **branches on an ADAS error condition**, superseding the note in
  `openspec/config.yaml` that none of its clients inspects a status or error code. That section's ADAS
  integration contract gains the header, its `/v1/pipelines` scope (ADAS sets the echo under that prefix,
  which is where all five call sites live — a sixth elsewhere would get no echo and read as a skew), and
  the three error codes the ADAS half adds: 409 `runner_contract_required`, 409
  `runner_contract_mismatch`, 422 `write_output_column_required`.
- **Operational**: during a skew the runner holds its cursors and writes nothing; Class-1 positions freeze,
  so ADAS-side rollups reading them clamp until the matching pair is deployed, self-healing within one
  cadence. A pod started against an incompatible ADAS crashloops on its startup probe by design.
- **Not affected**: PostgreSQL schema (no migration), configuration, DIAL invocation, the DLQ shape.
