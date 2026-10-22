## Context

The runner holds no definitions: it syncs them from ADAS and drives cursors, model calls and write-backs
from what the feed says. That makes the feed's *shape* a correctness input, and two shipped breaking
releases have shown what happens when the two deployments disagree about it. Tracing both deploy orders
names the two outcomes this half is responsible for:
an admitted row-grain pipeline burning its cursor through the backlog while lag falls to zero, and a
successful-but-empty feed cancelling every task with readiness still latched UP.

Three properties of this service make both unrecoverable rather than merely wrong:

- The scan cursor advances at **enqueue**, in the same transaction as the chunk insert, and nothing on the
  scan path ever re-examines a row the cursor has passed. "Already enriched" is not a predicate anywhere.
- Readiness is a **latch**: `ReadinessState` is set once and never cleared, so a process polling a broken
  ADAS for hours still reports UP.
- The failure signals are absent, not merely quiet: the silent-skip path emits no metric at all, and the
  dead-letter breaker is only consulted when something *was* dead-lettered.

The expected deployment shape is single-replica `RollingUpdate` with `maxSurge 1 /
maxUnavailable 0`, and the **startup** probe reads `/actuator/health/readiness`, which includes the
pipeline-sync contributor, on a 30 × 10s budget. That combination is what makes the readiness latch a
useful tool for exactly one job — gating a *new* pod — and a useless one for a pod already running.

## Goals / Non-Goals

**Goals:**

- No deploy order can advance a cursor over rows nothing processed, write a label the feed did not
  describe, or charge a model call whose result cannot be written.
- A skew self-heals when the matching ADAS lands, with nothing to re-enable by hand.
- The guards that matter hold whatever the cause: a bad feed, a bad deploy, or a future bug.

**Non-Goals:**

- Reading two feed vocabularies. The runner speaks one, and refuses anything else.
- Removing the coordinated window; this changes what happens when it is not followed.
- Changing readiness semantics for a running pod, or adding a liveness signal.
- The DLQ's shape, spend accounting, or anything about how DIAL is called.

## Decisions

### 1. Verify the response echo, do not merely send a version

Sending a header proves nothing: an unknown request header is silently ignored by any Spring service, which
is precisely how `view=compiled` was ignored by a fold-era ADAS. The only testable fact is a header the far
side **sets**, because an older build cannot forge one. So the runner's check is on the response: echo
absent → ADAS predates the contract; echo different → ADAS is on the other side of a bump. Both are
"incompatible", and the runner does not try to tell how far apart they are.

Alternatives rejected: a body member on the feed envelope (absent on error responses, and would need
adding to five response records); a version in the URL path (a far larger surface for one integer); relying
on ADAS's refusals alone (they cover the data plane, but the runner still needs its own reason not to
*interpret* a feed it cannot trust).

### 2. One interceptor on the shared `RestClient`, not five clients — and it owns the state

The header is set and the echo verified in a single `ClientHttpRequestInterceptor` on the one `RestClient`
bean. Every ADAS client already shares it, so a sixth client added later is covered without being told.
Putting the check in each client was rejected for the reason the fold already demonstrated — five places
that must agree are five places that can drift.

The interceptor also **records** the verdict, rather than leaving `sync()` to record it. It is the only
component that sees every response, which is the whole argument for putting it there: a skew that begins
between polls is met first by whichever call comes first, and that is usually a scan tick or a write-back,
not the feed. If only the poll recorded it, the gate of decision 5 would read a stale `compatible` for up
to a poll interval and let paid work through. So: on a matching echo the interceptor marks compatible, on
an absent or different echo it marks incompatible and throws. `AdasCompatibilityState` owns the
transition-only ERROR log, so a condition met on a scan tick is logged once rather than once per tick.

ADAS sets the echo under `/v1/pipelines`, which is where all five call sites live. A future client reaching
a different ADAS prefix would see no echo and be classified as skewed, so adding one means extending the
ADAS half's filter in the same window — recorded in `config.yaml`'s integration contract rather than left
as a surprise.

### 3. Incompatible means "stall", implemented as an exception on the existing failure arm

The mismatch surfaces as a `RuntimeException` from the interceptor, which lands on `sync()`'s existing
catch: cache kept, tasks kept, `reconcile` not entered, sync not marked succeeded. That last omission does
the work — a new pod never becomes ready and is restarted, so a runner-first deploy leaves the **outgoing**
pod (which matches the still-old ADAS) serving until ADAS lands. The rollout stalls instead of putting a
mismatched pair into service, and no new machinery is needed to achieve it.

The existing catch logs WARN "Pipeline sync failed"; the mismatch gets its own catch so it logs ERROR and
says which condition it is. Nothing else about `sync()` changes: the `reconcile` it skips is the same
`reconcile` a transient ADAS outage already skips, which is why this needs no new state machine.

Deliberately **not** chosen: treating an incompatible response as a reason to reconcile to empty. That is
the fold-era failure — cancel everything, report healthy — and it is worse than doing nothing, because it
discards state on the strength of an answer the runner has just decided it cannot read.

### 4. Readiness is left alone for a running pod

An already-ready pod stays ready. Un-latching would pull it out of its Service and take its own admin API
(DLQ list, requeue, cache read) with it during exactly the skew when an operator needs to reach it, and
it would buy nothing that the compatibility gauge does not already say. A liveness-affecting signal was
rejected outright: restarting a runner does not fix a version skew, and restart loops destroy the in-memory
pause registry and rate limiter on every cycle.

### 5. Gate paid work before the DIAL call, and hold rather than fail

A chunk enqueued before the skew is still in the queue during it. The lane derives then writes, so gating
only the write-back would spend the model call first and then archive the result. Holding the item — not
failing it, not dead-lettering it, not consuming an attempt — is correct because the queue is durable and
the condition is temporary by construction: the matching deploy is minutes away, and the DLQ exists for a
row this runner cannot process, not for a deploy in progress.

**Two entry points, because the state is discovered by a response.** The cheap check is at the top of the
handler, reading the recorded state — that is what stops a chunk enqueued before the skew from paying for
a model call. It cannot be the only one: the *first* item to meet a fresh skew is what sets the state, so
for that one the verdict arrives as the interceptor's exception, from inside the call. Both arms therefore
lead to the same outcome, a hold.

**All three work lanes, not two.** `WorkTasks.PIPELINE_SCOPED` names three pipeline-scoped work-item
tasks, and the one it would be easiest to overlook is the most expensive per item: `group-chunk`.
`GroupWorkerService` assembles a group's members through ADAS (`group-rows`) and then makes one model call
over them, so a group pipeline left ungated keeps paying for the largest calls the runner makes for the
whole length of a skew. It is also the lane the exception arm covers best — its ADAS call precedes its
DIAL call, so the refusal arrives before the money — which is an argument for having both arms, not for
skipping the cheap check there.

This is new machinery on these three lanes, not a reuse: `isPaused` is consulted in
`PipelineScanExecutor`, `BackfillExecutor`, `GroupReadinessSweep`, `Class1MaterializeExecutor` and
`DlqCircuitBreaker`, and none of `WorkerService`, `GroupWorkerService` or `UpsertService` consults
anything — the pause registry gates *enqueueing*, and what needs gating here is *executing*.

**"Without consuming an attempt" has to be built, not inherited.** db-scheduler increments
`consecutive_failures` on every failure, and `ChunkFailureHandler` / `UpsertFailureHandler` archive to the
DLQ once it reaches `WORKER_MAX_ATTEMPTS`. A hold that simply rescheduled on the failure arm would
therefore leave the counter elevated for as long as the skew lasts, so the *first real* failure after the
skew cleared would archive immediately — turning a deploy window into lost work at the moment it ended.
`ExecutionOperations.reschedule` writes `consecutive_failures = 0` when handed an `ExecutionComplete`
whose result is OK (verified against db-scheduler 15.6.0), so the hold branch reschedules with
`ExecutionComplete.success(...)` and a short fixed delay, taking neither the backoff ladder nor the
archive branch. The item comes back untouched, which is exactly what "held" is supposed to mean. The
branch goes in all three failure handlers (`ChunkFailureHandler`, `GroupFailureHandler`,
`UpsertFailureHandler`), each of which reads `execution.consecutiveFailures` against its own max-attempts
property today.

A hold loops for as long as the skew does, and deliberately never archives: a permanent incompatibility is
a stall to be fixed by deploying the matching pair, and the compatibility gauge is what names it.

**The hold treatment is shared, not this change's own.** `2026-09-15-unstarve-write-back` introduces the same
mechanism, for a work item due before the first registry sync. Two copies of
"held" is precisely the thing that lets the three lanes drift apart, so there is one: `WorkHold`
defines the treatment and `HeldWorkException` is the marker it dispatches on, with `AdasContractException`
extending it. A contract skew and a cold pipeline cache are then the same kind of fact — a condition the
runner is waiting out, as opposed to a failure it is retrying — and any later condition of that kind joins
the same arm rather than adding a third copy. Everything above about *why* a skew is held, and about the OK
result being the mechanism, is unchanged; only the class holding it is shared.

### 6. Structural absence is a different fact from a null value

`row.get(grainKey)` returning null conflates two things: the column is not in the page, and the column is
in the page holding null. The first is a projection skew and means *this page is not the page this lane
asked for*; the second is data. So the code tests `containsKey` first: missing key → fail the tick, hold
the cursor, log the pipeline and the column; present-but-null → skip and count.

The group lane reads a **third** required column, and it is the one where the split pays most.
`GroupMarkBuilder.versionOf` already treats an absent version column as an error — but it dead-letters the
row, so a page whose version column is simply not in the projection dead-letters every row of every page:
the DLQ flood this decision's alternative was rejected for, arriving through a path that already exists.
Under the split, a missing version *key* fails the tick like the other two, while a key present with a
null or uninterpretable value stays a dead-letter, because that is a defect in one row's data and the
group's dirtiness is genuinely unknowable for it.

Dead-lettering every row of a skewed page was considered and rejected: at 1000 rows per page and 50 pages
per fire it floods the DLQ with tens of thousands of items that are not row defects, and the recovery is
then a bulk requeue rather than a retry that fixes itself.

### 7. Cursor accounting as a structural backstop, blind to cause

`ScanPageCommitter` refuses to advance unless every row of the page is accounted for — chunked or
group-marked, dead-lettered, or skipped for a declared reason. The check is **equality**, not "at least
one": `ChunkBuilder` and `GroupMarkBuilder` route every row they read to exactly one of those outcomes, so
accounting for all N is what the code already does and asserting the weaker "not zero" would leave a
partial-accounting bug — a loop that breaks early, a branch that forgets to count — as invisible as the
one this exists to catch. Decision 6 fixes the known instance; this fixes the class, including instances
that do not exist yet. It sits in the committers because that is where a cursor advance is
persisted, and it needs no knowledge of which upstream invariant broke — which means **all three** of
them: `ScanPageCommitter`, `GroupDirtyMarkCommitter` and `BackfillPageCommitter`. The backfill shares the
page builders, so it already produces the same accounting; what it does not share is the commit, and its
cursor is the one where a silent skip is unrecoverable by waiting — a run drains a whole window and then
marks itself complete, so nothing afterwards says the window was not covered. The dead-letter
breaker cannot serve this role: it returns immediately when nothing was dead-lettered, so a page that
produced nothing never reaches it.

### 8. Two polls to confirm an empty feed

An empty feed stays authoritative — a real "last pipeline disabled" must still cancel its tasks — but it
takes a second consecutive poll, costing one poll interval. The first, unconfirmed one still marks the
pipeline sync **succeeded**: the poll did succeed, and the readiness contributor answers "has this process
ever read the registry", not "does the registry have contents". Withholding it would make a legitimately
empty registry hold a fresh pod out of readiness — and, with a poll interval longer than the startup
budget, crashloop it forever over a state that is not an error. What that removes is the single-poll path from
an unexpected empty answer to "every task cancelled, cache cleared, readiness UP, nothing reporting that
enrichment stopped". With the contract check in place the fold-era cause is already unreachable, so this is
defence in depth against the *next* cause, and it is cheap enough not to need one.

### 9. A code constant, no configuration key

No new env var, in either repo. Same reasoning as the hard-coded `view=compiled`: the only wrong setting
disables everything at once, silently. `docs/configuration.md` therefore gains nothing.

## Risks / Trade-offs

- **A pod cannot start while ADAS is behind** → by design; the previous pod keeps serving under
  `maxUnavailable: 0`, and the crashloop is the visible signal that the order was wrong. Same behaviour a
  prolonged ADAS outage already produces.
- **The constant is not raised when the feed changes** → the ADAS half carries a build-time test that pins
  the compiled projection's member set beside its constant; this side's admission arms are the runtime
  backstop if that is ever bypassed.
- **A held work item looks like a stuck queue** → the compatibility gauge is the thing to read first; the
  hold is visible as due items not executing while the gauge reads `0`. `runner.queue.depth` rises for the
  held lane, which is the same shape as a backlog; the gauge is what tells the two apart, and it is why the
  gauge is registered at startup rather than on first skew.
- **An unrelated ADAS failure reads as a skew** → the ADAS half sets the echo ahead of its security chain,
  so a `401` from a wrong API key carries it and stays an auth failure on the existing arm. The runner
  checks the echo and not the status, so it inherits that property rather than having to classify.
- **Cursor accounting could stall a pipeline on a legitimately empty result** → it cannot: a page with no
  rows is unaffected, and every deliberate exclusion (sampling, a null in a required column) is a counted
  skip, which accounts for its row.
- **One poll interval of delay before a genuine "all disabled" takes effect** → accepted; nothing acts on
  a disabled pipeline in the meantime except its own already-scheduled tick, which ADAS refuses.

## Migration Plan

Ships in the same window as the ADAS half, which is not optional. The window is unchanged — capture the
baseline, confirm no backfill is `RUNNING`, scale the runner to 0, deploy ADAS, deploy the runner, scale to
1 — and this change is about what happens if it is not followed:

- **ADAS first:** this service's predecessor sends no header and is refused `409` on every data-plane
  call. That order is *recoverable*, not free, and it is worth being exact about which parts of it are
  which, because the predecessor has none of the machinery above:
  - **Cursors are untouched.** The scan and materialize ticks fail on the refused call, before anything is
    committed, and re-fire on cadence.
  - **Model calls do not stop.** A chunk already in the queue when ADAS lands makes no ADAS call before its
    DIAL call, so the predecessor derives it and charges for it; only the write that follows is refused.
    That is the cost this change's paid-work gate removes, and it is the reason the gate exists.
  - **A refused write-back is archived on its *first* attempt, not after `WORKER_MAX_ATTEMPTS`.** `409` is
    not in `RetryableStatus`, so the predecessor takes its permanent-rejection branch straight away: the
    item is dead-lettered with its computed rows (and, for a group write, stamped and charged). Requeue
    those with the pipeline **enabled**, or the stored item is destroyed.
  - **A group item's `group-rows` call is refused the same way, and the group is stamped clean.** The
    predecessor reads a non-retryable `4xx` there as a poison group: dead-lettered, stamped computed so the
    sweep does not loop on it, and counted against the DLQ circuit breaker — which can auto-pause the
    pipeline, and does not reset itself. So an ADAS-first window on a group pipeline leaves work to requeue
    *and* possibly a pipeline to un-pause. Nothing is lost that a requeue cannot recover, but nothing here
    recovers on its own either.
- **Runner first:** this build sees no echo, never completes a sync, never reports ready, and is restarted;
  the outgoing pod keeps serving against the ADAS it matches until ADAS lands.

Both halves hard-code the version and nothing at build time can compare one repo's constant with the
other's, so the pair's equality is verified where both builds are observable at once: the post-deploy curl
of a data-plane endpoint through the in-cluster Service URL, with and without the header, while the runner
is still at 0. This side's tests assert against `AdasContract.VERSION` rather than the literal, so a bump
moves them with it instead of leaving a fixture that pins the old number.

No PostgreSQL migration, so there is no state to snapshot for this change specifically and nothing to
reverse. A rollback to the previous image is possible only while ADAS is also rolled back — the same
coupling every release in this pair has.

## Open Questions

None blocking. The in-flight `fold-evaluator-into-pipeline` change on the ADAS side will raise the contract
version, and the admission arms added here should be re-read against whatever projection it serves — an
entry with no separately-addressed evaluator must still be refused rather than routed.
