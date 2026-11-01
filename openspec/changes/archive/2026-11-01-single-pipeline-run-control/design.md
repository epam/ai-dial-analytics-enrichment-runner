## Context

ADAS makes its `enabled` flag the single run control for a pipeline (ADAS change `single-pipeline-run-control`,
see its `pipeline-registry` spec, "A pipeline has exactly one run control"). It also stops bumping
`generation` on a patch that only changes `enabled`. This runner currently differs in three ways:

- **It polls `enabled=true`.** A disabled pipeline therefore looks the same as a deleted one. The pipeline
  leaves the cache, its recurring task is cancelled, and every lane treats it as gone. Queued chunks are
  dropped, and their rows are never re-read because the cursor advanced in the same transaction that
  enqueued them. Group items are dropped and re-derived later. Paid upserts are archived for a manual requeue.
- **It has its own operator pause.** `PipelinePauseRegistry` is in memory and keyed by name, and an
  OPERATOR-origin pause never expires. It only gates *enqueueing*: queued items still run, and a restart
  clears every pause.
- **The DLQ circuit breaker** uses the same registry with origin BREAKER. Its pauses expire, and the window
  widens on each consecutive trip.

The "held" mechanism already exists (`WorkHold`). It reschedules a work item after a fixed delay without
using an attempt and without archiving it. Today it covers a cold cache and ADAS contract skew.

## Goals / Non-Goals

**Goals:**
- The runner obeys ADAS `enabled` and has no stop control of its own.
- A stop loses no work, and a restart continues from the same cursor and the same queued work.
- The breaker keeps working as it does now, and can still be lifted early.

**Non-Goals:**
- Reporting runner status to ADAS. The UI keeps reading `/runtime`.
- Auto-resuming backfill runs when their pipeline restarts. A lifted condition never resumes a run on its own.
- Making the breaker pause durable or multi-replica.

## Decisions

### Poll the unfiltered feed and cache stopped pipelines

The runner has to tell a stopped pipeline from a deleted one: it keeps the first one's work and drops the
second's. The unfiltered compiled listing already carries `enabled` and the full resolved entry for a
disabled pipeline, and ADAS omits incomplete or unservable declarations whatever the flag. So an entry with
`enabled: false` becomes a cached, *stopped* pipeline, and absence keeps its present meaning. Admission runs
the same way for both states. A stopped entry the runner cannot execute is refused and stays absent, as it
would if enabled.

*Rejected — a per-pipeline lookup on disappearance:* a new ADAS call, and a race between the two reads.
*Rejected — polling `enabled=false` as well:* two snapshots that can disagree with each other.

The sync compares `enabled` as well as `generation`. With ADAS no longer bumping `generation` on a flag-only
patch, comparing `generation` alone would never notice a stop.

### Hold a stopped pipeline's queued work; don't drop or drain it

Each work-item lane checks the cached pipeline's state before doing any paid work. A stopped pipeline's item
throws the existing held exception, so all three lanes share one meaning of "held": rescheduled after
`WorkHold.DELAY`, without using an attempt, never archived. The upsert lane checks again right before the
write, as it already does for absence.

*Rejected — drain (let queued work finish after the stop):* ADAS refuses writes to a disabled pipeline with
409, so drained results would be dead-lettered. Loosening that rule would let a stopped pipeline write.
*Rejected — a separate "parked" state or table:* the hold already reschedules without cost to the attempt
budget. A second mechanism would need its own recovery path.

A pipeline stopped for a long time re-holds its queued items every hold interval. The backlog is bounded by
the queue backpressure that existed before the stop, and `HeldWorkLog` already reduces repeated holds to one
line per interval, so the cost is a few db-scheduler picks per item per interval. A longer hold delay for this
cause would cut that, but every restart would then wait up to the longer delay before work resumed. The
shared delay is kept.

The stale-declaration guard does not change. A held item built against generation *N* still runs after a
restart because ADAS keeps *N* across the stop. If the declaration was edited while the pipeline was stopped,
the item is dropped as stale, as it is today.

### Remove the operator pause; keep `resume` for breaker pauses only

Once ADAS owns the stop, a runner-side operator pause is a second switch that can drift, and it is lost on
every restart. `POST …/pause` is removed and the OPERATOR origin goes with it. The breaker is different: it is
the runner's own inference and it expires, so it stays. An early lift is still needed when an upstream has
been fixed, or when expiry is configured off. So `POST …/resume` remains and lifts a breaker pause only. It
cannot stop anything, so it is not a second on/off switch. A UI shows it as "Resume now" on the auto-paused
badge. When there is no breaker pause it answers 409 rather than 204, because a silent success would hide a
caller still using the old pause/resume pair.

*Rejected — a stop→start in ADAS lifts the breaker:* a stop and start within one poll interval is never
observed, so it would fail unpredictably.
*Rejected — expiry only:* this removes the escape hatch for a non-positive window, and makes an operator who
fixed the upstream wait up to the maximum window.

`origin` stays on the paused listing and on the runtime view, always `breaker`, so existing clients keep
parsing.

### Backfill gets its own stop reason

A run paused because its pipeline stopped needs a reason that is neither "deleted" (`pipeline_absent`, whose
resume answers 404) nor the retired operator pause. Flyway `V14` adds `pipeline_stopped` to the check and keeps
`pipeline_paused_operator` permitted, because existing rows may carry it. The new value goes through a new
migration, since an applied one is checksummed. Starting or resuming a run on a stopped pipeline answers 409,
the same as a breaker-paused pipeline, because it is a state conflict and not a missing resource.

### Observability

A stopped pipeline is a legitimate steady state. Its heartbeat line is INFO, and a new
`runner.pipelines.stopped` gauge counts it. Per-pipeline gauges such as scan lag are evicted when it stops, as
they are when a pipeline leaves the cache, so a stopped pipeline does not show a lag that only climbs. Both
`runner.pipelines.paused` and `runner.pipelines.paused.auto` stay. They now count the same breaker set, and
existing alerts on `paused.auto` keep working.

## Risks / Trade-offs

- **Older ADAS (still bumps `generation` on flag-only patches)** → held items are dropped as stale on restart.
  That is the same outcome as today, so ADAS ships first.
- **Writes already in flight when the stop lands** → an item that called the model before the next poll
  saw the stop gets ADAS's 409 `pipeline_conflict`. Today that write would be dead-lettered and counted
  against the breaker at a 100% row failure rate, so stopping a busy pipeline could trip the breaker. The
  write lane holds it instead: no DLQ row, no group stamp, no breaker count. ADAS pins `pipeline_conflict`
  on its write paths to mean "disabled" only, so the code is safe to rely on.
- **Rate-limiter bucket and breaker counters across a stop** → kept. A restart resumes with the outcome
  window the breaker had, and an evicted window would hide a failing upstream for one window's length.
- **Breaking admin API** → any script or UI that calls `POST …/pause` gets 404. The upgrade note covers it,
  and the UI change drops the buttons.
- **Group-state eviction now sees stopped pipelines** → `GroupStateEvictor` walks cached pipelines, so a
  stopped pipeline's old groups age out on the usual TTL instead of being frozen. That is accepted, since
  eviction is retention and not progress.

## Migration Plan

1. Deploy ADAS first.
2. Before deploying the runner, read `GET /v1/pipelines/paused`. For each entry whose `origin` is `operator`,
   set `enabled: false` in ADAS. The deploy restarts the runner and clears all pauses anyway.
3. Deploy the runner. `V14` runs at startup.
4. **Rollback:** the old image reads `enabled=true` again and ignores stopped pipelines. Runs paused with
   `pipeline_stopped` stay readable, and the old image's resume clears the reason.

## Open Questions

_None._
