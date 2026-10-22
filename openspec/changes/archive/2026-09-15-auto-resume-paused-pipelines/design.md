# Design — an auto-pause that lets go

## What the breaker is actually asserting

`DlqCircuitBreaker` trips on a *rate*: a full window of recent outcomes of which too large a fraction
dead-lettered. That is a statement about the world outside the runner — a model endpoint is refusing, an
upstream cannot serve, a pipeline was mis-bound. Two of those three clear without anybody touching the
runner.

The pause it produces has no such qualification. It is a `String` in a `Set`, and the only code that
removes it is an operator's `POST /resume`. So the breaker asserts "something is wrong right now" and
records "something is wrong forever". The gap between those two is what lets a bounded outage become an open-ended stop.

An operator's pause is different in kind, not in degree: it is a decision, taken with knowledge the runner
does not have, and it must survive everything the runner concludes. Storing both in the same
undifferentiated set is what makes expiring either of them unsafe — which is presumably why neither
expires.

## Decision 1 — origin decides expiry

`PipelinePauseRegistry` stores a `Pause(origin, reason, since, resumesAt)` per pipeline. `resumesAt` is
null for `OPERATOR` and set for `BREAKER`. `isPaused` is the single read path — the scan executor, the
sweep, the materialize executor and the breaker itself all go through it — so expiry is evaluated there,
lazily, against the injected `Clock`: an expired auto-pause is removed and logged at WARN on the way out.

Lazy rather than a scheduled reaper because the read is the only moment the answer matters, there is no
window in which a stale entry is observable, and it keeps the registry free of a second scheduled
component. The recurring warning (Decision 3) is scheduled, but it only reports; it never decides.

## Decision 2 — widen on repetition, reset on recovery

A resume into an unfixed upstream is not free. The breaker drops its outcome window on trip, deliberately,
so that recovery is judged on fresh evidence — which means the resumed pipeline must produce a *full
window* of outcomes before it can trip again. At the shipped `DLQ_BREAKER_GROUP_WINDOW` of 50, that is
fifty group evaluations: fifty ADAS assemblies and fifty model calls, spent to re-derive a verdict the
runner held fifteen minutes earlier.

A fixed expiry therefore trades one unbounded failure for an unbounded cost. The fix is the standard one:
each *consecutive* auto-trip of the same pipeline doubles the pause, `pauseFor × 2^(attempts−1)`, capped
at `pauseMax`. An hour-long outage costs four probes instead of four; a day-long one costs eight instead
of ninety-six. The cap exists so the pause never grows past the point where an operator would rather be
told than waited for.

"Consecutive" needs a definition, and the outcome window already provides one: a pipeline that fills a
full window **without** tripping has demonstrated recovery on the same evidence the trip was made from, so
the attempt counter resets there. Nothing else resets it — in particular an operator's manual resume does
not, because a manual resume is not evidence about the upstream.

### Alternatives rejected

- **A fixed expiry with no widening.** Simplest, and it is what the "no auto-reset" complaint literally
  asks for. Rejected because at a 50-outcome window it converts a long outage into a steady drip of paid
  model calls — roughly two hundred an hour at the shipped defaults — which is a new way to waste money
  quietly, and quiet waste is the failure this change is about.
- **Half-open: resume, but re-trip on a shortened window.** Cheaper probes, and genuinely the textbook
  shape. Rejected for this change because it means two window sizes and a state machine inside
  `OutcomeWindow`, whose arithmetic is load-bearing and was itself the subject of a recent fix. Widening
  the pause achieves the same bound on wasted calls without touching the window.
- **Persisting the pause.** It would survive a restart, which today's does not. Rejected as a different
  problem: the runner is single-replica by design, a restart already clears every in-memory pause, and a
  persisted pause with no expiry would make a stuck auto-pause *worse*, not better.
- **Probing the upstream directly before resuming.** The runner has no upstream health it can ask about
  that is not itself a paid call, and a synthetic probe would be a second, differently-behaved code path
  to the same endpoints.

## Decision 3 — a state nobody can see is not a state

Three surfaces, because a silent auto-pause defeats three different kinds of looking:

1. **The listing.** `GET /v1/pipelines/paused` returns `{pipeline_name, origin, reason, since,
   resumes_at}`. A name-only listing shows `["session-insights-live"]`
   and tells the reader nothing they did not already suspect. `origin` and `since` are the two fields that answer
   the question actually being asked.
2. **The log.** A recurring WARN, on a fixed delay, naming every paused pipeline with its origin, reason
   and age. The trip line already exists and is fine; what was missing is that the *condition* was only
   ever announced as an *event*. A condition has to keep saying so.
3. **The metric.** `runner.pipelines.paused.auto` beside the existing total. The total cannot be alerted
   on, because an operator pause is a legitimate steady state and would keep the alert permanently lit.
   The auto count has no legitimate steady state, which is exactly what makes it alertable.

The health endpoint is deliberately **not** one of them. The runner's readiness group is
`readinessState,db,pipelineSync`, and ADAS's own liveness reads an aggregate health — a DOWN-capable
contributor for a paused pipeline would take a pod out of service, or restart it, over a condition that is
sometimes intended. A paused pipeline is an operational fact, not an unhealthy process.

## Risks

- **An auto-resume into a genuinely mis-bound pipeline** re-derives a window's worth of dead-letters on
  each probe. Bounded by the widening, visible in the DLQ, and the pipeline is still paused far more of
  the time than it is running. The alternative — staying paused forever — is the failure this change
  exists to remove.
- **The listing's shape changes.** It is an operator endpoint with no machine consumer in either repo, and
  the OpenAPI document regenerates with it.
- **A long `pauseMax` looks like the old bug.** Capped at four hours by default for that reason: long
  enough to ride out a real outage, short enough that nobody mistakes it for a stuck pause.
