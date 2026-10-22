## Why

When a ClickHouse outage makes a group pipeline's groups dead-letter, the DLQ circuit breaker does its
job and auto-pauses the pipeline. The outage ends. The pause does not. It holds until an operator
notices, which can be many hours, and nothing says so:

- the pause lives in an in-memory `Set<String>` (`PipelinePauseRegistry`) with no reason, no timestamp and
  no expiry, and `resume` is only ever called by an operator hitting `POST /v1/pipelines/{name}/resume`;
- it is announced once, in the `log.warn` on the line that trips it, and never again — every subsequent
  sweep says `Pipeline … is paused; skipping group sweep` at **debug**;
- ADAS, which is where anyone looks first, knows nothing about a runner-side pause: it reports the
  pipeline `enabled` with no run position, which is indistinguishable from "working, nothing new to do";
- `runner.pipelines.paused` exists but is a bare count with no origin, so it cannot answer "is this a
  deliberate operator pause or a breaker that forgot to let go?" — the one question that mattered.

Every surface reads healthy while the pipeline writes nothing. The breaker converts a bounded upstream
outage into an unbounded silent stop, which is a worse failure than the one it was protecting against.

## What Changes

- **An auto-pause expires; an operator pause does not.** A pause carries its origin, and a breaker-origin
  pause resumes on its own after a window. The distinction is the whole point: an operator pause is a
  decision and must outlive anything the runner thinks; a breaker pause is a *guess about an upstream*,
  made from a failure rate, and a guess that cannot expire is indistinguishable from a bug.
- **The window widens on repetition, and resets on recovery.** The breaker drops its outcome window when
  it trips, so a resume is judged on a fresh one — which means a pipeline auto-resumed into a still-broken
  upstream spends a full window of model calls to reach the same verdict. Doubling the pause on each
  consecutive auto-trip, to a cap, bounds that waste to a logarithm of the outage length instead of a
  multiple of it. A pipeline that completes a full window without tripping is recovered, and the next
  outage starts again from the shortest window.
- **A pause says what it is.** The registry stores origin, reason, the instant it started and, for an
  auto-pause, when it resumes; `GET /v1/pipelines/paused` reports all of it, and the runner logs a
  recurring warning naming every paused pipeline and how long it has been paused. A condition that is
  announced once and then only at debug is a condition nobody finds.
- **The metric distinguishes the two.** `runner.pipelines.paused.auto` alongside the existing total, so an
  alert can be written on the state that is never intended to persist without firing on the one that is.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `resilience-and-dlq`: auto-pause expiry, widening on repetition, reset on recovery, and the origin the
  breaker records.
- `runner-ops`: what the paused listing reports, and the recurring warning.
- `observability`: the auto-paused gauge.

## Impact

- **Code**: `PipelinePauseRegistry` (a record per pause, a clock, expiry, the recurring log),
  `DlqCircuitBreaker` (origin and reason on trip, recovery reset), `PipelineAdminController`,
  `PausedPipelinesResponse` plus a `PausedPipelineDto`, `RunnerMetrics`.
- **API (breaking, ops surface)**: `GET /v1/pipelines/paused` returns objects rather than bare names —
  `{paused:[{pipeline_name, origin, reason, since, resumes_at}]}`. The endpoint is operator-facing and has
  no machine consumer in either repo; a name-only listing cannot tell a deliberate pause from a stuck one.
- **Configuration**: `DLQ_BREAKER_PAUSE_FOR` (`15m`) and `DLQ_BREAKER_PAUSE_MAX` (`4h`). Setting
  `DLQ_BREAKER_PAUSE_FOR` to zero disables expiry and restores today's behaviour, for an operator who
  wants a trip to be sticky.
- **Not affected**: what trips the breaker, the outcome-window arithmetic, the DLQ shape, PostgreSQL
  schema. The pause remains in-memory and single-replica — a process restart still clears it, which this
  change does not pretend to fix and which the single-replica topology already assumes.
