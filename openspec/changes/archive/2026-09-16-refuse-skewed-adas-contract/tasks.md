## 1. The contract handshake

- [x] 1.1 Add `AdasContract` (`adas/`) holding `VERSION = 1` and `HEADER = "Adas-Contract-Version"`, with
      javadoc stating that only equality is defined, that it is a build property rather than configuration,
      and that it must match ADAS's constant exactly.
- [x] 1.2 Add `AdasContractMismatchException` (`exception/`) carrying the version echoed, or its absence.
      Not the version declared alongside it: that is always `AdasContract.VERSION`, and a copy of it on the
      exception would be a second spelling of one constant.
- [x] 1.3 Add `AdasCompatibilityState` (`adas/`): compatible flag and reason, with
      `markCompatible()` / `markIncompatible(reason)` that log the **transition** only at ERROR (the
      `refusedGenerations` pattern in `PipelineSyncService`), so a condition met on every scan tick is
      logged once.
- [x] 1.4 Add `AdasContractInterceptor` (`ClientHttpRequestInterceptor`) and register it on the single
      `RestClient` bean in `configuration/AdasClientConfiguration`: set the request header on every call,
      and on the response either `markCompatible()` or `markIncompatible(...)` + throw. The interceptor
      owns the state because it is the only component that sees every response — a skew that starts between
      polls is met first by a scan tick or a write-back, and a verdict recorded only by the poll would let
      the paid-work gate read a stale `compatible` for up to a poll interval.

## 2. Sync behaviour under a skew

- [x] 2.1 `PipelineSyncService.sync()`: catch `AdasContractMismatchException` separately from the existing
      `RuntimeException` arm so it logs at ERROR naming the condition, and keep the existing guarantees —
      cache and tasks kept, `reconcile` not entered, sync **not** marked succeeded. The state itself is
      flipped by the interceptor (1.4), not here.
- [x] 2.2 `PipelineRouting.unsupportedReason`: add an `evaluator == null` arm and a blank `grain_key` arm,
      each with the reason text and a comment naming the skew that makes them reachable — specifically an
      entry that drops the evaluator while keeping `outputs`, which the existing `outputs` arm does not
      catch and which `fold-evaluator-into-pipeline` produces.
- [x] 2.3 `PipelineSyncService.reconcile`: require two consecutive empty feeds before cancelling; the first
      logs WARN and counts `runner.sync.empty.feeds`, and a non-empty poll resets the count. The first
      empty feed **still** calls `readinessState.markPipelineSyncSucceeded()` — the poll succeeded, and
      withholding readiness would keep a fresh pod out of service over a legitimately empty registry.

## 3. Scan-path invariants

- [x] 3.1 `ChunkBuilder`: test `containsKey` for the grain key before reading it — missing key throws
      naming the pipeline and the column; present-but-null stays a skip and is counted by reason.
- [x] 3.2 `scan/group/GroupMarkBuilder`: the same split for `trigger.group_by`, **and** for the
      `version_column` it reads alongside it. The version column is the sharper case: `versionOf` already
      treats an absent key as an error but dead-letters the row, so a page whose version column is missing
      from the projection dead-letters every row of every page. Missing key → throw; present with a null or
      non-instant value → keep the existing dead-letter, because that is one row's data defect.
- [x] 3.3 Carry the per-page accounting through `ChunkBatch` and `GroupMarkBatch`: chunked or marked,
      dead-lettered, and skipped-by-reason counts alongside `rowCount`, so the counts and the row count come
      from the same object.
- [x] 3.4 `ScanPageCommitter.commit`, `GroupDirtyMarkCommitter` **and `BackfillPageCommitter`**: refuse to
      persist the cursor advance unless the accounted counts **equal** the page's row count; fail the tick
      and count `runner.scan.pages.refused`. Equality, not "at least one": the builders route every row to
      exactly one outcome, so a partially unaccounted page is as much a bug as a wholly unaccounted one.
      Take the batch record rather than the two lists so the committer cannot be handed counts from a
      different page. The backfill is not optional here — it shares `ChunkBuilder`/`GroupMarkBuilder` but
      has its own committer and its own cursor, and a run that skips silently drains the whole window and
      then marks itself complete.

## 4. Do not pay for work that cannot be written

- [x] 4.1 Gate all **three** pipeline-scoped work lanes (`WorkTasks.PIPELINE_SCOPED`) on
      `AdasCompatibilityState` before any ADAS call, DIAL call or write: `WorkerService.process`,
      `GroupWorkerService.process` and `UpsertService.write`. The group lane is the one most easily missed
      and the costliest per item — it assembles a group's members through ADAS and then makes one model
      call over them. This is new machinery on these three lanes, not a reuse of the pause check: `isPaused`
      gates *enqueueing* in the scan/backfill/sweep/materialize executors and none of the three work-item
      services consults it.
- [x] 4.2 Hold, rather than fail, in all three failure handlers (`ChunkFailureHandler`,
      `GroupFailureHandler`, `UpsertFailureHandler`): on an `AdasContractMismatchException` cause (or the
      gate's own hold signal), reschedule with a short fixed delay using `ExecutionComplete.success(...)`,
      which writes `consecutive_failures = 0` — so the hold takes neither the backoff ladder nor the
      archive branch, and a skew that lasts for hours does not leave the next genuine failure archiving on
      its first attempt. Never archive on this cause, and keep it distinct from `PoisonGroupException`,
      which the group handler must still treat as permanent. Implemented by the shared `WorkHold` —
      `AdasContractException` extends `HeldWorkException`, so the skew takes the same hold arm as a cold
      pipeline cache instead of a second copy of it (see design.md §5).
- [x] 4.3 Cover both arms: an item due while the state already reads incompatible makes no call at all, and
      an item whose own call discovers the skew is held on that refusal rather than failed.

## 5. Metrics

- [x] 5.1 `RunnerMetrics`: register `runner.adas.contract.compatible` (untagged gauge, at startup, reading
      `AdasCompatibilityState`) and the `runner.sync.empty.feeds` counter.
- [x] 5.2 `RunnerMetrics`: add `runner.scan.rows.skipped{pipeline,reason}` and
      `runner.scan.pages.refused{pipeline,reason}`, and evict both in `evictPipeline` alongside the existing
      per-pipeline meters.

## 6. Tests

- [x] 6.1 WireMock: a feed response with no echo header, and one with a different value — assert the cache
      and tasks are unchanged, `reconcile` is not entered, readiness stays DOWN for a fresh state, the
      gauge reads `0`, and the ERROR is logged once across repeated polls.
- [x] 6.2 WireMock: the matching echo restores normal reconciliation with no operator action; assert the
      request header is present on all five call sites; assert a data-plane call (not only the poll) flips
      the state, so the gate cannot read a stale `compatible`. Stub and assert against
      `AdasContract.VERSION`, never the literal `1`, so a bump moves the fixtures with the constant instead
      of leaving one pinning the old number.
- [x] 6.3 Admission tests: an entry with no evaluator **but `outputs` present** is refused (so the test
      pins the new arm rather than the pre-existing `outputs` one), an entry with a blank `grain_key` is
      refused, both counted on `runner.pipelines.unsupported` and logged once per generation.
- [x] 6.4 Scan tests: a page whose rows lack the grain column leaves the `pipeline_cursor` row unchanged
      and writes no DLQ item; the same for a group page lacking `group_by`, and for one lacking the version
      column — the last asserting **no** DLQ item is written, which is the behaviour change; a page with a
      null grain value still advances and counts the skip; a group row with an unreadable version value is
      still dead-lettered.
- [x] 6.5 Committer test, for each of the three committers: a wholly unaccounted page persists no advance
      and counts the refusal; so does a partially unaccounted one; a page accounted for entirely by sampling
      still advances; an empty page is unaffected. For the backfill, additionally assert the run is not
      marked complete by a refused batch.
- [x] 6.6 Empty-feed test: one empty poll changes nothing but the counter **and still marks the sync
      succeeded** (readiness UP from a fresh state); two consecutive polls reconcile to empty; a non-empty
      poll in between resets.
- [x] 6.7 Worker test, per lane: a due chunk, a due **group** chunk and a due write-back are held while
      incompatible — no ADAS group-rows call, no DIAL call, no attempt consumed, no archive — and execute
      once compatibility returns. Plus: an item held many times and then failing transiently retries with
      its full attempt budget, which is what the `ExecutionComplete.success` reschedule buys.

## 7. Specs and docs

- [x] 7.1 Confirm the delta specs match what shipped (`pipeline-sync`, `incremental-scan`, `backfill`,
      `enrichment-worker`, `observability`) and run
      `openspec validate refuse-skewed-adas-contract --strict`.
- [x] 7.2 Update `openspec/config.yaml`'s *ADAS integration contract* section: the header on every call,
      the echo requirement and its `/v1/pipelines` scope (all five call sites lie under it; one added
      elsewhere would receive no echo), replacing the line stating that none of the runner's clients
      branches on a status or error code; and add the ADAS half's new codes to the Errors line — 409
      `runner_contract_required`, 409 `runner_contract_mismatch`, 422 `write_output_column_required`. No
      `docs/configuration.md` row — this change adds no env-driven config.
- [x] 7.3 Add the deploy note to `docs/e2e-runbook.md`: both orders after this change, and the DLQ requeue
      caveat for write-backs archived during an ADAS-first window.

## 8. Verification

- [x] 8.1 `./gradlew clean build` passes (compile + checkstyle + tests); prefix with
      `TESTCONTAINERS_RYUK_DISABLED=true` if the reaper cannot start in this Docker environment.
