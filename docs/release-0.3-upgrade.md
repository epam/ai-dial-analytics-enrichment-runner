# Enrichment Runner release-0.3 — upgrade notes

How to move a runner from **0.2.2** to **0.3**. A runner on an older build first follows
[`release-0.2-upgrade.md`](release-0.2-upgrade.md) to 0.2.2.

The runner moves as a pair with **ADAS 0.3**. Follow ADAS's own `docs/release-0.3-upgrade.md` for its
side. The contract version rises from `2` to `3`, and neither build works with the other's previous
release, so both are upgraded in one window. The main change is ADAS retiring **`trigger.kind: on_ingest`
and `advanced.scan_every`**: every row-grain pipeline now fires on its `trigger.cron`, and a group
pipeline's mark scan runs on the runner's new `GROUP_SCAN_CADENCE`.

---

## 1. Upgrade procedure

### Before the window — on the old build

1. **Drop overrides of `SCAN_DEFAULT_CADENCE` and `CLASS1_DEFAULT_CADENCE`** from your values. Both
   are removed, and Spring ignores a value that is still set, so nothing fails but nothing reads it.
2. **Decide `GROUP_SCAN_CADENCE`** (default `1m`) if you run group pipelines. It replaces each group
   pipeline's `advanced.scan_every` for the mark scan. Keep it well below the shortest
   `ready_when.idle` and `GROUP_DEFAULT_IDLE` (§3).
3. **Check that your ingress forwards `%2F` unchanged** if operators will requeue groups whose key
   contains `/` (§3). This is optional, and nothing else depends on it.
4. **Record `runner.scan.lag.seconds` and `runner.group.dirty`** per pipeline, so you can check afterwards
   that no state was reset.

### The window

1. **Scale the runner to 0** and wait for the pod to be gone. A rolling update is not enough: an old and a
   new pod would overlap against a half-updated ADAS.
2. **Deploy ADAS 0.3.** While it rolls, make **no `POST` or `PATCH` on `/v1/pipelines`** from anyone. An
   old ADAS pod still accepts `on_ingest`, which ADAS's `V44` has already made the database refuse.
3. **Deploy runner 0.3.** Migrations `V11`–`V13` run at startup (§5). None rewrites data.
4. **Scale the runner back to 1.** Readiness stays DOWN until the first pipeline sync succeeds, so a Ready
   pod confirms the pair matches.

**While the runner is at 0**, nothing is lost. ADAS's Class-1 materialize positions stop advancing, so
dependent rollups lag until a cadence after the runner is back. Keep the window short.

### After the window

1. **Verify:**
   - the pod is Ready and `runner.adas.contract.compatible` is `1`;
   - `runner.pipelines.cached` equals the number of enabled pipelines you expect;
   - `runner.pipelines.unsupported` is `0` and there are no `Refusing pipeline` WARNs;
   - `runner.scan.lag.seconds` and `runner.group.dirty` are near their recorded values plus the length
     of the window, and `runner.pipeline.state.resets` is `0` (a reset would show far larger numbers);
   - each former `on_ingest` pipeline fires on its new cron: `GET /v1/pipelines/{name}/runtime` shows a
     recent `schedule.last_scan_at` and a `schedule.next_run_at` within a minute.
2. **Pipelines may now be edited again.** Update scripts and clients for §2.

**No runner state is migrated or discarded.** ADAS's `V44` rewrites every `on_ingest` trigger to
`schedule` without bumping `generation`, so no scan cursor, Class-1 position or queued work is dropped.
The runner applies the new schedules on its own: each poll compares every recurring instance's stored
schedule with the one derived from the feed, reschedules on a mismatch, and retries until they match.

**Rollback is closed.** A 0.2.2 runner speaks contract `2`, and ADAS 0.3 refuses it. Fix a failing new
image forward.

---

## 2. What breaks for scripts and clients

| Change | What to do |
|---|---|
| `GET /v1/pipelines/paused` renames `pipelineName` → `pipeline_name` and `resumesAt` → `resumes_at` | Rename the two keys in any script that parses it. It was the only `/v1` response not in snake_case. |
| ADAS refuses `trigger.kind: on_ingest` (`422`) and `advanced.scan_every` (`400`) | Author `schedule` pipelines. `trigger.cron` is optional, and ADAS stores a once-a-minute default. Worked bodies: [`e2e-runbook.md`](e2e-runbook.md). |

Before and after for `GET /v1/pipelines/paused`:

```jsonc
// 0.2
{"paused": [{"pipelineName": "session-insights-live", "origin": "breaker",
             "reason": "...", "since": "...", "resumesAt": "..."}]}

// 0.3
{"paused": [{"pipeline_name": "session-insights-live", "origin": "breaker",
             "reason": "...", "since": "...", "resumes_at": "..."}]}
```

A script reading both versions can use `jq '.paused[] | .pipeline_name // .pipelineName'`.

Everything else in this release is additive: existing fields and parameters keep their meaning.

---

## 3. Behaviour changes

### Row-grain pipelines fire only on their cron

The fixed-delay branch is gone. A row-grain pipeline, on the scan lane and the Class-1 lane alike, is
scheduled only from `trigger.cron`. The feed of a matching ADAS carries no `on_ingest` pipeline, but an
unexpected one is **refused at admission** with a reason naming the retired kind. So is a `schedule`
pipeline with no cron or an unparseable one, and an entry with no trigger. Before, such a pipeline was
cached as healthy and then failed in reconcile.

A restart no longer reschedules an instance whose stored schedule already matches, so an overdue cron
fire is no longer pushed to its next slot by a restart.

### A group pipeline's mark scan follows `GROUP_SCAN_CADENCE`

The mark scan only records dirty marks and never calls a model, so its cadence is runner configuration
rather than a per-pipeline knob. A group pipeline that declared a longer `scan_every` (for
example `5m`) now marks every `GROUP_SCAN_CADENCE`, which means more ADAS scan calls
but no more model calls. A group's later rows sit unmarked for up to one cadence, so a still-active
group can look idle and be evaluated early, then again. Keep the cadence well below the shortest
`ready_when.idle`. Sync WARNs once per generation for a group pipeline whose effective idle window is
under twice the cadence. Keep the value the same on every replica: mixed values make the stored schedule
flap.

### A recreated pipeline starts from empty runner state

ADAS identifies a pipeline by name, and a delete followed by a create under the same name is a new
pipeline. This happens with a plain `DELETE` + `POST`, or with ADAS 0.3's catalog import. The runner now
records ADAS's `created_at` per name. When the feed reports a different value, one transaction:

- cancels the name's running and paused backfills;
- deletes its group state and unpicked work items;
- empties its scan cursor.

It also lifts a breaker pause (an operator pause is kept) and logs a WARN. `runner.pipeline.state.resets`
counts these resets. Dead letters, same-day spend and work items already executing are kept.

The first sync after deploy **adopts** every pipeline's `created_at` without resetting anything.

### New operator reads

- **`GET /v1/pipelines/{name}/runtime`** answers "what is this pipeline doing right now?" for all three
  lanes. It reports status (`active`, `paused`, `held`, `over_budget`, `backpressured`), schedule,
  progress and queue, plus group readiness, today's spend and live dead-letter totals where they apply. A
  section that does not apply to the lane is omitted. Run outcomes are held in memory, so after a restart
  they are absent until the pipeline's first run. A pipeline not in the cache is `404 not_found`; a cache
  that has never synced is `503 pipeline_cache_cold`.
- **`GET /v1/dlq` pages.** It takes an opaque `cursor`, orders by `created_at DESC, id DESC`, and returns
  `next_cursor`, `has_more`, `total` and `requeueable_total`. The counts ignore the cursor.
- **`GET /v1/pipelines/{name}/groups` pages too,** by `cursor` and `order` (`oldest`, the default, or
  `newest`), and returns `next_cursor`, `has_more` and `total`. A cursor the listing did not issue, or one
  replayed under the other `order`, is `400 invalid_cursor` on both listings.
- **A group key containing `/` can be requeued** with the slash sent as `%2F`. Everything in front of the
  runner must forward `%2F` unchanged. See
  [`kubernetes-exposure.md`](kubernetes-exposure.md) for the ingress and the keys that stay unaddressable.

---

## 4. If the deploy goes out of order

| What happened | Result |
|---|---|
| Runner deployed first, old runner already at 0 | Harmless. The new pod never becomes Ready and holds its work until ADAS 0.3 is up. |
| ADAS deployed without scaling the 0.2.2 runner to 0 | The 0.2.2 runner stalls: it starts no new work, logs `ADAS is incompatible with this build's contract version`, and `runner.adas.contract.compatible` reads `0`. A call already under way may be paid for; its write is held and lands after the upgrade. Deploy the runner. |

The contract check (`Adas-Contract-Version: 3`, echoed back by ADAS) runs before a response is read, so
a mismatched pair never renders a template or calls a model on the new feed.

---

## 5. Reference

### Migrations

| Migration | What it does |
|---|---|
| `V11__dlq_listing_keyset` | Rebuilds the DLQ listing indexes with an `id` tie-breaker. Indexes only. |
| `V12__group_listing_keyset` | Rebuilds the group activity index with a `group_key` tie-breaker. Indexes only. |
| `V13__pipeline_cursor_created_at` | Adds a nullable `pipeline_cursor.pipeline_created_at`. No backfill: the first sync adopts every row. |

`pipeline_cursor` positions, `group_state` and queued work are untouched, so there is no cursor reset and
no re-derivation. The 0.2.2 image ignores the new column.

The Spring Boot (4.0.8) and Jackson (2.21.7 / 3.1.7) patch bumps are security updates. There is no JDK,
Postgres, base-image or container change.

### Configuration

Descriptions are in [`configuration.md`](configuration.md). New or removed in 0.3:

| Variable | Change | Note |
|---|---|---|
| `SCAN_DEFAULT_CADENCE` | **removed** | Row-grain pipelines fire on `trigger.cron`. A value that is still set is ignored. |
| `CLASS1_DEFAULT_CADENCE` | **removed** | The same, for the Class-1 lane. |
| `GROUP_SCAN_CADENCE` | new, `1m` | Fixed delay of a group pipeline's mark scan. At least `1s`, or startup fails. |

### New metrics

Prometheus spells these with underscores.

| Metric | Use |
|---|---|
| `runner.pipeline.state.resets` | Recreated pipelines whose runner state was reset. Expect `0` right after the deploy. |

### Probes

Unchanged: liveness `/actuator/health/liveness`; readiness and startup `/actuator/health/readiness`.

For troubleshooting the contract check, a stuck write lane or a silent pause, see
[`e2e-runbook.md`](e2e-runbook.md).
