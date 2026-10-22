## Why

ADAS retires `trigger.kind: on_ingest` and `advanced.scan_every` (paired ADAS change
`retire-on-ingest-trigger`, contract version 3). `on_ingest` was a fixed-delay timer over the same
cursor-incremental row scan a `schedule` pipeline runs, and its name promised event-driven delivery
that never existed. After the ADAS change, every row-grain pipeline arrives as `schedule` with a cron,
and no feed entry carries `scan_every`. The runner must stop reading both, and it must speak contract 3,
or ADAS refuses every data-plane call.

## What Changes

- **BREAKING (contract)** `AdasContract.VERSION` goes from 2 to 3, the exact match the paired ADAS build
  requires and echoes.
- **BREAKING** The fixed-delay branch for row-grain pipelines is removed. A row-grain pipeline
  (`pipeline-scan` and `class1-materialize` lanes alike) is scheduled only from its `trigger.cron`.
- A **group** pipeline's mark scan, which only records dirty marks and never calls a model, runs on a
  fixed delay from a new runner property `GROUP_SCAN_CADENCE` (`group.scan-cadence`, default `1m`)
  instead of the pipeline's `advanced.scan_every`.
- `AdvancedDto.scanEvery` / `PipelineDto.scanEvery()` are removed; the runner no longer reads
  `advanced.scan_every`.
- `SCAN_DEFAULT_CADENCE` (`scan.default-cadence`) and `CLASS1_DEFAULT_CADENCE`
  (`class1.default-cadence`) are removed, since nothing falls back to them any more.
- Reconcile reschedules an instance when its **stored schedule differs** from the derived one, as well as on a
  `generation` bump. A restart alone no longer reschedules a matching instance, which used to push overdue cron
  fires to their next slot. Without the comparison, the restart that is supposed to apply V44's generation-less
  rewrite misses any instance still picked by an execution the scale-down killed. A pipeline whose reconcile
  threw stays cached, and the comparison retries it.
- A `schedule` pipeline with no `trigger.cron` or an unparseable one, and an entry with no trigger, are **refused
  by the admission gate** rather than throwing in reconcile after being cached as healthy.
- `TriggerKind.ON_INGEST` stays modelled, so an unexpected value cannot fail the whole feed parse. It is
  **refused by the admission gate** with a reason naming the retired kind.
- The pipeline status heartbeat's cadence (`ScheduleFactory.cadence`) follows the same rules: the cron
  gap for a row-grain pipeline, and `GROUP_SCAN_CADENCE` for a group pipeline.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `pipeline-sync`: "Pipeline registry sync" no longer lists `scan_every` among the `advanced` knobs
  it reads, and its enum-binding scenario uses `schedule`. "Recurring task reconciliation" derives a
  row-grain schedule from the cron only, a group schedule from `GROUP_SCAN_CADENCE`, and reschedules on a
  stored-schedule mismatch as well as a generation change. "Pipeline admission gate" refuses `on_ingest` and a
  cronless `schedule` pipeline. "Every ADAS call declares a contract version and every response is checked for its
  echo" records the raise to 3.
- `group-enrichment`: "Dirty-mark rides the scan cursor" states the mark scan's cadence is
  `GROUP_SCAN_CADENCE`, and why it is runner configuration rather than a per-pipeline knob.
- `incremental-scan`: "Defensive page and chunk sizing" stops describing the knob defaults in terms of the
  retired `cadence`.

## Impact

- **Code**: `PipelineSyncService.reconcile` (stored-schedule comparison), `ScheduleFactory`, `ScanProperties`, `Class1Properties`, `GroupProperties`, `AdvancedDto`,
  `PipelineDto`, `TriggerKind`, `PipelineRouting.unsupportedReason`, `AdasContract`,
  `PipelineStatusResolver` (through `ScheduleFactory.cadence`), stale comments in `PipelineScanTask` and
  `JsonMapperConfiguration`, `openspec/config.yaml` (trigger kinds and `advanced` members), and the tests
  that build `on_ingest` fixtures or set `scan_every`.
- **Config**: `application.yml` drops `scan.default-cadence` and `class1.default-cadence` and adds
  `group.scan-cadence`. `docs/configuration.md` loses two rows and gains one.
- **Docs**: the release-0.3 upgrade guide (contract 3, the config change, deploy order), deferred until
  that guide exists; `docs/release-0.2-upgrade.md` is not edited. `docs/e2e-runbook.md` (fixtures use `schedule`; the group-backfill proof uses `GROUP_SCAN_CADENCE`
  instead of a long `scan_every`).
- **Deploy**: ships with ADAS release 0.3 in one window, as a pair (runner scaled to 0, ADAS deployed, runner deployed). Any environment that overrides
  `SCAN_DEFAULT_CADENCE` or `CLASS1_DEFAULT_CADENCE` should drop the override, since Spring ignores it
  silently.
- **Runner state**: none migrated. ADAS's V44 rewrites the triggers without bumping `generation`. The
  runner picks up the new schedules because reconcile compares each instance's stored schedule with the
  derived one, and retries every poll until they match. An instance still picked at the first poll is hidden
  from the listing, and is compared again at a later poll.
