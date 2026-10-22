# Enrichment Runner release-0.2 — upgrade notes

How to move a runner to **0.2.2**. Pick your starting point:

- **Running 0.2.1** — read [§0](#0-from-021-to-022) only. 0.2.2 changes logging: a plain redeploy, with
  no migration and no ADAS change.
- **Running 0.2.0 or release-0.1 (0.1.2)** — read §1–§5. They take you straight to 0.2.2 in one window,
  including everything in §0. Steps and changes marked *0.1.2 only* can be skipped when you are already
  on 0.2.0.

From 0.2.0 or 0.1.2, the runner moves as a pair with **ADAS 0.2.2 or later** — follow ADAS's own
`docs/release-0.2-upgrade.md` for its side. Neither build works with the other's previous release, so
both are upgraded in one window. The main change, from 0.2.1, is ADAS's **evaluator fold**: an
enrichment is now defined on its pipeline as a `transform` block, and the runner reads it from there.

---

## 0. From 0.2.1 to 0.2.2

0.2.2 changes logging only. There is no migration and no API change, and the ADAS contract version
stays `2`, so ADAS is not touched and pipelines can be edited as usual. Going back to 0.2.1 is safe.

1. **Remove `DLQ_PAUSE_REPORT_INTERVAL`** from your values. A value left set is ignored.
2. **Optional: add the log-level ConfigMap** described in §1, "Before the window", step 4.
3. **Redeploy the runner** — scale to 0, deploy 0.2.2, scale back to 1, as the runner runs
   single-replica.
4. **Verify:** the pod is Ready, and each pipeline logs a `Pipeline status` line within
   `STATUS_REPORT_INTERVAL` (default `5m`).

What changed is in §3, "Logging"; the new settings are in §5, "Configuration".

---

## 1. Upgrade procedure

### Before the window — on the old build

1. **Requeue the DLQ items you want replayed**, with their pipelines enabled, and let them drain.
   After the upgrade, items dead-lettered by 0.2.0 or 0.1.2 can no longer be requeued
   (`422 dlq_not_requeueable`); their rows stay in `dlq_item.payload` for a hand replay or a backfill.
2. **Make sure no backfill is running** — cancel it or let it finish. Migration `V10` fails if a
   pipeline has two running backfills; this query must return nothing:

   ```sql
   SELECT pipeline_name, array_agg(id) FROM run_history WHERE state = 'running'
   GROUP BY pipeline_name HAVING count(*) > 1;
   ```

3. **Record `runner.scan.lag.seconds` and `runner.group.dirty`** per pipeline, so you can check
   afterwards that no state was reset.
4. **Optional: set log-level ConfigMap** (§3, "Logging"). The runner starts without it.
   - key `logging.levels.json`, starting content `{"loggerLevels": {}}`;
   - mounted **as a directory** at `/app/log-config`, not with `subPath`, which never updates;
   - `optional: true`, not immutable;
   - `LOG_CONFIG_PATH` left at its default.

### The window

1. **Don't touch pipelines** — no edit, no enable, no disable — until step 3 of "After the window".
   Any change drops in-flight work (§3).
2. **Scale the runner to 0** and wait for the pod to be gone. A rolling update is not enough: the new
   pod migrates Postgres on startup, and a surviving old pod then fails every spend and DLQ write.
3. **Deploy ADAS 0.2.2 or later.** While it rolls, make **no `POST`, `PATCH` or `DELETE` on
   `/v1/pipelines`** from anyone — an operator, a script or `adas-mcp`. An old ADAS pod handling a
   write silently rewrites the pipeline in the old shape.
4. **Deploy runner 0.2.2.** Migrations `V9` and `V10` run at startup (§5).
5. **Scale the runner back to 1.** Readiness stays DOWN until the first pipeline sync succeeds, so a
   Ready pod confirms the pair matches. Size the startup probe to cover
   `ADAS_PIPELINES_INITIAL_DELAY_MS` plus one `ADAS_PIPELINES_POLL_INTERVAL_MS`.

**While the runner is at 0**, nothing is lost — no scan, no write-back, no DIAL spend. ADAS's Class-1
materialize positions stop advancing, so dependent rollups lag until a cadence after the runner is
back. Keep the window short.

### After the window

1. **Let the carried-over queue drain.** Work queued by the old build is picked up automatically
   (§3). It has drained when `runner.workitem.prefold` stops rising and `runner.queue.depth` is back
   to its usual level.
2. **Verify** (metrics in your monitoring, spelled as in §5):
   - the pod is Ready and `runner.adas.contract.compatible` is `1`;
   - `runner.pipelines.cached` equals the number of enabled pipelines you expect;
   - `runner.pipelines.unsupported` is `0` and there are no `Refusing pipeline` WARNs;
   - `runner.scan.lag.seconds` is back near its recorded value plus the length of the window, and
     `runner.group.dirty` near its recorded value — a reset would show far larger numbers;
   - every enrich target has a `pipeline_generation` column, and new rows fill it (§2).
3. **Pipelines may now be edited again.** Update scripts and clients for §2, and log parsers and
   alerts for the new log format (§0, step 3).

**Rollback is closed.** A runner older than 0.2.1 against ADAS 0.2.2 either does nothing (0.2.0) or
overwrites paid-for labels with NULLs (0.1.2). Fix a failing new image forward.

---

## 2. What breaks for scripts and clients

| Change | Since | What to do |
|---|---|---|
| `POST /v1/evaluators` is gone (`405`); an enrichment is defined in the pipeline's `transform` block (`type`, `model`, `preset`, `params`, `request_template`, `inputs`, `outputs`) | 0.2.1 | Register enrichments through `/v1/pipelines` only. Worked bodies: [`e2e-runbook.md`](e2e-runbook.md) §2, §8, §9. |
| `evaluator_name`, `evaluator_version` and top-level `vars` on a pipeline are rejected (`400`) | 0.2.1 | Use `transform.inputs` in place of `vars`. |
| Write-back stamps `pipeline_generation` instead of `evaluator_version`; ADAS refuses a row naming `evaluator_version` (`422`) | 0.2.1 | Nothing for existing targets — see below. |
| `GET /v1/pipelines/cache` no longer reports `evaluator_version` | 0.2.1 | Read `generation`. |
| Backfill runs lose `rows_total` (it was never populated) | 0.2.1 | Stop reading it. |
| Backfill pause or cancel on a run in the wrong state answers `409` (was `200`) | 0.2.1 | Handle the `409`. |
| Backfill start and resume are refused up front: paused or over-budget pipeline `409 backfill_conflict`, cache not yet synced `503 pipeline_cache_cold` (was `404`) | 0.2.1 | Retry the `503` after the first sync. |
| `GET /v1/pipelines/paused` returns objects instead of names | 0.2.0 | Update the parser — example below. *0.1.2 only.* |

**The `pipeline_generation` column needs no manual step.** ADAS adds it at startup to every enrich
target that lacks it (`ENRICHMENT_PROVENANCE_RECONCILE_ENABLED`, default `true`). Don't patch schemas
by hand. If a target is missing it, look for the warning in ADAS's startup log or a non-zero
`adas.enrichment.provenance.reconcile_failed`. The old `evaluator_version` column keeps its values;
rows written before the upgrade get no `pipeline_generation`.

`GET /v1/pipelines/paused`, before and after:

```jsonc
// 0.1.2
{"paused": ["session-insights-live"]}

// 0.2
{"paused": [{"pipelineName": "session-insights-live", "origin": "breaker",
             "reason": "...", "since": "...", "resumesAt": "..."}]}
```

`origin` is `operator` or `breaker`; only a `breaker` pause has `resumesAt`.

---

## 3. Behaviour changes

### Any pipeline change drops in-flight work (0.2.1)

ADAS now bumps a pipeline's `generation` on **every** accepted change — `enabled`, `filter`,
`trigger`, any `advanced.*` setting, and a description edit on an `llm` target's columns. The runner
keys its work on `generation`, so after a change:

- queued chunks are dropped for good (the scan has already moved past them; only a backfill brings
  those rows back);
- pending write-backs are dropped, although their model calls were already paid for;
- queued group items are dropped, and the sweep re-derives the groups;
- DLQ items from before the change are dropped when requeued — **requeue before you edit**;
- a running backfill pauses with `pipeline_changed`.

Change a pipeline when its queue is shallow. The daily spend budget is not reset by a new generation.

### Work queued by the old build is carried over (0.2.1)

The new build recognises work queued before the upgrade and handles it:

| Work item | Handling |
|---|---|
| Scan chunk | **Run** under the pipeline's current definition. |
| Write-back | **Written** — the model call was already paid for. |
| Group item | **Discarded**; the group is still dirty, so the sweep re-derives it. |

Each is counted on `runner.workitem.prefold{lane}` (`chunk`, `upsert`, `group`) and logged at WARN.
Two ways it can still end in the DLQ:

- a write-back whose pipeline is disabled or deleted is archived and **can't be requeued** — this is
  why pipelines stay enabled through the window;
- a chunk whose inputs no longer fill the current template dead-letters at stage `input_map`, before
  any model call. This only happens if the pipeline was edited during the window.

### Templates that can't be filled fail before the model call (0.2.1)

A `{{placeholder}}` with no bound value dead-letters that row — stage `input_map`, or `group_map` on
the group lane — with no DIAL call and no spend. ADAS now allows saving a disabled pipeline
incomplete, so this is the runner's guard against a broken definition. Substitution is also
single-pass now: a value containing `{{another_var}}` stays as written.

### Pipelines that can't run are refused at admission (0.2.0, extended in 0.2.1)

A refused pipeline is not scheduled and logs `Refusing pipeline {name} (generation {n}): {reason}`
once per generation. Refused: a pipeline with zero outputs (0.2.0 — it used to overwrite labels with
NULLs); and, in 0.2.1, an `llm` transform without `request_template` or `model`, and a `sql`
transform that declares `transform.inputs`.

### Backfill runs explain themselves (0.2.1)

- A paused run says why (`paused_reason`, `paused_at`); a run whose last batch failed says why
  (`last_error`, `last_error_at`). A failing batch retries indefinitely, so `last_error` is how a stuck
  run is told apart from a slow one. Retries back off with jitter up to 1h (was every 5 minutes).
- A run pauses with `pipeline_changed` when its pipeline is edited (resume to continue under the new
  definition), and with `pipeline_absent` when the pipeline disappears (it won't resume by itself).
- `GET /v1/backfills` lists runs as `{"runs": [...]}`, newest first, filtered by `pipeline_name`,
  `state` and `paused_reason`, `limit` up to 500.
- `GET /v1/dlq` filters by `run_id` and `lane=live|backfill`; `POST /v1/dlq/requeue` can select by run.

### Logging (0.2.2)

- **Log levels change at runtime.** The runner polls `LOG_CONFIG_PATH` every `LOG_CONFIG_INTERVAL` and
  applies per-logger levels from it, with no restart. An entry can be time-boxed with `validTill`, and every
  change is logged. HTTP body and SQL loggers can't be set below `INFO`. Format and rules:
  [`configuration.md`](configuration.md#logging).
- **On-demand call tracing.** Setting an executor, client or service to `TRACE` logs its entry, exit and
  duration, with no arguments or bodies.
- **Each pipeline logs a status line** every `STATUS_REPORT_INTERVAL` (default `5m`). The line is WARN when
  the pipeline is paused, or when it has stopped writing while lagging. This line replaces the separate
  paused-pipeline WARN, so **`DLQ_PAUSE_REPORT_INTERVAL` is removed** (§5). A runner-wide line follows it. Both
  are `|`-separated sections in plain words, with relative times, for example
  `Pipeline status | labels (row, gen 4) | active | last write 3m ago, +12 rows, +0 DLQ | lag 2m | last scan 20s ago | next fire in 4m`.
- **A read timeout is no longer reported as contract skew.** A timed-out ADAS call, or a `502`, `503` or `504`
  from the ingress, used to log `ADAS is incompatible…` and hold all paid work until the next good call. It is
  now retried as a transient failure, and a slow call WARNs once it passes half of `ADAS_READ_TIMEOUT_MS`.
- **Failure lines name the pipeline, stage and cause**, with at most one stack trace per failure. They never
  include an HTTP response body. db-scheduler's own failure line is off, and a held item logs no failure line.
  A rejection is named `ADAS <status> <code>` only when ADAS sent it; a DIAL or ingress error reads
  `HTTP <status>` (a backfill's `last_error` included).
- **Log fields:**
  - The console pattern gains `pipeline=`, `gen=` and `task=`, each printed only when the record carries it. The
    trace ids follow the same rule and are now written `trace_id=`, and the message follows ` - `.
  - OTLP log records carry `pipeline_name`, `pipeline_generation`, `chunk_id`, `task`, `task_instance` and
    `run_id` as attributes.
  - Existing message prefixes are unchanged.
- **Gate conditions log once per transition**, not every fire: spend gate, backpressure, held work and the
  group-sweep pause. Operator actions (pause, resume, backfill verbs, DLQ requeue) are logged with the acting
  principal.

### From 0.2.0 — *0.1.2 only*

- **Write-back no longer starves.** Write-back runs at a higher priority than compute
  (`WRITE_BACK` 20 > `LIVE` 10 > `BACKFILL` 1). Before, labels could be paid for and never written.
- **Breaker pauses expire.** A DLQ-breaker pause lifts after `DLQ_BREAKER_PAUSE_FOR`, doubling on each
  consecutive trip up to `DLQ_BREAKER_PAUSE_MAX`. An operator pause never expires, and auto-resume
  does not replay the DLQ — requeue with the pipeline enabled.
- **Hardening:** group dead-letters always keep their payload; `408` and `425` are retried; a page
  missing a required column fails the tick instead of dead-lettering every row; cursors advance only
  when every row is accounted for; an empty feed must be seen twice before tasks are cancelled.

---

## 4. If the deploy goes out of order

| What happened | Result |
|---|---|
| Runner deployed first, old runner already at 0 | Harmless. The new pod never becomes Ready and holds its work until ADAS 0.2.2 is up. |
| Runner deployed while an old runner pod still runs | The old pod's spend and DLQ writes fail once the new pod has migrated. Scale the old pod down. |
| ADAS deployed without scaling a 0.2.0 runner to 0 | The 0.2.0 runner stalls: it starts no new work, logs `ADAS is incompatible with this build's contract version`, and `runner.adas.contract.compatible` reads `0`. A call already under way may be paid for; its write is held and lands after the upgrade. Deploy the runner. |
| ADAS deployed without scaling a 0.1.2 runner to 0 | **Costly.** Every write is refused `409` after its model call is paid for; results are dead-lettered at once, group items are marked clean, and the DLQ breaker can pause pipelines. Those dead letters can't be requeued by 0.2.2 — replay them by hand from `dlq_item.payload` or backfill. |

The contract check (`Adas-Contract-Version: 2`, echoed back by ADAS) runs before a response is read,
so a mismatched pair never renders a template or calls a model on the new feed.

---

## 5. Reference

### Migrations

| Migration | What it does |
|---|---|
| `V9__rekey_state_on_pipeline_generation` | Renames `evaluator_version` to `pipeline_generation` (`BIGINT`) in `dlq_item` and `spend_counter`. No row is rewritten. |
| `V10__backfill_run_operability` | Adds pause-reason, last-error and generation columns to `run_history`, `run_id` to `dlq_item`, and a unique index allowing one running backfill per pipeline. Existing rows read `NULL`. |

Both are new in 0.2.1; 0.2.0 added none. `pipeline_cursor` and `group_state` are untouched — no cursor
reset, no re-derivation. A DLQ row written before the upgrade keeps its old `evaluator_version` value in
`pipeline_generation`; if the same grain fails again it usually gets a second row, removed by the
30-day retention sweep.

No dependency, JDK, Postgres, base-image or container change.

### Configuration

Descriptions are in [`configuration.md`](configuration.md). New or removed in 0.2.2:

| Variable | Change | Note |
|---|---|---|
| `DLQ_PAUSE_REPORT_INTERVAL` | **removed** | Paused pipelines are reported by the status line, every `STATUS_REPORT_INTERVAL`. A value that is still set is ignored. |
| `STATUS_REPORT_INTERVAL` | new, `5m` | How often each pipeline logs its status line. |
| `LOG_CONFIG_PATH` | new, `/app/log-config/logging.levels.json` | Runtime level file. A missing file changes nothing. |
| `LOG_CONFIG_INTERVAL` | new, `10s` | Poll interval for that file. A duration of at least `1s` — write `10s`, not `10`. |
| `EXECUTION_TRACE_ENABLED` | new, `true` | `@LogExecution` tracing. It logs nothing until a traced class is at `TRACE`. |

These arrived in 0.2.0 — *0.1.2 only*:

| Variable | Change | Note |
|---|---|---|
| `DB_SCHEDULER_THREADS` | default `5` → `3` | An override still wins. Keep threads × 2 GiB under ClickHouse's free memory (8 GiB fits 3, 16 GiB fits 5). |
| `ADAS_READ_TIMEOUT_MS` | default `30000` → `180000` | Must stay above ADAS's `PIPELINE_BUDGET_TIMEOUT` (120s). Raise any override. |
| `DLQ_BREAKER_PAUSE_FOR` | new, `15m` | Length of a breaker pause; doubles per consecutive trip. `0` keeps pauses until resumed. |
| `DLQ_BREAKER_PAUSE_MAX` | new, `4h` | Cap on that doubling. |
| `GROUP_MAX_INFLIGHT` | new, `0` | Cap on one group pipeline's pending work. `0` means `DB_SCHEDULER_THREADS × 10` — 30 with the new default. |

### New metrics

Prometheus spells these with underscores.

| Metric | Since | Use |
|---|---|---|
| `runner.workitem.prefold{lane}` | 0.2.1 | Carried-over work. Non-zero during the window is expected; alert on growth after it. |
| `runner.backfill.batch.failures{pipeline}` | 0.2.1 | Failed backfill batches; see the run's `last_error`. |
| `runner.pipelines.paused.auto` | 0.2.0 | Breaker pauses — the one to alert on. |
| `runner.adas.contract.compatible` | 0.2.0 | `0` while ADAS and the runner disagree on the contract version. |
| `runner.group.sweep.deferred`, `runner.sync.empty.feeds` | 0.2.0 | |
| `runner.scan.rows.skipped{pipeline,reason}`, `runner.scan.pages.refused{pipeline,reason}` | 0.2.0 | |

### Probes

Unchanged: liveness `/actuator/health/liveness`; readiness and startup `/actuator/health/readiness`.
Never point liveness at `/actuator/health` — it reports DOWN until the first sync. The probe table
moved within [`configuration.md`](configuration.md#health-probes) in 0.2.0.

For troubleshooting the contract check, a stuck write lane or a silent pause, see
[`e2e-runbook.md`](e2e-runbook.md).
