# Local end-to-end runbook

Exercises the enrichment path against a real ADAS + ClickHouse + Postgres: register a pipeline in ADAS,
seed source rows, run the runner, and verify the enrichment table populates and reads back joined
onto its source. Also verifies idempotency (running a scan twice yields the same result).

Sections 1–7 walk the row × LLM lane; §8 covers group enrichment, §9 Class-1 (SQL), and §10 bounded
backfill. Each of the three lanes is worth walking once per environment — they fail in different places.

## Prerequisites

- ADAS running locally with the enrichment endpoints (`enrichment-runtime` + `enrichment-registry`)
  and reachable ClickHouse + Postgres. See the ADAS repo's own runbook.
- A DIAL Core endpoint and an `Api-Key` with a real chat/completions deployment, plus per-target
  `WRITE` access on the enrichment table the pipeline targets.
- The runner's own Postgres database (separate from ADAS's).
- Docker (for the runner's Testcontainers-based tests).

## 1. Configure and start the runner

Point the runner at ADAS, DIAL, and its own Postgres (see `docs/configuration.md` for all vars):

```bash
export ADAS_BASE_URL=http://localhost:5000
export DIAL_BASE_URL=https://core.dial.local
export DIAL_API_KEY=<dial-api-key>        # also used for ADAS auth
export POSTGRES_HOST=localhost POSTGRES_DATABASE=enrichment_runner
export HTTP_PORT=5100                     # default is 5000, which local ADAS/DIAL already holds
./gradlew bootRun
```

Confirm liveness/readiness (readiness turns UP after the first successful pipeline sync):

```bash
curl -s localhost:5100/actuator/health/liveness    # {"status":"UP"}
curl -s localhost:5100/actuator/health/readiness    # {"status":"UP"} once a pipeline sync has run
```

## 2. Register a pipeline in ADAS

An enrichment is registered as one `POST /v1/pipelines` carrying a nested `transform` block: the
pipeline declaration is the whole definition, with nothing else to register first.

The declaration binds an enrichment table whose grain key is `event_id`, a `schedule` trigger (its `cron` is
optional: ADAS stores every minute at a name-derived second when it is omitted), and a
`transform` carrying `type`, `model`, `preset`, a `request_template` with `{{var}}` placeholders, a
`transform.inputs` map saying what those placeholders are taken from (this is the former top-level
`vars`), and a `transform.outputs` map keyed by the target column each output lands in — ADAS composes
the response schema from the target plus those outputs. An `advanced` block of execution knobs is
optional. (Bodies per the ADAS `pipeline-registry` API.)

`transform.outputs` is the only place output binding is declared, which is why the runner's feed carries
one resolved `outputs` list and never joins two.

```bash
curl -s -X POST localhost:5000/v1/pipelines -H 'Content-Type: application/json' -d @pipeline.json
```

Within one poll interval (`ADAS_PIPELINES_POLL_INTERVAL_MS`, default 30s) the runner caches the pipeline:

```bash
curl -s localhost:5100/v1/pipelines/cache        # the pipeline appears with its generation

# The runner reads the COMPILED projection, and so must you when checking what it sees. The default,
# view=source, is the authored declaration: no derived response_schema, no grain_key, no version_column
# and no resolved outputs — a different object, not a degraded one.
curl -s 'localhost:5000/v1/pipelines?kind=enrich&enabled=true&view=compiled' | jq '.pipelines[0]'
```

## 3. Seed source rows and let the runner enrich

Seed rows into the source table (e.g. `dial_usage_log`) via ADAS's ingest path. The pipeline's recurring
scan drains pages, calls DIAL, and upserts results. Watch progress:

```bash
curl -s localhost:5100/actuator/prometheus | grep -E 'runner_(pipelines_cached|queue_depth|dlq_size|scan_lag_seconds|dial_calls|enrichment_rows_upserted)'
```

## 4. Verify the enrichment table

Query the source through ADAS and confirm the enrichment columns (plus provenance
`pipeline_generation`, `model`, `enriched_at`) are populated and joined onto the source:

```bash
curl -s -X POST localhost:5000/v1/queries/execute -H 'Content-Type: application/json' -d @query.json
```

## 5. Idempotency

Re-run the same scan range (or restart the runner so it resumes from the committed cursor). The
enrichment table (a `ReplacingMergeTree` read with `final=1`) collapses duplicate writes by grain
key, so the joined result is identical — exactly-once *effect* despite at-least-once execution.

## 6. Failure & ops paths

- **DLQ**: force a schema-invalid model response and confirm a `dlq_item` at stage `validate`:
  `curl -s localhost:5100/v1/dlq`, or narrowed to one pipeline with
  `curl -s 'localhost:5100/v1/dlq?pipeline_name=chat-topic-live'`. An item a backfill produced carries its
  `run_id`; one without is the live path's. Narrow to one run with `?run_id=<run-id>`, or to a lane with
  `?lane=live` (what is failing *now*, without a replay's noise) or `?lane=backfill`; `lane=live` together
  with a `run_id` is a `400`, not an empty list. The listing is paged newest first (`limit`, default 100, at
  most 1000): pass a page's `next_cursor` back as `?cursor=` for the next one, until `has_more` is false and
  `next_cursor` is absent. Every page carries `total` and `requeueable_total` for its filters. A cursor the
  listing did not issue is a `400 invalid_cursor`. To dump everything:

  ```bash
  cursor=; while :; do
    page=$(curl -s "localhost:5100/v1/dlq?pipeline_name=chat-topic-live&limit=1000${cursor:+&cursor=$cursor}")
    echo "$page" | jq -c '.items[]'; cursor=$(echo "$page" | jq -r '.next_cursor // empty'); [ -n "$cursor" ] || break
  done
  ```

  Requeue an archived chunk: `POST /v1/dlq/{id}/requeue`;
  requeue in bulk by pipeline, by run, or both (their intersection):
  `POST /v1/dlq/requeue?pipeline_name=chat-topic-live`, `POST /v1/dlq/requeue?run_id=<run-id>`. Neither is a
  `400`. A requeued run item keeps its run if it fails again, and — like any requeue — counts toward the
  pipeline's live in-flight backpressure, so a large run-scoped requeue throttles the live scan while it drains.
- **Backfill**: `POST /v1/pipelines/{name}/backfill`, then `GET /v1/backfills/{runId}`; pause/resume/cancel.
  A run is findable without its id: `GET /v1/backfills` lists runs newest first, narrowed by
  `pipeline_name`, `state` and `paused_reason` (lowercase, as the run body reports them; any case is
  accepted), with `limit` clamped to 500. `curl -s 'localhost:5100/v1/backfills?state=running' | jq` answers
  "which run is holding the `409`"; `?state=paused&paused_reason=cursor_stalled` is the triage query. A
  misspelled state or reason is a `400` naming the permitted values.
- **Pause**: a pipeline auto-pauses if its dead-letter rate trips the DLQ circuit breaker. An auto-pause
  lifts itself after `DLQ_BREAKER_PAUSE_FOR` (widening on each consecutive trip, to `DLQ_BREAKER_PAUSE_MAX`);
  an operator pause never expires. `GET /v1/pipelines/paused` reports each pause's `pipeline_name`, `origin`,
  `reason`, `since` and `resumes_at`, and `POST /v1/pipelines/{name}/resume` lifts either. Pauses are held in
  memory: a restart lifts them all.
- **Runtime view**: `GET /v1/pipelines/{name}/runtime` answers "what is this pipeline doing right now?" in one shape
  for every lane. A section that does not apply to the lane is absent, and so is a field the runner does not
  currently know. The last error, `has_more` and `caught_up_at` are held in memory and are unknown after a restart
  until the next fire, and `last_write_at` until the next write. While the pipeline is still failing,
  `last_error_at` survives a restart, read from the scheduler's durable record, without its message.

  ```bash
  # Row lane: status, schedule, progress, plus queue {computing, awaiting_write}, spend_today {calls, tokens,
  # failed_calls} and failures {total, requeueable_total}. Read lag together with queue: the scan advances when
  # work is enqueued, so lag 0 with queue.computing > 0 means "caught up, still writing".
  curl -s localhost:5100/v1/pipelines/chat-topic-live/runtime | jq '{status, progress, queue, failures}'

  # Group lane: the same, plus groups — pending = ready + ceiling_blocked + waiting_idle.
  curl -s localhost:5100/v1/pipelines/<group-pipeline>/runtime | jq '.groups'

  # Class-1 (sql) lane: no queue, spend or DLQ. A failing materialize shows as consecutive_failures and last_error.
  curl -s localhost:5100/v1/pipelines/<sql-pipeline>/runtime | jq '.schedule'
  ```

  The view reports counts and state only. `status.state` is never `stalled`: the stall verdict stays in the status
  heartbeat's log line. `failures` is the count of live dead letters; the items are on
  `GET /v1/dlq?pipeline_name=<name>&lane=live`. A running or paused backfill is on
  `GET /v1/backfills?pipeline_name=<name>&state=running` (or `state=paused`), and the pipeline's declared settings
  are on ADAS's `GET /v1/pipelines/{name}`.

  Before the first pipeline sync it answers `503 pipeline_cache_cold`; a pipeline the synced cache does not hold
  is `404 not_found`. The materialized-through position and `drained_at` of a Class-1 pipeline are ADAS's, under
  `state` on ADAS's `GET /v1/pipelines/{name}`.

## 7. Build gate

```bash
./gradlew clean build        # compile + checkstyle + tests (Docker required for Testcontainers)
```

---

## 8. Group enrichment end to end

Dataset-agnostic on purpose: this exercises the group *capability*, not any particular content. Register a
`chat_id`-grained enrichment over `dial_usage_log` and a group pipeline over it.

```bash
# A group-grained enrichment, in ADAS's two steps: identity first (no DDL, status PENDING), then the
# physical schema, which materializes the table. A create body carrying columns or grain_key is a 422.
curl -s -X POST localhost:5000/v1/tables -H 'Content-Type: application/json' -d '{
  "name":"usage_chat_topic","type":"enrichment","source_table":"dial_usage_log"
}'

# The pipeline's group_by must resolve to this grain_key (equal to it, or an enrichment column whose last
# segment is it); the write back is always keyed by grain_key. Declare group_version and truncated here
# or the runner's group provenance is silently dropped on write.
curl -s -X POST localhost:5000/v1/tables/usage_chat_topic/schema -H 'Content-Type: application/json' -d '{
  "grain_key":"chat_id","cardinality":"zero_or_one",
  "columns":[{"source_name":"topic","name":"topic","type":"string","nullable":true},
             {"source_name":"pipeline_generation","name":"pipeline_generation","type":"long","nullable":true},
             {"source_name":"model","name":"model","type":"string","nullable":true},
             {"source_name":"enriched_at","name":"enriched_at","type":"string","nullable":true},
             {"source_name":"group_version","name":"group_version","type":"long","nullable":true},
             {"source_name":"truncated","name":"truncated","type":"boolean","nullable":true}]
}'

# A group pipeline. Readiness is the OR of the conditions declared: idle makes a quiet group ready, signal
# makes a group declared complete ready at once, and cost_ceiling bounds a chatty one.
curl -s -X POST localhost:5000/v1/pipelines -H 'Content-Type: application/json' -d '{
  "name":"chat-topic-live","kind":"enrich","target":"usage_chat_topic",
  "trigger":{"kind":"group","group_by":"chat_id",
             "ready_when":{"signal":"event_kind = '\''conversation_end'\''","idle":"30s",
                           "max_staleness":"24h","cost_ceiling":5}},
  "transform":{"type":"llm","preset":"chat_completion","model":"gpt-4o",
               "request_template":"{\"messages\":[{\"role\":\"user\",\"content\":\"{{members_notice}} Summarise: {{members}}\"}]}",
               "inputs":{"text":{"column":"request_body"}},
               "outputs":{"topic":"the conversation topic in two or three words"}},
  "enabled":true
}'
# The mark scan's clock is the runner's, not the pipeline's: GROUP_SCAN_CADENCE (default 1m). Start the runner
# with GROUP_SCAN_CADENCE=15s here: at the default, the 30s idle above is shorter than the gap between marks, so
# the sweep can judge a still-active chat idle and evaluate it early.

# The verdict, not the raw columns: a group pipeline's scan page carries __signal per row. ADAS evaluates the
# predicate inside its own query, so the runner never parses it.
curl -s -X POST localhost:5000/v1/pipelines/chat-topic-live/scan -H 'Content-Type: application/json' \
  -d '{"limit":5}' | jq '.rows[0] | {chat_id, __signal, _ingested_at}'
```

The transform's `request_template` interpolates the assembled members as a JSON array under `{{members}}`.
`{{members_notice}}` is a ready-made caveat that is empty when the input is complete, so including it
unconditionally is correct — a trimmed group is then labelled in the knowledge that it was trimmed:

```json
{"messages":[{"role":"user","content":"{{members_notice}}\nSummarise this conversation:\n{{members}}"}]}
```

Seed two or more rows sharing a `chat_id`, then watch the two halves of the group path:

```bash
# 1. Dirty-mark — the ordinary scan tick records per-group state (cost ∝ new rows, not group count).
psql -c "SELECT group_key, group_version, computed_version, signalled, evaluations FROM group_state;"

# 2. Readiness sweep — after ready_when.idle elapses, the group is enqueued.
curl -s localhost:5100/v1/pipelines/<pipeline-name>/groups | jq
psql -c "SELECT task_name, count(*) FROM scheduled_tasks GROUP BY 1;"

# 3. One enrichment row per group key, with group provenance. An enrichment is not a queryable entity of
#    its own — query the SOURCE, where the enrichment's columns appear namespaced `<enrichment>.<column>`
#    and the group's label fans out onto every member row. ADAS reads the target with final=1 itself, so
#    there is no FINAL to write here. An unqualified `topic` fails with a confusing
#    "column 'topic' depends on itself" once two enrichments of one source both declare that name.
#    Keep the SQL on ONE line: a literal newline inside the JSON string makes the body unparseable, which
#    answers 400 "check the request body" before ADAS has looked at the query at all.
curl -s -X POST localhost:5000/v1/queries/execute-sql -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT chat_id, \"usage_chat_topic.topic\", \"usage_chat_topic.group_version\", \"usage_chat_topic.truncated\" FROM dial_usage_log WHERE chat_id = '\''chat-42'\'' LIMIT 5"}'
```

Then re-ingest a member of that `chat_id` and confirm the group re-fires: `group_version` rises above
`computed_version`, the sweep enqueues it again, and the row is replaced (the `final=1` read shows one row per
group key throughout). A group at its `cost_ceiling` stops being enqueued for the rest of the UTC day —
`runner_group_ceiling_blocked` is what distinguishes that from a group that simply stopped changing.

A group is also held back while its own compute or write for that version is still queued: dirtiness is
cleared by the write, so without this the compute-to-write window would re-evaluate the group and throw the
result away. `runner_group_sweep_suppressed_total` counts it, and rises with the write backlog — expect it to
be non-zero during a backfill and near zero once writes keep up. The sweep line says the same thing:
`… N dirty and ready group(s) enqueued, M skipped (work in flight)`.

To exercise the signal, ingest a row for that `chat_id` whose `event_kind` matches the predicate and raise
`ready_when.idle` well above the sweep interval: the group is enqueued on the next pass rather than after the
idle window, and `group_state.signalled` is `true`. Note that eviction discards `signalled` with the row, so a
group re-created after a long quiet period starts un-signalled.

One group can be re-driven without punishing the rest of the pipeline, which is the point of the override — it
bypasses the readiness windows deliberately, and runs even for a group the sweep is declining at its ceiling
(the evaluation it triggers is charged like any other, so the next sweep still declines the group):

```bash
curl -s -X POST localhost:5100/v1/pipelines/<pipeline-name>/groups/<group-key>/requeue   # 202
# A key containing '/' is sent with the slash as %2F (curl passes it through as typed; a key containing a '.' or
# '..' segment, '//', '%', ';' or '\' cannot be addressed, see docs/kubernetes-exposure.md).
curl -s -X POST 'localhost:5100/v1/pipelines/<pipeline-name>/groups/team%2Fa/requeue'   # 202
```

The group listing pages by keyset, oldest activity first by default, and every page reports the pipeline's
`total`. Follow `next_cursor` (absent on the last page) under the **same** `order`: a cursor replayed under the
other order is refused with `400 invalid_cursor`.

```bash
curl -s 'localhost:5100/v1/pipelines/<pipeline-name>/groups?limit=2' | jq '{total, has_more, next_cursor, keys: [.groups[].group_key]}'
curl -s 'localhost:5100/v1/pipelines/<pipeline-name>/groups?limit=2&cursor=<next_cursor>' | jq '[.groups[].group_key]'
curl -s 'localhost:5100/v1/pipelines/<pipeline-name>/groups?order=newest&limit=5' | jq '[.groups[] | {group_key, last_activity_at}]'
```

## 9. Class-1 (SQL) enrichment — zero DIAL traffic

The target is an ordinary row-grain enrichment, except that `enriched_at` must be declared `timestamp`:
ADAS stamps it itself with `now64(3)` inside the `INSERT … SELECT` (unlike the group lane, where the runner
sends a string). `model`, `group_version` and `truncated` are skipped for a `sql` transform, so declaring
them buys nothing.

A `sql` transform carries no model, preset, template or response schema — only `transform.outputs`, keyed
by the target column each one lands in, whose value is the bare SQL expression (an `llm` transform's value
is prose, or an object; `transform.type` selects the grammar, which is why it is required). Every
expression must be a *scalar projection*, which is a narrower grammar than a filter: comparison operators
are not projectable, so a boolean column comes from `json_extract_bool`, never from `x > y`. The
expressions are validated against the source at registration, so a nonsense expression is refused by the
same `POST /v1/pipelines` that declares it.

`enabled` must be present in the pipeline body, here and in every `enrich` registration above: ADAS
honours it for this kind (an `aggregate` pipeline is always created disabled behind its preview gate), so
leaving it unstated is refused with `422 pipeline_validation_failed` naming the field rather than silently
defaulting to off.

```bash
curl -s -X POST localhost:5000/v1/pipelines -H 'Content-Type: application/json' -d '{
  "name":"request-shape-live","kind":"enrich","target":"usage_request_shape",
  "trigger":{"kind":"schedule","cron":"*/15 * * * * *"},"filter":"deployment = '"'"'e2e-class1'"'"'",
  "transform":{"type":"sql",
    "outputs":{"model_name":"json_extract_string(request_body, '"'"'model'"'"')",
               "token_bucket":"width_bucket(total_tokens, 0, 100000, 4)",
               "streaming":"json_extract_bool(request_body, '"'"'stream'"'"')"}},
  "advanced":{"rows_per_scan":4},
  "enabled":true}'
```

The assertion that matters is the absence of model traffic — check the counters rather than eyeballing the
logs. The lane touches no queue, no DLQ and no spend counter either, so those stay flat too:

```bash
curl -s localhost:5100/actuator/prometheus \
  | grep -E 'runner_(class1_rows_written|class1_tick_failures|cursor_stalls|dial_calls|enrichment_rows_upserted|queue_depth)'
# runner_class1_rows_written_total{pipeline="…"} climbs; runner_dial_calls_total and
# runner_enrichment_rows_upserted_total must NOT (the latter counts the row-lane write-back, which this lane
# does not use). Rows written are tagged by pipeline, because a summed total cannot say which pipeline stopped writing.
```

The lane's three signals are the whole of its observability — it has no queue depth and no DLQ size — so read
all three: `runner_class1_rows_written_total{pipeline}`, `runner_class1_tick_failures_total`, and
`runner_cursor_stalls_total{lane="class1"}`. The last one is the case that raises no error: ADAS reports backlog
but returns a cursor that does not move, the drain halts by design, and nothing else moves. Lag is the fourth
reading, and `runner_scan_lag_seconds{pipeline}` returns to **0** once a pipeline is caught up rather than holding the
last backlog's value, so a non-zero lag on a quiet pipeline is a real finding rather than a stale one.

A `rows_per_scan` below the backlog is drained as several windows inside one fire, which the data shows
without reading a log: rows written in different windows carry different `enriched_at` values (with a limit
of 4 over 6 rows, four rows share one stamp and two share another).

Re-running the tick against an unchanged source writes nothing at all — an empty window echoes the caller's
cursor, so `pipeline_cursor.cursor` stays byte-identical while `last_scan_at` advances. Re-materializing a window
that *does* match (§10) re-writes identical values, which the target's `ReplacingMergeTree` collapses by
grain — the `final=1` result and the distinct-grain count are unchanged.

Governance rejects, at pipeline registration, every expression the lane's exactly-once effect depends on not
seeing — worth walking once, since each has its own message:

| Expression / pipeline | Rejected because |
| --- | --- |
| `sum(total_tokens)` | aggregates; that is an aggregation, not a Class-1 enrichment |
| `now()` | absent from the closed function catalog (non-determinism needs no denylist) |
| `operation_duration_ms > 5000` | `>` is not expressible as a projection in the DSL |
| `execution_path` | yields an array; a column bound to a grain takes one scalar |
| `no_such_column` | unknown column |
| string var → `long` column | output binding type incompatible with the variable's type |
| `transform.type: sql` + `trigger.kind: group` | a group-grain deterministic pipeline is an aggregation |
| llm pipeline → `POST /v1/pipelines/{name}/materialize` | materialize is the Class-1 path; use scan/write-back |

## 10. Bounded backfill

Re-applying a changed expression to a range of history, rather than replaying everything:

```bash
curl -s -X POST localhost:5100/v1/pipelines/<pipeline-name>/backfill -H 'Content-Type: application/json' \
  -d '{"from":"2026-07-01T00:00:00Z","to":"2026-08-01T00:00:00Z"}'
curl -s localhost:5100/v1/backfills/<run-id> \
  | jq '{state, paused_reason, paused_at, rows_done, last_error, last_error_at, window_from, window_to}'
```

A run reports the rows it has **processed** and no total: ADAS exposes no cardinality, the cursor is opaque and
the window's upper bound moves, so a progress fraction is not obtainable. Elapsed time and `rows_done` read twice
are the honest progress signals.

An omitted body is a full replay — from the source start, but still through the pipeline's `filter`, so it
replays the pipeline's rows and not the whole table. An inverted window is a `400` and creates no run.

The window is `(from, to]` over the pipeline's **version column** (`_ingested_at`, not `request_time`), so two
adjacent windows partition history exactly — no overlap, no gap. That is the assertion worth making, because a
run that reports `done` proves nothing about what it actually covered: check `rows_done` against a window you
know the row count of, and pick windows that *exclude* rows too. On a Class-1 pipeline the data itself shows the
clip, since `enriched_at` is stamped per materialize window:

```bash
# with two ingest batches (6 rows, then 2), these two runs report rows_done 6 and 2, and each refreshes
# enriched_at only for its own half
-d '{"from":"2026-07-29T10:00:00Z","to":"2026-07-29T10:04:00Z"}'   # 6
-d '{"from":"2026-07-29T10:04:00Z","to":"2026-07-29T10:06:00Z"}'   # 2
-d '{"from":"2026-07-25T00:00:00Z","to":"2026-07-26T00:00:00Z"}'   # 0 — a window matching nothing is `done`, not stuck
```

The run carries its own cursor in `run_history`, so replaying history never disturbs the live cursor in
`pipeline_cursor` — the live tick resumes from where it was while the backfill walks the window. On a **Class-1**
pipeline this is the whole expression-evolution path, and it is worth walking end to end: patch a column onto the
target, `PATCH` the pipeline's `transform` with the extra output (its `generation` rises, and the runner picks
that up on the next pipeline sync), then backfill the window. The window's rows come back with the new column
filled and `pipeline_generation` bumped, the columns the change did not touch keep their previous values, and
the distinct-grain count is unchanged — the replay collapsed.

**Lifecycle.** A run is scheduled, not executed inline, so there is a `DB_SCHEDULER_POLLING_INTERVAL` window
after the `202` in which to pause or cancel it before its first batch — which is the only way to exercise these
on a fixture small enough to finish in one fire.

Every verb is guarded by the run's state: **pause** admits only `running`, **resume** only `paused`, and
**cancel** `running` or `paused`. Anything else is `409 backfill_conflict` and changes nothing — including a
retried cancel, which is a `409` because a completion instant is written once (a `GET` says the run is
cancelled). A cancel or pause during a draining batch stops it at its **next page**: that page's work is rolled
back and nothing further is enqueued.

| Action | Effect |
| --- | --- |
| second `POST …/backfill`, or a resume, while a run is `running` | `409 backfill_conflict` naming the running run's id; a `paused` run does *not* block a new one |
| start or resume into a **paused pipeline**, or one **over its spend budget** | `409 backfill_conflict` naming the condition (the budget resets at 00:00 UTC); no run is created or set running |
| start or resume in the seconds after a boot, before the first pipeline sync | `503 pipeline_cache_cold` — retry; the runner does not yet know whether the pipeline exists |
| `…/cancel` | terminal: never fires, `rows_done` stays 0, and `…/resume` or a second `…/cancel` on it is a `409` |
| `…/pause` on a `done`, `cancelled` or `paused` run | `409`; state and `finished_at` unchanged |
| `…/pause` then `…/resume` | the run keeps its window and its cursor and finishes from where it stopped |
| a pipeline **paused mid-run** | the next page flips the run to `paused` with `paused_reason` `pipeline_paused_operator` or `pipeline_paused_breaker`; the pipeline *and* the run must both be resumed |
| a pipeline **disabled or deleted** in ADAS mid-run (or refused as unexecutable) | `paused` / `pipeline_absent`, committed cursor kept; resume is `404` while it is absent and drains from the cursor once it is back. Nothing resumes it automatically |
| a pipeline **edited** mid-run (its `generation` moved) | `paused` / `pipeline_changed` before the next page. Resume adopts the new generation and continues; or cancel and re-run the window |
| a batch that **keeps failing** (ADAS `422`, a page missing the grain column, …) | the run stays `running`; `last_error` names the cause (`"ADAS 422 pipeline_validation_failed"`, never a body) and `runner_backfill_batch_failures_total{pipeline=…}` rises while the batch retries on the worker backoff. There is no terminal `failed`: fix the cause, or cancel — a cancelled run keeps its `last_error` |
| a cursor that does not advance | `paused` / `cursor_stalled` — investigate before resuming, or it re-enters the same page |

`paused_reason` records what *paused* the run, not what holds it now, and survives a restart that clears the
in-memory pipeline-pause registry; it is cleared by resume and cancel.

For a **group** pipeline the run completing means the window has been *marked*, not evaluated — the sweep then
applies the readiness windows and the ceiling as usual. Prove it without spending a model call by giving the
pipeline a `ready_when.idle` far longer than the test and starting the runner with a `GROUP_SCAN_CADENCE` far longer
too (for example `24h`). A fixed-delay instance fires once as soon as it is created, so create the pipeline before
seeding the window's rows; after that neither the sweep nor the live tick can fire, and the backfill is the only
actor: the run reports `rows_done` = the window's row count, and

```bash
curl -s localhost:5100/v1/pipelines/<pipeline-name>/groups | jq   # the group appears: dirty=true, evaluations=0
```

with `runner_queue_depth{task="enrich-group"}` and `runner_dial_calls_total` both still 0 and no row in the
target enrichment. Marked, queued and evaluated are three different states, and only the first has happened.

---

## 11. Reading a starved write lane

What a write lane starved behind a deep compute backlog looks like, and what it looks like now that write-back has its own priority lane.

```bash
# What the queue is holding, per task, and how old the head of each lane is.
docker exec -it <postgres> psql -U postgres -d testdb -c \
  "SELECT task_name, count(*) FILTER (WHERE NOT picked) AS queued,
          count(*) FILTER (WHERE picked) AS picked,
          min(execution_time) AS earliest
   FROM scheduled_tasks GROUP BY task_name ORDER BY 1"
```

- `enrich-group` hundreds deep with `upsert-rows` queued and **never** picked, alongside
  `Computed group … queued for writing` lines and no `Upserted N enrichment row(s)`, is the starvation.
  Since write-back outranks every compute lane it should not recur; if it does, check the stored `priority`
  column rather than the timings — a row enqueued at the compute priority is the bug, a slow drain is not.
- `… deferred (queue at its depth ceiling)` in the sweep's log line is **normal** while a backlog drains:
  the groups are still dirty in `group_state` and the next pass takes them. It is only a symptom if it
  persists with the queue never emptying. `runner_group_sweep_deferred` is the series; do not confuse it
  with `runner_group_sweep_suppressed`, which counts groups whose own work is already queued and which
  should trend to zero.
- A deeper queue is bought with `GROUP_MAX_INFLIGHT`, but raising it does not make the pipeline faster —
  concurrency is `DB_SCHEDULER_THREADS`. Raising *that* multiplies the concurrent `group-rows` reads against
  ADAS, each of which can cost over a gigabyte of ClickHouse memory; size it against the ClickHouse node,
  not against the backlog.

### Work that is held rather than dropped

A work item that becomes due before this process has ever synced the pipeline registry is **held**:
rescheduled without consuming an attempt and never archived. Expect a short burst of

```
Chunk … held; retrying in 30s without consuming an attempt: Holding chunk …: the pipeline cache has not
been populated yet
```

after every restart, clearing on the first successful sync. A burst that does **not** clear means the sync
itself is failing — check readiness, not the queue.

A **backfill batch** due in that window is held the same way — `Backfill batch <run-id> held; retrying in 30s
…` — and its run stays `running` with no `paused_reason` and no `last_error`. It is only paused as
`pipeline_absent` once a populated cache genuinely lacks the pipeline.

### A pipeline that is paused and says nothing

```bash
curl -s localhost:5100/v1/pipelines/paused | jq
curl -s localhost:5100/actuator/prometheus | grep runner_pipelines_paused
```

`runner_pipelines_paused_auto` is the alertable one — an operator pause is a legitimate steady state, a
breaker pause is not. ADAS reports such a pipeline `enabled` with no run position throughout, which is
indistinguishable from "working, nothing new to do": the runner's own listing and that gauge are the only
places a breaker pause is visible.

### A pipeline recreated under the same name

A delete followed by a create under the same name (a catalog import, or `DELETE` + `POST`) is a new pipeline, and
the runner tells it apart by ADAS's `created_at`, which it records on the cursor row. On the first poll that reports a
different value it resets the name: the cursor is emptied, `group_state` rows and unpicked work items are deleted,
running and paused backfills are cancelled, a breaker pause is lifted (an operator pause is kept), and the new
pipeline scans from the beginning. Dead letters and same-day spend stay under the name.

```bash
# The ADAS side: delete, then register again under the same name (enabled, so it enters the feed).
curl -s -X DELETE "$ADAS/v1/pipelines/my-pipeline"
curl -s -X POST "$ADAS/v1/pipelines" -H 'Content-Type: application/json' -d @my-pipeline.json
# Within one poll interval the runner logs "Pipeline my-pipeline was recreated (created_at … -> …); runner state reset: …"
curl -s localhost:5100/actuator/prometheus | grep runner_pipeline_state_resets_total
curl -s localhost:5100/v1/pipelines/my-pipeline/runtime | jq '.progress'
```

```sql
SELECT pipeline_name, pipeline_created_at, cursor, last_scan_at FROM pipeline_cursor WHERE pipeline_name = 'my-pipeline';
```

## Appendix: the cut-over onto `/v1/pipelines`

Run once, during the window that moves both services onto the folded contract. It is **not** part of the
ordinary local walkthrough above.

The runner's five state tables were keyed on ADAS's rule UUID and are now keyed on the pipeline name. `V8`
renames the columns **and empties the tables** — it does not carry the values across. The `id -> name`
mapping lives in ADAS's catalog and this runner's Flyway is pointed at a different database, so preserving
the state would mean generating `UPDATE`s from ADAS and applying them here in between the two deploys.
Discarding removes that step and, with it, the ordering constraint that made this a window at all. `V8`'s
header records what the choice costs; this is the operational half.

### The sequence

| # | Step | Where |
|---|---|---|
| 0 | **Capture what a rescan will not rebuild** (below), and snapshot both databases — that is the only rollback, since neither migration is reversible. | both |
| 1 | **Stop the runner.** Scale it to zero. Do **not** disable pipelines in ADAS, or patch a declaration — see the warning below. | runner |
| 2 | **Deploy ADAS.** `V36` folds `rule` into `pipeline`, carrying each row's `enabled` value across unchanged. | ADAS |
| 3 | **Deploy the runner** with the renamed env vars (`ADAS_PIPELINES_*`). `V8` renames, truncates the five state tables and clears the queue, announcing the discarded counts as `NOTICE`s in the deploy log. | runner |
| 4 | **Start it.** Within one `ADAS_PIPELINES_POLL_INTERVAL_MS` the sync feed hands back every enabled pipeline and `scheduleIfNotExists` recreates its recurring instance. **Nothing is re-enabled by hand.** | runner |

Step 1 comes before step 2 because a running old runner against a folded ADAS polls `/v1/rules`, gets 404,
keeps its cache by design and goes on calling endpoints that no longer exist. Not destructive — a failed
tick leaves the cursor untouched — but there is no reason to generate it.

### Change nothing that bumps `generation` — not `enabled`, and not a declaration patch

Disabling is the obvious way to drain the queue, and it is the one action that turns a free discard into a
stall. But the mechanism is the `generation` bump, not the toggle, so a declaration `PATCH` does the same
damage: either bumps the pipeline's `generation`, and ADAS's Class-1 materialized position is qualified by
`generation`, so the next materialize discards the mark and re-records it at the end of the first replayed
window — near the start of the source, because `V8` has just truncated the cursor. Every rollup reading that
enrichment is then clamped down there until the re-derivation catches back up.

A `scan_every`-only patch can move a Class-1 pipeline's mark back by weeks. That knob is retired since, but a `trigger.cron`-only patch bumps `generation` in the same way.
`advanceMaterializedThrough` is monotone only *within* a generation — the discard runs first — so nothing
protects the mark across a bump. Declaration edits wait until the re-derivation is done.

Leave `enabled` alone and the mark stays valid at the current tail. `advanceMaterializedThrough` is monotone
in SQL, so a re-derivation from zero can never lower it, and no rollup notices anything. Stopping the runner
drains the queue just as well, and `V8` clears whatever was still queued.

The visible artefact of leaving it alone is the opposite one: every replayed window ends at or below the
recorded position, so ADAS stamps `history_rederived_at` and dependent rollups report `rebuild_required`
naming the re-derived enrichment. That is a read-only diagnostic over identical values — the false positive
`EnrichmentFreshness.reDerivesHistory` documents as deliberately accepted. Clear it by re-declaring the
rollup while disabled, or leave it.

### Capture first — a rescan does not rebuild these

```bash
# Dead-letter items: why each poison item failed, and the only route back for a dead-lettered group
# (deadLetter() stamps the group computed, so the sweep never retries it on its own).
curl -s 'localhost:5100/v1/dlq?limit=1000' | jq > dlq-pre-cutover.json
```

On the build you are about to replace the filter parameter is `rule_id`, not `pipeline_name`; the path is
the same. Requeue anything worth keeping **before** the window rather than after — a requeue needs the
pipeline enabled, and a requeue into a disabled one destroys the stored item.

Against the runner database, **as it stands before `V8` runs** — these are capture queries for step 0, so
they are written against the schema the outgoing image left behind. `V8` renames `rule_id` to
`pipeline_name` in all five tables, so the current spelling is given beside each one for anyone running
these after the window has closed.

```sql
-- A paused or running backfill's cursor lives only here. Cancel or let it finish before the window;
-- afterwards it cannot be resumed, only re-requested. (After V8: pipeline_name.)
-- (rows_total was never written and is unmapped from V10 on; it is dropped in the release after that.)
SELECT id, rule_id, kind, state, rows_done, rows_total, started_at FROM run_history
 WHERE state IN ('running', 'paused');

-- What the group lanes had already computed. Nothing reads this back afterwards; it is the number to
-- compare against when the re-evaluation bill arrives. (After V8: pipeline_name.)
SELECT rule_id, count(*) AS groups, count(*) FILTER (WHERE computed_version IS NOT NULL) AS computed
  FROM group_state GROUP BY rule_id;
```

### Verify afterwards

```bash
curl -s localhost:5100/v1/pipelines/cache | jq        # every enabled pipeline, under its name
curl -s localhost:5100/actuator/prometheus | grep -E 'runner_(class1_rows_written|dial_calls|cursor_stall)_total'
```

```sql
SELECT pipeline_name, last_scan_at, lag_seconds FROM pipeline_cursor ORDER BY pipeline_name;
```

A row per enabled pipeline within a couple of scan intervals is the signal that the sync, the recurring instance
and the lane all came back. Then watch one of each kind actually fire: a scan tick, a Class-1 materialize,
a group sweep.

### What is expected to look wrong, and is not

- **Class-1 lanes re-deriving from the start of their source.** Bounded and idempotent — one
  `INSERT … SELECT` per `rows_per_scan` window inside ClickHouse, no model call, the target replacing by
  grain. `runner_class1_rows_written_total` climbs far past the day's new rows; `runner_dial_calls_total`
  does not move at all.
- **`rebuild_required` on rollups reading a re-derived enrichment**, as above.
- **A group lane, once enabled, re-evaluating every group rather than only new ones.** `group_state` held
  the `computed_version` that said which were done; with it gone every group is dirty. That is a real
  chat-completion call each, so enable a group lane deliberately and with the bill in mind, not as part of
  bringing the service back up.

---

## Appendix: the `Adas-Contract-Version` handshake

From this release every ADAS call carries an `Adas-Contract-Version` request header and every ADAS response
must carry the same value back. The version is a code constant in both repos (`AdasContract.VERSION` here,
`RunnerContract.VERSION` in ADAS), there is no env var for it, and nothing at build time can compare one
repo's constant with the other's — so **the pair is verified in the deploy window**, while the runner is
still scaled to zero:

```bash
# Against the in-cluster ADAS Service URL. Substitute the version this runner image declares.
curl -s -o /dev/null -D- -X POST "$ADAS/v1/pipelines/$PIPELINE/scan" \
  -H "Api-Key: $KEY" -H 'Content-Type: application/json' -H 'Adas-Contract-Version: 4' \
  -d '{"limit":1}' | grep -i adas-contract-version   # the echo must come back, byte for byte

curl -s -X POST "$ADAS/v1/pipelines/$PIPELINE/scan" \
  -H "Api-Key: $KEY" -H 'Content-Type: application/json' -d '{"limit":1}' | jq -r .error
# 409 runner_contract_required — the header is mandatory on the four data-plane endpoints
```

If the echo is absent, ADAS predates the contract and **deploying the runner is what stalls**; if it
differs, the two are on opposite sides of a bump. Either way, do not start the runner.

### The window is unchanged; this appendix is about not following it

The sequence is the same as the fold's — capture the baseline, confirm no backfill is `RUNNING`
(`GET /v1/backfills?state=running` returns no runs), scale the
runner to 0, deploy ADAS, deploy the runner, scale to 1. What this release changes is the cost of getting the
order wrong. **Neither order loses data now**, which they did before — but only one of the two is free:

| Order | What happens | What to do |
|---|---|---|
| **ADAS first** | The runner's **predecessor** is refused `409` on every data-plane call, and it has none of this release's machinery. Cursors untouched and nothing written — but chunks already queued still make their DIAL call before the refused write, refused write-backs are archived on their **first** attempt (409 is not retryable) with their computed rows, and group items are dead-lettered **and stamped clean**, which can auto-pause the pipeline through the DLQ breaker. | Deploy the runner. Then un-pause any pipeline the breaker paused, and see the DLQ caveat below. |
| **Runner first** | This build sees no echo, never completes a sync, never reports ready, and fails its startup probe. Under `maxUnavailable: 0` the **outgoing** pod keeps serving against the ADAS it matches. | Deploy ADAS. The incoming pod becomes ready on its next poll; nothing to re-enable. |

A runner already running when ADAS lands does **not** lose readiness — that is deliberate, so its own admin
API (DLQ list, requeue, cache read) stays reachable through its Service during exactly the incident when an
operator needs it. Read `runner_adas_contract_compatible` instead: it is `0` for the length of the skew and
`1` again afterwards, with no restart.

### What a skew looks like while it lasts

Nothing throws in a way a queue depth or a DLQ size would show, which is why the gauge is the thing to read
first:

```bash
curl -s localhost:5100/actuator/prometheus | grep -E \
  'runner_adas_contract_compatible|runner_queue_depth'
```

- `runner_adas_contract_compatible 0` — the pair is skewed. This is the only signal that says so.
- `runner_queue_depth{task="…"}` **rising** while the gauge reads `0` is a *hold*, not a backlog: due items
  are not executing because no model call and no write-back may be made. They resume on their next due time
  once the gauge returns to `1` — no requeue, no operator action.
- Scan lag **falls** rather than rises, because no page is being committed at all. Do not read it as caught up.
- The ERROR is logged **once per transition**, not once per poll, so `grep` for it rather than tailing:
  `ADAS is incompatible with this build's contract version`.

### DLQ caveat for an ADAS-first window

> **Upgrading to 0.2.2 or later:** items left over from an *earlier* ADAS-first window must be
> requeued on the old build, before the 0.2.2 upgrade window. A 0.2.2 runner refuses to requeue any item archived by 0.2.0 or earlier (`422 dlq_not_requeueable`);
> after the upgrade their rows can only be replayed by hand from `dlq_item.payload` or re-derived by a
> backfill. See [`release-0.2-upgrade.md`](release-0.2-upgrade.md) §1.

Write-backs already in flight when a skewed ADAS starts refusing them are **held**, not archived — that is
what this release adds. But a write-back archived by the *predecessor* image during such a window is still
sitting in the DLQ afterwards. It archived on its **first** attempt, not after `WORKER_MAX_ATTEMPTS`: a `409`
is outside `RetryableStatus`, so the predecessor took its permanent-rejection branch immediately and stored
the item with its computed rows (a group write was also stamped and charged there). Requeue those **with the
pipeline enabled**, or `UpsertService` archives the item again for an absent pipeline and the stored result
is destroyed:

```bash
# The listing filters by pipeline, not by stage, so pick the upsert items out with jq. One page holds at most
# 1000 items: if has_more is true, walk next_cursor with the dump loop from §6.
curl -s 'localhost:5100/v1/dlq?pipeline_name=<pipeline>&limit=1000' \
  | jq '.items[] | select(.stage == "upsert") | {id, pipeline_name, error}'
curl -s -X POST localhost:5100/v1/dlq/<id>/requeue    # re-attempts the WRITE; recomputes nothing
```

Requeuing one re-posts rows the model was already paid for, so it is always cheaper than letting the row be
re-derived by a backfill.

Group pipelines need the same pass at `stage == "group_fetch"`. The predecessor read the `409` on
`group-rows` as a poison group, so it dead-lettered the group **and stamped it clean** — the sweep will not
pick it up again on its own, and a requeue is the only thing that will. Those items carry their payload, so
requeuing one re-drives the group from its group key.

Check `curl -s localhost:5100/v1/pipelines/paused` in the same pass: enough of those dead letters trips the
DLQ circuit breaker, which pauses the pipeline until `DLQ_BREAKER_PAUSE_FOR` elapses (doubling on each consecutive trip, up to
`DLQ_BREAKER_PAUSE_MAX`). A pause does not block a
requeued work item — the pause gates *enqueueing*, in the scan, sweep and backfill — so the requeue lands either
way, but the pipeline stays out of the live path until the pause lifts or you call
`curl -s -X POST localhost:5100/v1/pipelines/<name>/resume`.
