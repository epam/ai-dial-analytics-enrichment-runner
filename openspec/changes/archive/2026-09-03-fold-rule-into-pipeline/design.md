## Context

The runner holds **no definitions and no ClickHouse connection**. It syncs enabled rules from the ADAS control plane on a 30 s poll and owns only its own PostgreSQL runtime state: the db-scheduler queue, scan cursors, group state, DLQ and spend counters. So ADAS's fold of `rule` into `pipeline` leaves nothing of ADAS's to re-model here — only a client, a cache, five state tables, and the identity all of them are keyed on.

That identity is the whole of the difficulty. Today it is a UUID that ADAS mints; after the fold it is the pipeline `name`. `rule_cursor`, `run_history`, `dlq_item`, `spend_counter` and `group_state` are all keyed on `rule_id`, and `DeterministicId` embeds it in every db-scheduler `task_instance` — in the prefix *and* in the sha1 basis. Everything else in this change is a rename.

Two facts constrain the shape of the solution before any choice is made:

- **The two services have separate databases.** The `id → name` mapping lives in ADAS's catalog, so the runner's Flyway cannot read it: keeping the runner's state across the identity change could only ever be an out-of-band operator step, never a migration.
- **`/v1/rules` is deleted in the same ADAS release** — no alias, no deprecation window. There is no version of the runner that works against both, so this is a coordinated deploy in one maintenance window, not a rolling upgrade.

The runner is single-replica, which is the one thing that makes a maintenance window cheap: there is no split-brain to manage.

Sections 2 and 3 below are **verbatim shared** with ADAS's design for the same fold. Edit either and edit both, or the two services get built against different contracts.

## Goals / Non-Goals

**Goals:**

- Consume ADAS's merged `/v1/pipelines` surface: the `kind=enrich` sync feed with its `{"pipelines":[…]}` envelope, and the four re-pathed data-plane calls.
- Re-key the runner's five state tables and every deterministic work-item id from the UUID to the pipeline name, **without losing state** — a lost cursor or lost `group_state` costs real DIAL spend, not just time.
- Carry the rename all the way through: classes, task names, config keys, metric and MDC and span names, this service's own `/v1/**` endpoints, and the operator-facing log lines. Internals speaking "rule" while the API speaks "pipeline" would reproduce, inside one service, exactly the confusion the fold exists to remove.
- Leave the specs a single source of truth: retire `rule-sync` rather than leaving it beside `pipeline-sync` holding superseded requirements.

**Non-Goals:**

- **Any behavior change.** The three execution lanes and their routing (`EvaluatorType` `SQL` vs `LLM`, `TriggerKind.GROUP`); the evaluator registry; the group prompt-variable contract (`members`, `members_truncated`, `members_included`, `members_omitted`, `members_notice`), its 4-chars-per-token estimate, oldest-first drop and tail-keeping cut; DIAL invocation; DLQ, circuit breaker, spend accounting and backfill semantics; at-least-once execution / exactly-once effect; single-replica topology — all unchanged.
- Re-validating the pipeline-name charset in the runner. ADAS is the gate (§2.4); a second validator would drift.
- Closing the incremental-sync gap: the poller keeps passing `null` for `updated_since`, because a deletion is indistinguishable from "unchanged" under a watermark. Separate change.
- Sweeping orphaned `group_state` rows for deleted pipelines. A known gap, tracked separately. The truncate takes the existing orphans with everything else, so the cut-over no longer has to decide about them one by one — but the table starts collecting them again from the first pipeline deleted after it.
- Executing the cut-over on any running deployment. This change ships the code, the migration and the runbook; scheduling the window is an operator concern.

## Decisions

### 1. The runner's state is discarded, not remapped

`V8` renames the five state tables onto the pipeline name and then **truncates them**, clearing
`scheduled_tasks` with them. The alternative — remapping the values with hand-run SQL against the old column
names, between ADAS's deploy and the runner's — is what the separate databases force on anyone who wants to
keep the state, and it was this change's original plan. It is rejected because the state turns out to be
worth less than the choreography it costs.

**What the state is worth**, estimated rather than assumed:

- `pipeline_cursor` is the whole of it. Losing it means every enabled pipeline re-derives its target from the
  start of its source once, because nothing on the scan path skips a source row that already carries an
  enrichment value.
- For a **Class-1 (`type=sql`)** lane that re-derivation happens inside ClickHouse — one `INSERT … SELECT`
  per window, no model call, idempotent because the target replaces by grain. Hundreds of source rows for a small pipeline such as
  `turn-feedback-live`; millions for a large one such as `usage-client-identity-live`, which is on the order
  of a hundred windows across a few cadence ticks. Compute, not spend.
- For a **group LLM lane** it is a real chat-completion call per group: tens of thousands of prompt tokens
  and a fraction of a cent each, so thousands of sessions cost tens of dollars and take on the order of an
  hour. With group pipelines disabled at cut-over time, the window itself spends nothing; the bill falls to
  whoever next enables one.
- For a group pipeline such as `session-insights-live` the re-evaluation can in fact be **wanted**.
  `session_insights` records the `evaluator_version` that produced each row, and after an evaluator bump many
  sessions may still carry an older version. `group_state`'s dirty predicate compares *source* versions, so a stale-version group is not dirty, and no
  backfill re-fires it either — a backfill page becomes dirty marks, and marks are `GREATEST`-monotone. The
  only supported ways to force a re-label are per-group requeue or discarding the state, so here the discard
  is the cheapest implementation of something that has to happen anyway.

**What it buys.** No operator step between the two deploys, no cross-database generator query, no
verify-before-`COMMIT` ritual, and no ordering constraint beyond "runner down first". The one irreversible
data operation moves off an operator's console and into a migration that announces what it discarded — the
same information the verification query existed to produce.

**What it forbids.** The cut-over must not quiesce by disabling pipelines in ADAS. `enabled` is a mutation,
every accepted mutation bumps `generation`, and ADAS's Class-1 materialized position is qualified by
`generation` — so a disable/re-enable makes ADAS discard the mark and re-record it at the end of the first
replayed window, near the *start* of the source, clamping every dependent rollup down there for the length
of the re-derivation. Left alone, the mark stays at the tail and `advanceMaterializedThrough`'s monotonicity
means a re-derivation from zero can never lower it.

**What no rescan rebuilds**, and so has to be captured at choreography step 0: `dlq_item` (why each poison
item failed, and the payload of an archived chunk), `run_history` (a paused run's own cursor — such a run
can be re-requested but not resumed) and `spend_counter` (inert while `SPEND_PAUSE_ENABLED` is false, which
is the default).


### 2. The identity's charset is ADAS's to enforce, and the runner depends on it

`DeterministicId` builds `#`-delimited instance ids (`name#sha1#c0`, `name#g#sha1`, `…#w`), and `InflightCounter` counts a pipeline's in-flight work with a `LIKE 'name#%'` prefix scan. A UUID could not contain `#`; a free-form name could, and then `a#b` and `a` share the `a#` prefix. ADAS's `^[a-z][a-z0-9_-]{0,63}$` validator is therefore a **hard dependency of this change**, and the spec says so on the side that relies on it.

Two things that look like new risks and are not — verified, do not "fix" them: `escapeLike` already escapes `!`, `%` and `_` and the query already carries `ESCAPE '!'`, so a name containing `_` cannot broaden a match; and `scheduled_tasks.task_instance` is `TEXT`, so the longer identity cannot overflow the column or its PK. A regression test pins the first.

*Alternative considered:* validate the charset in the runner too. Rejected as a second, drifting validator — but the `_` regression test stays, because that one guards the runner's own `escapeLike`, not ADAS's grammar.

### 3. The quiesce is stopping the runner, and the queue is cleared rather than asserted empty

The identity is in the sha1 **basis** of `chunkInstanceId` and `groupInstanceId`, not only in the prefix.
Every deterministic work-item id therefore changes when the identity does, so `scheduleIfNotExists` cannot
dedup a pre-window item against a post-window one, and no queued row is reachable after the fold.

The original plan drained the queue by disabling every pipeline and waiting for `scheduled_tasks` to reach
**0** — which does work, recurring instances included: `PipelineSyncService.reconcile` cancels the recurring
instance of any pipeline that leaves the `enabled=true` feed, so one 30 s poll removes those too. It is
ruled out by Decision 1's prohibition on touching `enabled`, and it is no longer needed: **stopping the
process is the quiesce**, and `V8` deletes whatever was still queued.

Deleting is now both sufficient and required. *Sufficient*, because a work item stands for rows the cursor
had already passed at enqueue time, and that cursor has just been truncated — those rows are re-scanned from
the beginning like everything else, so dropping the item loses nothing. *Required*, because the earlier
`RAISE EXCEPTION` would abort startup over leftovers from before a lane was switched off, with no quiesce
left to clear them.

The delete covers both spellings of the scan task (`rule-scan` before the fold, `pipeline-scan` after) and
`class1-materialize`, which keeps its name and would otherwise be picked up with a UUID where a pipeline
name is expected. Recreating what is deleted is free — the next sync's `scheduleIfNotExists` does it.


### 4. `PipelineDto` is a narrow read model, not a mirror of ADAS's DTO

ADAS's merged `PipelineDto` is one flat union for both kinds. The runner models only what it reads:

- `id` removed — `name` is the key everywhere the code said `rule.id()`.
- New nested `TriggerDto(kind, cron, group_by, ready_when)`. `member_select` stays unmodelled, as today.
- `TriggerKind` unchanged as an enum, now read from `trigger.kind`.
- Dropped as **verified unread** in `src/main`: `filterSql` (ADAS applies the filter server-side), `source`, `targetEnrichment`, `evaluatorName`, `createdAt`, `updatedAt`, and `priority` with its `RulePriority` enum — the lanes hard-code `PRIORITY_LIVE = 10` / `PRIORITY_BACKFILL = 1`, so that field already did nothing. Unknown JSON properties are already tolerated: ADAS serves `member_select`, `unclamped_reads` and `rebuild_required` today and the current record omits all three.
- `generation` stays a `long` and stays the sole change token, so `reconcile`'s diff is untouched in substance.

The sync feed's response is **no longer a bare list**: `TypeReference<List<RuleDto>>` becomes a `PipelineListDto(List<PipelineDto> pipelines)` wrapper. The blank-body branch still returns an empty list.

Dropping `id` reaches one place beyond the internals: `RuleCacheEntryDto` carries both `id` and `name`, so `GET /v1/pipelines/cache` loses a field. That is a wire change on this service's own ops surface, and it belongs in the `runner-ops` delta rather than in the mechanical rename list. It will not fail silently — `RuleCacheMapper` is a MapStruct `@Mapper`, so a target property with no source is a build-time complaint.

### 5. Rename the capability by retiring and founding, not by renaming the directory

OpenSpec has no rename-a-capability operation: sync applies `ADDED`/`MODIFIED`/`REMOVED`/`RENAMED` per capability, keyed off the **delta file's own directory name**, and creates a main spec when the capability does not exist. Nothing deletes one. A delta under `specs/pipeline-sync/` alone would found the new capability and leave `openspec/specs/rule-sync/` behind as an orphan holding stale, superseded requirements — two sources of truth in the one directory the project declares to be the source of truth.

So: `specs/pipeline-sync/spec.md` with `## ADDED Requirements` carrying all three in full; `specs/rule-sync/spec.md` with `## REMOVED Requirements`, each block carrying `**Reason**:` and `**Migration**:`; the proposal saying it in words; and an explicit task to `rm -r openspec/specs/rule-sync/`, because sync empties the requirements and leaves the directory. This is how the sibling repo retired `metadata-store` in `2026-06-29-add-table-management-api`, whose task 1.6 was exactly that `rm -r`.

`RENAMED` with `FROM:`/`TO:` is the right section for a title-only change — note that neither repo has ever used it, so the retitles here are its first exercise. And the heading is the lookup key, so `MODIFIED` alone cannot retitle a requirement.

*Alternative considered for the id:* `registry-sync`, naming what syncs rather than what is synced and staying accurate if a third kind arrives. `pipeline-sync` chosen: it matches the locked full-rename decision and sits with the other domain-noun ids (`incremental-scan`, `group-enrichment`, `spend-tracking`).

### 6. Rename by symbol, never by blanket replacement

"Rules" is the ordinary English word in every `rules:` block of `docs/kubernetes-exposure.md`'s Ingress and NetworkPolicy manifests, and in `openspec/config.yaml`'s own top-level `rules:` key. A blanket `rule` → `pipeline` pass corrupts all of them. Rename by symbol and read every prose hit.

`PipelineRouting`'s seven refusal reasons keep their wording apart from the noun — they are operator-facing diagnostics.

### 7. The silent-failure checklist

Everything here fails **quietly** when only half of it changes. This is the review checklist, not a summary.

| Item | Change | How it fails if missed |
|---|---|---|
| `adas.rules.poll-interval-ms`, `adas.rules.initial-delay-ms` | → `adas.pipelines.*`; env `ADAS_RULES_*` → `ADAS_PIPELINES_*` | They are **string literals inside `@Scheduled` placeholders with defaults**, not bound to a typed record, so a mismatch falls back to 30 s / 10 s instead of failing at startup. **It fails the same way inside the test suite: 20 integration tests register these keys as `3600000` via `@DynamicPropertySource` to park the poller.** Rename the placeholder without them and all twenty fall back to a 10 s initial delay and start polling ADAS mid-test — green today, intermittently red tomorrow, for a reason nothing points at. |
| Health contributor bean `ruleSync` | → `pipelineSync`, **and** `application.yml`'s `management.endpoint.health.group.readiness.include` | Rename one without the other and readiness either fails to start or silently drops a contributor — the pod then reports ready before its first successful sync, which is precisely what that group exists to prevent. Verify empirically which way Spring Boot 4 treats an unknown contributor name. |
| `runner.rules.{cached,paused,unsupported}` | → `runner.pipelines.*` | Dashboards go blank rather than red. Three gauges — `runner.rules.paused` is the one an operator watches during a DLQ breaker trip. |
| Tag `rule=` on `runner.spend.calls.today`, `runner.scan.lag.seconds`, `runner.class1.rows.written` | → `pipeline=` | Same; also breaks the `runner_class1_rows_written_total{rule="…"}` assertions in `docs/e2e-runbook.md`. |
| MDC `WorkerMdc.RULE_ID = "rule_id"` | → `pipeline_name` | Log-query filters break silently. |
| Span attribute `rule.id` | → `pipeline.name`, in **7** places (`PipelineScanExecutor` ×2, `GroupReadinessSweep`, `EvaluatorInvoker`, `GroupWorkerService`, `UpsertService`, `Class1MaterializeExecutor`) | Trace queries break silently. Span **names** are unchanged. |
| This service's own endpoints | `/v1/rules/…` → `/v1/pipelines/…` with `{name}` | Four auth integration tests hard-code these as protected-endpoint fixtures. Authorization is unchanged: `/v1/**` on `FULL_ADMIN`. |
| **The `/v1/dlq` surface** | `?rule_id=` → `?pipeline_name=` on list and requeue; the `rule_id` property on `DlqItemDto` and `BackfillRunDto` | Wire changes on paths that do not themselves contain "rule" — exactly what a path-driven rename pass misses. |
| Three hand-written SQL blocks | `GroupStateWriter` (12 named constants, but only 7 `rule_id` references — the readiness/budget/ordering constants are composable **fragments** naming only their own columns), `SpendService`'s `ON CONFLICT (rule_id, evaluator_version, day)` and `DAILY_TOTALS`, `InflightCounter`'s `LIKE … ESCAPE '!'` | None goes through JPA, so nothing catches them at build time. Change what mentions the column; leave the fragments alone. |
| Ingress | `docs/kubernetes-exposure.md` routes `/v1/rules` with `pathType: Prefix` → `/v1/pipelines`; plus its three `curl` examples, its "first ADAS rule sync" probe prose, and the callout that the prefix admits both a read and a backfill trigger because an Ingress routes by path and not by method | Each service has its own hostname and its own Ingress, so there is no shared route and no routing ambiguity — what is shared after the fold is one *noun* across two services (`/v1/pipelines/{name}/refresh` is ADAS, `/v1/pipelines/{name}/backfill` is the runner). The fix is to say in the exposure table which host owns which verb. |

### 8. The backfill namespace had to move too, for the reason §2 gives about the prefix

§2 argues that `#` is what keeps `a#b` and `a` from sharing a prefix, and verifies two things that look like
new risks and are not. There is a third that **is** one, and it is the mirror image of the same argument:
`DeterministicId.backfillChunkInstanceId` namespaced backfill chunks under the literal `bf#`.

A backfill chunk rides the same `enrich-chunk` task as a live one but is deliberately *outside* every
pipeline's in-flight count — its id carries the run id, not the pipeline name. Under a UUID identity that
separation was free, because no UUID is the string `bf`. Under a name identity it is not: `bf` matches
`^[a-z][a-z0-9_-]{0,63}$`, so a pipeline named `bf` has `pipelinePrefix` = `bf#`, and its backpressure count
would sweep in every backfill chunk in the runner — every *other* pipeline's included — and throttle it on a
queue that is not its own. Silently, and only for that one name.

The namespace therefore leads with the delimiter (`#bf#`). Since a name must **begin** with a lower-case
letter, no name can produce a prefix matching an id that begins with `#`, which makes the separation
structural rather than a fact about which words happen to be taken. Free to change here and nowhere else: the
cut-over quiesces the queue to zero anyway, so no live id is being renamed under a running system.

*Alternative considered:* let backfill chunks count toward their pipeline's in-flight depth, which removes
the collision by removing the exemption. Rejected as a behaviour change this change does not want — backfill
priority already keeps it from starving live work, and folding it into backpressure would make a large
backfill throttle the live lane it was written to stay out of the way of.

## Risks / Trade-offs

- **ADAS is deployed before the runner is stopped** → the old runner polls `/v1/rules`, gets 404, keeps its cache by design and goes on calling endpoints that no longer exist. Not destructive — a failed tick leaves the cursor untouched — but it is noise that looks like a broken deploy. Mitigated by choreography step 1 preceding step 2, and by confirming the replica count is zero before deploying ADAS.
- **The cut-over quiesces by disabling pipelines in ADAS** → the `generation` bump makes ADAS discard its Class-1 materialized mark, which is then re-recorded near the start of the source because `V8` has truncated the cursor, clamping every dependent rollup there for the length of the re-derivation. It is the obvious way to drain the queue, which is what makes it worth stating three times: in choreography step 1, in Decision 1, and in the migration's own header.
- **Neither Flyway migration is reversible** → rollback is restoring the step-0 snapshots of *both* databases and redeploying the previous images. Snapshotting both is choreography step 0 and is the only rollback that exists.
- **A group lane is enabled as part of bringing the service back up** → with `group_state` gone every group is dirty, so the lane re-evaluates its whole population at a fraction of a cent a group rather than picking up where it left off. Mitigated by there being nothing to restore: `V36` carries each pipeline's `enabled` value across unchanged, group pipelines are disabled going in, and enabling one is a separate costed decision rather than a step in this runbook.
- **The config-key rename lands without the test `@DynamicPropertySource` registrations** → an intermittently red suite whose cause points nowhere. Mitigated by grepping for the key rather than working from a list of test classes, and by a new test asserting the property actually resolves.
- **ADAS ships without the widened name grammar** → existing hyphenated identities are readable and patchable but could never have been created, and the `#` guard this change depends on is absent. Mitigated by the shared §2.4 contract and by verifying against the sibling repo before the window.
- **Trade-off: no compatibility shim.** Deleting `/v1/rules` with no alias means a maintenance window and no rolling upgrade. Accepted — the runner is single-replica, so a window is cheap, and an alias would mean carrying two identities through five state tables for the length of a deprecation period.

## Migration Plan

One maintenance window, both services. Steps 0–5, from the shared choreography:

| # | Step | Where |
|---|---|---|
| 0 | **Pre-flight.** Assert no `rule.name` collides with a `pipeline.name` and that every name matches the charset; **snapshot both PostgreSQL databases** (this is the only rollback); capture what no rescan rebuilds — the dead-letter queue, `run_history` rows in state `running`/`paused`, today's `spend_counter` — and cancel or finish any live backfill. | both |
| 1 | **Stop the runner.** Scale to zero; that *is* the quiesce. Do **not** disable pipelines in ADAS (Decision 1). | runner |
| 2 | **Deploy ADAS** — `V36__fold_rule_into_pipeline.sql` runs at startup, carrying each row's `enabled` value across unchanged. | ADAS |
| 3 | **Deploy the runner** with the renamed env vars; `V8__rekey_and_discard_runner_state.sql` renames the tables, columns and indexes, truncates the five state tables and clears `scheduled_tasks`, reporting the discarded counts as `NOTICE`s. | runner |
| 4 | **Start it and verify.** One sync, one scan tick, one Class-1 materialize, a `pipeline_cursor` row per enabled pipeline, `GET /v1/pipelines/cache`. Nothing is re-enabled by hand. | both |
| 5 | **Follow-up change:** ADAS drops `rule_legacy` and `rule_legacy_aud`. | ADAS |


Rehearse steps 1–4 against a copy of the ADAS catalog and a copy of the runner database **before booking the window** — in particular to see what the `NOTICE`s report and how long the Class-1 re-derivation actually takes on a full-size `dial_usage_log`.

## Open Questions

- ~~Which way Spring Boot 4 treats an unknown contributor name in a health group's `include`~~ — **moot, and deliberately left that way.** `OpsIntegrationTest` now asserts the readiness group reports **exactly** `readinessState`, `db` and `pipelineSync`, so a half-done rename fails on the contributor set whichever way the framework behaves. Pinning the framework's behaviour instead would have made the guard depend on the thing it is guarding against.
- ~~Whether ADAS's fold migration has landed with the widened `requirePipelineName` grammar by the time this change is verified end to end~~ — **answered.** It is ADAS's **`V36__fold_rule_into_pipeline.sql`**, which follows the audit trail's `V34`/`V35`. `V36` carries a Part D that reconciles the fold with that audit trail (widening `pipeline_aud` with the six control-plane columns, remapping `audit_activity.resource_type` to `Pipeline`, and renaming `rule_aud` to `rule_legacy_aud`). The local end-to-end and the choreography rehearsal are now unblocked; everything else — build, unit and integration tests against WireMock, and both `V8` migration paths — was verifiable here alone.
