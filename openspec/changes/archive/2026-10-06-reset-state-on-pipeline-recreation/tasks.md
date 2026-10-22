## 1. Feed and schema

- [x] 1.1 `PipelineDto.createdAt` (`created_at`), truncated to microseconds; javadoc no longer lists it as ignored.
- [x] 1.2 `V13__pipeline_cursor_created_at.sql`: nullable `pipeline_cursor.pipeline_created_at`; read-only entity mapping.
- [x] 1.3 `PipelineCursorRepository`:
  - `adopt`, which inserts the row or fills a NULL column;
  - `insertIfAbsent`;
  - `findForUpdate`;
  - `resetOwner`, the reset's compare-and-set.

## 2. Guarded cursor write

- [x] 2.1 `PipelineCursorWriter`: `lockOwned` (insert-if-absent, lock, refuse on mismatch with `PipelineRecreatedException`) first in the commit, `advance` (the guarded cursor and lag update) last; `lockAndAdvance` for Class-1.
- [x] 2.2 `ScanPageCommitter`, `GroupDirtyMarkCommitter` and `Class1MaterializeExecutor` use it and take the tick's `created_at`.
- [x] 2.3 `PipelineScanExecutor` and `Class1MaterializeExecutor` end the fire quietly on the refusal.

## 3. Reset

- [x] 3.1 `RunHistoryRepository.cancelActiveForPipeline`, `GroupStateWriter.deleteAll`, `InflightCounter.deletePendingForPipeline`.
- [x] 3.2 `PipelineStateReset.reset`: one transaction; the cursor compare-and-set first, then runs, groups, queue; nothing when the compare-and-set misses.
- [x] 3.3 `PipelineSyncService.resetRecreated`:
  - adopt, compare and reset;
  - in-memory eviction plus `PipelineRunOutcome.forget` and `PipelineActivity.forget`;
  - `PipelinePauseRegistry.clearBreakerPause`;
  - forced reschedule;
  - WARN line and `runner.pipeline.state.resets`.

## 4. Tests

- [x] 4.1 `PipelineRecreationIntegrationTest`:
  - first sighting, adoption, unchanged across polls and a re-enable;
  - recreation across polls and within one poll at the same generation;
  - operator pause kept;
  - stale commit refused with nothing enqueued;
  - a commit leaves the recorded value alone.
- [x] 4.2 Existing tests updated for the new commit signatures and the first-sighting row.

## 5. Specs and docs

- [x] 5.1 `pipeline-sync`, `incremental-scan`, `class1-enrichment`, `service-runtime` and `observability` specs.
- [x] 5.2 `docs/e2e-runbook.md`: a pipeline recreated under the same name.
- [x] 5.3 ADAS: `pipeline-registry` states that `created_at` is immutable; the rationale in `catalog-export-import` and `docs/catalog-promotion.md` is reworded.
