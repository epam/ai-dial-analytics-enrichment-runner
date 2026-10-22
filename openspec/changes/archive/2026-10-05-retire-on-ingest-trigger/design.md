## Context

The paired ADAS change (`analytics-data-access-service`, `openspec/changes/retire-on-ingest-trigger/`,
already applied there) does four things:
- retires `trigger.kind: on_ingest` (a 422 on write);
- retires `advanced.scan_every` (a 400 on write);
- stores a default cron `S * * * * *` on any enrich `schedule` trigger that omits one, with `S` derived
  from an MD5 of the name;
- raises the runner contract to **3**.

Its migration `V44` rewrites every stored `ON_INGEST` row to `SCHEDULE` with a cron derived from its old
`scan_every`, and strips `scan_every` from every enrich spec, `group` included. It does not bump
`generation`.

For example, a `sql` `on_ingest` pipeline at `60s` becomes `S * * * * *` with its own name-derived `S`, and a
`group` pipeline that declared `scan_every: 5m` loses that knob.

Today the runner's `ScheduleFactory.forPipeline` returns `CronSchedule` for `SCHEDULE`. For everything
else it returns `Schedules.fixedDelay(scanEvery ?: lane default)`, where the default is
`SCAN_DEFAULT_CADENCE=30s` for `pipeline-scan` and `CLASS1_DEFAULT_CADENCE=5m` for `class1-materialize`.
`ScheduleFactory.cadence()` feeds the status heartbeat (`PipelineStatusResolver`) with the same rules.

## Goals / Non-Goals

**Goals:**
- Speak contract 3 and read the contract-3 feed: no `scan_every`, no `on_ingest` in practice.
- One clock per lane: cron for row grain (both task names), `group.scan-cadence` for the group mark scan.
- No runner state migrated, and nothing an operator must do beyond upgrading both services together.

**Non-Goals:**
- Changing the readiness sweep (`GROUP_SWEEP_INTERVAL`), `ready_when` semantics, or any work-item lane.
- An ADAS-side floor on `ready_when.idle` relative to the group cadence. That is left open in the ADAS
  design; at most this change logs a hint (see "Delete the two lane defaults outright").
- Jitter or spreading in the runner. ADAS already spreads the default cron by name.

## Decisions

### Row grain is cron-only on both lanes; a cronless `schedule` pipeline is refused at admission

`forPipeline` becomes:
- `SCHEDULE` → `CronSchedule(cron, UTC, SPRING53)`;
- `GROUP` → `fixedDelay(group.scanCadence)`;
- anything else → `IllegalArgumentException`, which the per-pipeline reconcile isolation already turns
  into "skipped, logged". The admission gate (below) means this branch is never reached in practice.

A `schedule` pipeline with no cron, or with a cron either of the runner's parsers rejects (db-scheduler's for the
instance, Spring's for the heartbeat cadence), is refused by the admission gate, as is an entry with no
trigger at all. None of them is left to the throw in `forPipeline`. A throw in reconcile is logged and skipped, but the pipeline is still cached as healthy, and
`ScheduleFactory.cadence()` throws for the status heartbeat too. The throw in `forPipeline` stays as a
defensive backstop. After V44, ADAS never serves a cronless `schedule` row, so falling back to a runner
default would hide a producer bug.
Rejected alternative: keeping a fixed-delay fallback for a cronless `schedule` pipeline. That is the
second clock again, under a different trigger.

### `group.scan-cadence` on `GroupProperties`, default `1m`

It's a `Duration` member of the existing `GroupProperties` record (`GROUP_SCAN_CADENCE`), so it binds with
the rest of the group lane's configuration. `1m` keeps marking well inside the `10m` default idle
(`GROUP_DEFAULT_IDLE`) and a typical declared idle such as `30m`. A mark tick reads only the
group key, version and signal, never a heavy column, and when caught up it is one keyset probe.
A group pipeline that scanned every `5m` moves to `1m`: cheaper marks per tick, and fewer premature evaluations.

Rejected alternatives:
- Deriving the cadence from each pipeline's `idle` (e.g. `idle / 10`). It is cleverer, but invisible, and a
  pipeline declaring only `signal` has no idle of its own.
- Reusing `GROUP_SWEEP_INTERVAL`. The mark scan and the sweep are different work with different costs,
  so tying them together forces one to move whenever the other is tuned.

### `TriggerKind.ON_INGEST` stays modelled, refused by `PipelineRouting.unsupportedReason`

The enum's own Javadoc states the rule: every value ADAS can send is modelled, so one bad entry cannot fail
the whole feed parse. Under contract 3 ADAS never sends it, but the gate refuses it explicitly ("trigger kind
'on_ingest' is retired"). Without that, it would reach `ScheduleFactory` and fail there, after being cached
as healthy. The arm goes first, beside the "no transform" arm, because it is a property of the entry and
not of its lane. A second arm refuses a `schedule` entry with a blank `trigger.cron` (see "Row grain is cron-only"), for the same
reason: refused entries never reach the cache.

### Delete the two lane defaults outright

`scan.default-cadence` and `class1.default-cadence` are removed from `ScanProperties`, `Class1Properties`,
`application.yml` and `docs/configuration.md`. A deployment that still sets `SCAN_DEFAULT_CADENCE` or
`CLASS1_DEFAULT_CADENCE` is silently ignored by Spring. The upgrade guide says so. No startup check is
added for a dead variable.
Optional, cheap: at sync time, `WARN` once per generation when a group pipeline's `ready_when.idle` is below
`5 × group.scan-cadence`. Implement it only if it fits the existing once-per-generation logging idiom
(`refusedGenerations`) without new state. Otherwise skip it and note that in the tasks.

### No runner state migration; reconcile converges on the stored schedule, not on generation alone

Today `PipelineSyncService.reconcile` reschedules when `cached == null || generation changed`, and the cache is
empty after a restart, so the first poll looks like it reschedules everything. It does not reliably do so:
- db-scheduler's `reschedule` throws `TaskInstanceCurrentlyExecutingException` while the row is `picked`;
- a scan killed by the scale-to-0 step stays picked until declared dead (`SHUTDOWN_TIMEOUT=30s`, heartbeat
  45s × 6 missed ≈ 4.5 min), while the first poll comes about 10s after start;
- the per-pipeline `catch` logs and skips, but `cache.replaceAll(desired)` still caches the pipeline at its
  unchanged `generation`, so no later poll retries;
- `ReviveDeadExecution` then revives the row with the `FixedDelay` persisted in `task_data`
  (`RecurringTaskWithPersistentSchedule`), and it keeps the old cadence until some unrelated bump.

So reconcile SHALL reschedule when the instance's stored `PlainScheduleAndData` differs from the desired one
(`FixedDelay`, `CronSchedule` and `PlainScheduleAndData` implement `equals`, and the default `JacksonSerializer`
round-trips them equal), read from the same `fetchScheduledExecutionsForTask` pass that already lists instance
ids. A generation bump still reschedules. Convergence then no longer depends on the restart: a picked instance is
hidden from the listing (`withPicked(false)`), so `scheduleIfNotExists` returns false or `reschedule` throws
`TaskInstanceCurrentlyExecutingException`, and both are left to the next poll's comparison. No
`scheduled_tasks` rewrite or Flyway migration is needed. Verify locally: the stored `task_data` of a formerly
fixed-delay instance becomes a `CronSchedule`, including when the instance was picked at the first poll.

Two follow-ons from review:
- `cached == null` (a restart) no longer reschedules on its own. With the comparison it is redundant, and it was
  harmful: rescheduling a cron instance moves an overdue fire (a daily pipeline that was due during the downtime)
  to its next slot, and clears `last_success`, `last_failure` and `consecutive_failures`.
- A pipeline whose reconcile threw stays cached at its current declaration. An earlier draft left it out of the
  cache (or at its previous generation) so the generation path would retry it. That is unnecessary, because the
  create path and the comparison already retry every poll. It is also harmful: after a restart a failed pipeline
  would be absent from a populated cache, and `WorkerService` drops an absent pipeline's queued chunks
  irreversibly (the cursor has already advanced past them).

Rejected alternative: asking ADAS to bump `generation` in V44. That discards every Class-1 materialized
position, which is exactly what the ADAS migration avoids.

### Contract 3

`AdasContract.VERSION = 3`. The interceptor and the echo check are unchanged, and the bump is exactly what
they enforce. Every test that pins the header value reads the constant.

## Risks / Trade-offs

- **[A stale `SCAN_DEFAULT_CADENCE` override in a Helm values file]** → harmless (ignored). Documented in the
  upgrade guide, and removed from charts when found.
- **[A group pipeline's scan cadence 5m → 1m]** → five times as many mark ticks, each one a cheap keyset
  probe over a filtered `dial_usage_log` page without bodies. Expect fewer premature re-evaluations, which
  lowers model spend.
- **[Runner deployed before ADAS]** → the contract-3 runner is refused by the contract-2 ADAS, and holds its
  work. That is the existing guard, unchanged.
- **[A cronless `schedule` pipeline from a misbehaving producer]** → refused at admission with a counted
  reason, the others unaffected.
- **[Schedule comparison reschedules every poll]** → only if a stored schedule never equals the derived one
  (for example a serialization that loses the zone). The unit test pins equality for both schedule kinds, and
  a reschedule that keeps happening shows up in the `rescheduled` count of the per-poll INFO line.

## Migration Plan

Ship with ADAS release 0.3 as a pair: scale the runner to 0, deploy ADAS (V44 runs), deploy this runner,
scale it to 1. Before the window, drop any `SCAN_DEFAULT_CADENCE` / `CLASS1_DEFAULT_CADENCE`
overrides and optionally set `GROUP_SCAN_CADENCE`. After the window, check that the migrated `sql`
pipelines' `materialized_through_*` did not reset, and that group pipelines keep labelling.

## Open Questions

- Whether to ship the optional idle-vs-cadence warning (see "Delete the two lane defaults outright"). It's an implementer's call, recorded in tasks.
