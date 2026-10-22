## 1. Metrics

- [x] 1.1 `scan/InflightCounter` — expose counts by task name; `observability/RunnerMetrics` — replace the
  single `runner.queue.depth` with a per-task gauge, since a summed depth cannot say which lane is backed up
- [x] 1.2 `RunnerMetrics` — a distinct DIAL 429 counter, with the 429 **also** staying in the error total (it
  is a failed call; removing it would make the error rate under-report). Classify in `worker/EvaluatorInvoker`,
  which is where the status is already inspected
- [x] 1.3 `RunnerMetrics` — per-rule current-day spend gauge, evicted with the rule like the lag gauge. Fed
  from `spend/SpendService.today`, refreshed off the scrape path
- [x] 1.4 Active-group count and dirty backlog as `COUNT`s over `group_state`, refreshed on a schedule (never
  during a scrape). Added to `RunnerMetrics` rather than a separate `GroupMetrics` class as the task sketched:
  they share the one off-scrape refresh with the DLQ-size and queue-depth gauges, and splitting them would have
  meant two schedules doing the same job
- [x] 1.5 `persistence/GroupStateRepository` — the two aggregate queries backing 1.4
- [x] 1.6 `sweep/GroupReadinessSweep` — a sweep-duration timer and an enqueue counter
- [x] 1.7 `worker/group/GroupWorkerService` — a group-throughput counter (evaluated, dead-lettered)
- [x] 1.8 Cardinality check: no new series tagged by a group key. Group detail stays on spans, in the MDC, and
  in the introspection endpoint

## 2. Tracing

- [x] 2.1 `scan/RuleScanExecutor` — the missing `enrichment.dirty-mark` span around the group page's
  disposition, with `rule.id`; the new lanes added every other span but this one
- [x] 2.2 Verify the new lanes' span set is complete: `dirty-mark`, `group-sweep`, `group-assembly`,
  `materialize`, plus the row lane's `scan-tick`, `dial-call`, `upsert`

## 3. Group admin

- [x] 3.1 `web/controller/GroupAdminController` — `GET /v1/rules/{id}/groups`: per group the version, computed
  version, truncation flag and evaluation count. Identifiers, versions, timestamps and counts only — never
  member rows or labels
- [x] 3.2 `web/dto/admin/{GroupStateDto,GroupStateResponse}` + a MapStruct mapper; bound the page size the way
  `DlqController` clamps its limit
- [x] 3.3 `POST /v1/rules/{id}/groups/{groupKey}/requeue` — re-enqueue via `DeterministicId.groupInstanceId`,
  so the override cannot duplicate an item already queued for that version. Deliberately bypasses the readiness
  windows; does **not** bypass the ceiling, since the evaluation it triggers is charged like any other
- [x] 3.4 404 for an unknown rule or group, in the standard `ErrorView` shape

## 4. Runbook & documentation

- [x] 4.1 `docs/e2e-runbook.md` — add the group walkthrough: register a `chat_id`-grained enrichment over
  `dial_usage_log` plus a group rule, seed rows sharing a `chat_id`, watch mark → sweep → evaluate, assert one
  row per group key, then re-ingest a member and watch `group_version` rise and the group re-fire
- [x] 4.2 `docs/e2e-runbook.md` — add the Class-1 walkthrough: a `type=sql` rule populates its column with
  **zero** DIAL traffic (assert on the DIAL call counter, not by eyeballing)
- [x] 4.3 `docs/e2e-runbook.md` — dataset-agnostic throughout: no conversations/turns tables, which belong to a
  change that has not landed
- [x] 4.4 `docs/configuration.md` — drop the stale milestone-by-milestone framing
- [x] 4.5 `README.md` — fix the reference to the non-existent `openspec/project.md`, and the port
  inconsistency (README says 5100, `CLAUDE.md` says 5000 — reconcile against `application.yml`)

## 5. Opportunistic debt

- [x] 5.1 Write a real `## Purpose` for each of the nine main specs still carrying
  `TBD - created by archiving change …`. They are the first thing a reader of a capability sees

## 6. Verification

- [x] 6.1 Test: `/actuator/prometheus` exposes the new meters — per-task queue depth, the 429 counter, the
  spend gauge, the group gauges
- [x] 6.2 Test: a 429 increments both the rate-limit counter and the error counter
- [x] 6.3 Test: no exposed series is tagged by a group key (assert over the registry's meter names and tags)
- [x] 6.4 Test: group introspection lists a rule's groups with versions and counts and **no** member content
- [x] 6.5 Test: per-group requeue enqueues exactly one item, and a second requeue for the same version adds
  none
- [x] 6.6 Test: the group-state gauges are refreshed off the scrape path (a scrape issues no `group_state`
  query)
- [x] 6.7 `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build` passes
- [x] 6.8 `openspec validate add-phase2-ops --strict` passes
