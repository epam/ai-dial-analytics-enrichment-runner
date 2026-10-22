## 1. Scan client & config

- [x] 1.1 Add `ScanRequest`/`ScanResponse` DTOs and `AdasScanClient`
- [x] 1.2 Add `ScanProperties` (scan-limit, chunk, max-inflight, max-pages-per-fire); wire
  `application.yml` + `docs/configuration.md`

## 2. Scan pipeline

- [x] 2.1 `Sampler` (portable FNV-1a 64-bit hash, deterministic + monotone)
- [x] 2.2 `InputMapper` (bound column / JSONata) + `InputMappingException`
- [x] 2.3 `DeterministicId` (sha1 chunk instance id + rule prefix)
- [x] 2.4 `ChunkPayload`/`ChunkRow` work-item POJOs

## 3. Persistence

- [x] 3.1 `RuleCursorEntity` + repository
- [x] 3.2 `DlqItemEntity` + repository + `DlqStage`

## 4. Executor & wiring

- [x] 4.1 `EnrichChunkTask` (one-time, placeholder handler)
- [x] 4.2 `ScanPageCommitter` (`@Transactional` enqueue + DLQ + cursor advance)
- [x] 4.3 `RuleScanExecutor` (drain loop, sampling, mapping, chunking, backpressure, lifecycle guard)
- [x] 4.4 Wire `RuleScanTask` → executor via `ObjectProvider`; register both tasks in `DbSchedulerConfiguration`

## 5. Verification

- [x] 5.1 Unit tests: `Sampler`, `DeterministicId`, `InputMapper`
- [x] 5.2 Integration test: scan → enqueue + cursor atomic; idempotent re-scan; input-map DLQ;
  rollback atomicity (WireMock ADAS + Testcontainers PG)
- [x] 5.3 `./gradlew clean build` passes
- [x] 5.4 `openspec validate add-incremental-scan --strict` passes
