## 1. Pre-flight

- [x] 1.1 Re-read each MODIFIED requirement in this change's deltas (`pipeline-sync`: "Pipeline registry sync", "Recurring task reconciliation", "Pipeline admission gate"; `group-enrichment`: "Dirty-mark rides the scan cursor"; `incremental-scan`: "Defensive page and chunk sizing") against its current `openspec/specs/<capability>/spec.md` block. Re-extract any that moved after 2026-10-05, so the delta does not revert shipped text at archive
- [x] 1.2 Confirm the paired ADAS build is on `RunnerContract.VERSION_VALUE = "3"` (ADAS `openspec/changes/retire-on-ingest-trigger/`, already applied) and read its `V44` header for the exact feed shape

## 2. Contract and feed model

- [x] 2.1 Raise `AdasContract.VERSION` from 2 to 3. Fix any test that pins the literal rather than the constant
- [x] 2.2 Remove `scanEvery` from `AdvancedDto` and `PipelineDto.scanEvery()`, and update `FeedShapeTest` / any golden feed fixture so `advanced` has four members
- [x] 2.3 Keep `TriggerKind.ON_INGEST`, re-documented as "retired; modelled only so the feed parse survives it; refused at admission"

## 3. Scheduling

- [x] 3.1 Add `Duration scanCadence` to `GroupProperties` (`group.scan-cadence: ${GROUP_SCAN_CADENCE:1m}` in `application.yml`)
- [x] 3.2 Rewrite `ScheduleFactory.forPipeline`:
  - `SCHEDULE` → cron (unchanged, and still throws when the cron is blank);
  - `GROUP` → `fixedDelay(group.scanCadence)`;
  - anything else → `IllegalArgumentException`.

  Make `cadence()` follow the same rules (cron gap / group cadence). Remove the `ScanProperties` and `Class1Properties` dependencies if nothing else needs them there
- [x] 3.3 Remove `defaultCadence` from `ScanProperties` and `Class1Properties`, and `scan.default-cadence` / `class1.default-cadence` from `application.yml`
- [x] 3.4 `PipelineRouting.unsupportedReason`: add the first-position arm refusing `TriggerKind.ON_INGEST` ("trigger kind 'on_ingest' is retired; ADAS serves it as 'schedule'"), and an arm refusing a `schedule` entry with a blank `trigger.cron` (design: "Row grain is cron-only")
- [x] 3.6 `PipelineSyncService.reconcile` (design: "No runner state migration"): collect each existing instance's stored `PlainScheduleAndData` in the `fetchScheduledExecutionsForTask` pass, and reschedule when it differs from the desired one as well as on a generation change. A pipeline whose reconcile threw keeps its previous cache entry (or none) instead of being cached at its new generation
- [x] 3.5 Optional (design: "Delete the two lane defaults outright"): a once-per-generation `WARN` when a group pipeline's `ready_when.idle` (or the `group.default-idle` fallback) is below `5 × group.scan-cadence`. Do it only if it fits the existing `refusedGenerations`-style idiom without new state; otherwise record it as skipped here.
  **Skipped:** the warning needs a per-pipeline "last warned generation" map beside `refusedGenerations`, which is new state; `refusedGenerations` itself only holds refused pipelines, and a group pipeline that trips the hint is admitted

## 4. Tests

- [x] 4.1 `TestPipelines`:
  - the builder's default trigger becomes `SCHEDULE` with a cron, chosen so existing timing-sensitive tests keep their behaviour (check what the 30s fixed delay was protecting in each caller before choosing);
  - drop `scanEvery`;
  - replace `onIngest(...)` / `class1(..., scanEvery)` with cron-taking variants.

  Update all ~31 callers
- [x] 4.2 `ScheduleFactory` unit tests:
  - a `schedule` pipeline gets a `CronSchedule` on both lanes;
  - a `group` pipeline gets `fixedDelay(group.scanCadence)`;
  - a blank cron throws;
  - `cadence()` reports the cron gap and the group cadence
- [x] 4.3 Admission-gate test: an `on_ingest` entry is refused naming the kind, gets no task under any name, and the rest of the feed is admitted
- [x] 4.4 Sync integration test (pattern of `PipelineSyncIntegrationTest`):
  - a persisted instance scheduled with a fixed delay is rescheduled to its cron on the first poll after a restart (empty cache, same `generation`);
  - the same instance, `picked` at the first poll, is rescheduled by a later poll once released;
  - a poll whose stored schedule already equals the desired one reschedules nothing
- [x] 4.6 Admission-gate test: a `schedule` entry with a blank cron is refused naming the cron and gets no task, and the rest of the feed is admitted
- [x] 4.7 Leftover tests that still build `on_ingest` or `scan_every`: `PipelineStatusReporterTest`, `PipelineStatusResolverTest`, `PipelineRuntimeIntegrationTest`, `PipelineCacheTest`, `PipelineRoutingTest`, `OpsSignalsIntegrationTest`, `FeedShapeTest`, `ScheduleFactoryTest` (re-grep before closing)
- [x] 4.5 Fix `OpsIntegrationTest`, `EmptyFeedConfirmationIntegrationTest`, `AdasContractIntegrationTest` and `PipelineSyncIntegrationTest` for the removed `scan_every` / `on_ingest` fixtures

## 5. Docs

- [x] 5.1 `docs/configuration.md`: delete the `SCAN_DEFAULT_CADENCE` and `CLASS1_DEFAULT_CADENCE` rows, and add one `GROUP_SCAN_CADENCE` row (`group.scan-cadence`, `1m`, No, "Fixed delay of a group pipeline's mark scan.") in the group table
- [x] 5.2 (done in `docs/release-0.3-upgrade.md`; `docs/release-0.2-upgrade.md` is not edited) In the release-0.3 upgrade guide, add a section covering:
  - contract 3, deployed as a pair with ADAS (runner to 0, ADAS, runner, runner to 1);
  - drop any `SCAN_DEFAULT_CADENCE` / `CLASS1_DEFAULT_CADENCE` override, because it is now silently ignored;
  - `GROUP_SCAN_CADENCE` replaces a group pipeline's `scan_every` (a group pipeline that scanned every 5m now scans every 1m by default);
  - the restart applies the new schedules, with no state migration.

  Add the config rows to its configuration section
- [x] 5.3 `docs/e2e-runbook.md`:
  - fixtures use `trigger.kind: schedule` with a cron instead of `on_ingest` and `advanced.scan_every`;
  - the group-backfill proof (around line 409) uses a long `GROUP_SCAN_CADENCE` instead of a long `scan_every`;
  - recheck the "scan_every-only patch" measurement note (around line 512) still reads correctly

- [x] 5.4 Stale mentions outside the deltas: `openspec/config.yaml` (trigger kinds `on_ingest|schedule|group`; `advanced`{scan_every, …}), and the comments in `PipelineScanTask` (fixed delay for `on_ingest`), `JsonMapperConfiguration` (`on_ingest` as an example enum code), `ScanProperties` and `Class1Properties` (go with 3.3)

## 6. Review follow-ups

- [x] 6.0.1 Keep a pipeline whose reconcile threw cached at its current declaration (design: "No runner state migration"): leaving it out made
  `WorkerService` drop its queued chunks irreversibly after a restart. The create path and the stored-schedule
  comparison retry it anyway
- [x] 6.0.2 A restart (`cached == null`) no longer reschedules an instance whose stored schedule matches, so an
  overdue cron fire is not pushed to its next slot. `reschedule` returning false (version race) is not counted, and
  a `TaskInstanceCurrentlyExecutingException` is left to the next poll at DEBUG
- [x] 6.0.3 Admission also refuses an entry with no trigger, and a `schedule` cron that db-scheduler's or Spring's
  parser rejects
- [x] 6.0.4 `group.scan-cadence` fails startup when missing or below `1s`
- [x] 6.0.5 `ScheduleFactory` dispatches with exhaustive switches; `TestPipelines` emits a cron only on a
  `schedule` trigger
- [x] 6.0.6 The status heartbeat's stall threshold for a group pipeline was `3 × group.scan-cadence` (3m by
  default, previously 15m for a group pipeline scanning every 5m), below any realistic `ready_when.idle`, so a busy group pipeline with
  lag could read as stalled before any group was ready. It is now the longer of that and the effective idle window
  plus `group.sweep-interval`; `max_staleness` and a signal only make a group ready sooner and are not added
- [x] 6.0.7 Admission WARNs once per (pipeline, generation) when a group pipeline's effective idle window is below
  twice `group.scan-cadence`: a later row can then sit unmarked long enough for a still-active group to read idle
  and be evaluated early and again. A warning, not a refusal

## 7. Verification

- [ ] 7.1 Local end to end against the paired ADAS (contract 3, after V44), using the local runner harness:
  - the three migrated `sql` pipelines keep `materialized_through_*` across the runner restart and fire on their derived second;
  - a pipeline whose scan was killed mid-run by the scale-down still ends up on its cron;
  - a group pipeline marks every minute and keeps labelling;
  - an `on_ingest` entry injected into a stubbed feed is refused

  **Local run** (local ADAS with V42–V44 applied over a copy of its local database; this runner on a copy of its local
  state database):
  - pass: V44 turned `request-shape-live` (`cadence 20s`) into `43 * * * * *`, matching the MD5 rule, with
    `generation` 5 and `materialized_through_*` unchanged. The first poll rescheduled its persisted `fixedDelay 20s`
    instance onto the cron ("stored schedule differed"), and it fires on :43. It cannot advance, because its derivation
    reads `request_body`, which ClickHouse V11 dropped earlier (local drift, unrelated to this change);
  - pass: a cronless `schedule` PATCH got ADAS's name-derived cron; `e2e-shape-live` fired on :17 and materialized;
  - pass: an instance picked by a dead pod with `fixedDelay 20s` was hidden at the first poll, revived on its fixed
    delay, and moved to its cron by a later poll ("generation=3->3, stored schedule differed");
  - pass: the group mark scan runs on `fixedDelay 60s` (it was `cadence 3600s`) and marks new chats each tick;
  - **not verified: group labelling.** The DIAL key in the local run config was rejected with 401, so no model call
    was charged. Re-run with a valid key;
  - `on_ingest` refusal: covered by the stubbed-feed integration test
    `retiredOnIngestPipelineIsRefusedAndTheRestOfTheFeedIsAdmitted`, because real ADAS cannot serve it after V44.
- [x] 7.2 `openspec validate retire-on-ingest-trigger --strict` passes
- [x] 7.3 `./gradlew clean build` passes
