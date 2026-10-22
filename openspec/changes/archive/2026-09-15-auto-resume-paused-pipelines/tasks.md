## 1. A pause that knows what it is

- [x] 1.1 Add `pause/PauseOrigin` (`OPERATOR`, `BREAKER`) and a `PipelinePauseRegistry.Pause` record
      carrying origin, reason, `since` and a nullable `resumesAt`. Javadoc the asymmetry: an operator
      pause is a decision and must outlive anything the runner concludes; a breaker pause is an inference
      about an upstream, and an inference that cannot expire is indistinguishable from a bug.
- [x] 1.2 `PipelinePauseRegistry`: replace the `Set<String>` with a `ConcurrentHashMap<String, Pause>`,
      inject `Clock`, and give it `pause(name, origin, reason)` / `pauseUntil(...)`, `resume(name)`,
      `isPaused(name)`, `paused()` and `pausedCount()` / `autoPausedCount()`.
- [x] 1.3 Evaluate expiry **lazily, in `isPaused`** — the single read path for the scan executor, the
      sweep, the materialize executor and the breaker itself — removing an expired auto-pause and logging
      the resume at WARN. A comment says why this is not a scheduled reaper: the read is the only moment
      the answer matters, so no stale entry is ever observable.

## 2. Widening and recovery

- [x] 2.1 `DlqProperties.Breaker`: add `pauseFor` (default `15m`) and `pauseMax` (default `4h`), with the
      non-positive-disables convention the other levers already use.
- [x] 2.2 `DlqCircuitBreaker`: pass `PauseOrigin.BREAKER` and a reason naming what tripped it (which lever,
      what count out of what). Track consecutive auto-pauses per pipeline and compute the window as
      `pauseFor × 2^(attempts−1)` capped at `pauseMax`, stating in the javadoc what the widening buys — at
      a window of 50, every probe of an unfixed upstream costs fifty assemblies and fifty model calls.
- [x] 2.3 Reset the consecutive count when a pipeline's outcome window fills **without** tripping: the same
      evidence the trip was made from, so recovery is judged on it. An operator's manual resume does not
      reset it, and the code says why.
- [x] 2.4 `evict(pipelineName)` clears the attempt count alongside the outcome window.

## 3. Surfaces

- [x] 3.1 `web/dto/admin/PausedPipelineDto` (`pipeline_name`, `origin`, `reason`, `since`, `resumes_at`)
      and `PausedPipelinesResponse` holding a list of them. Mapped in the controller rather than through
      MapStruct: the source is a `Map.Entry` of runtime state, not an entity, so a mapper would carry the
      key/value flattening and the enum lowercasing as hand-written default methods anyway.
- [x] 3.2 `PipelineAdminController`: `pause` records `PauseOrigin.OPERATOR` with an operator reason;
      `paused()` returns the enriched listing.
- [x] 3.3 A recurring WARN in `PipelinePauseRegistry` naming every paused pipeline with origin, reason and
      age, on a `@Scheduled` fixed delay resolved from a placeholder (not bound in a properties record —
      the `group.sweep-interval` convention). Nothing when none is paused.
- [x] 3.4 `RunnerMetrics`: `runner.pipelines.paused.auto` beside the existing total, untagged, with the
      comment explaining why the total cannot carry an alert.

## 4. Docs

- [x] 4.1 `docs/configuration.md`: rows for `DLQ_BREAKER_PAUSE_FOR`, `DLQ_BREAKER_PAUSE_MAX` and the pause
      report interval, one line each.
- [x] 4.2 `docs/e2e-runbook.md`: what an auto-paused pipeline looks like from the listing, the log and the
      metric, and that ADAS will report it `enabled` throughout.

## 5. Tests

- [x] 5.1 Registry test on a settable clock: an auto-pause expires and reports resumed; an operator pause
      does not, at any elapsed time; a non-positive window disables expiry.
- [x] 5.2 Breaker test: consecutive trips widen the pause to the cap; a full clean window resets it; a
      manual resume does not.
- [x] 5.3 Listing content is asserted on the registry (`PipelinePauseRegistryTest`), which is where the
      fields originate; the controller only flattens the map. No new `@SpringBootTest` — an extra
      application context costs the shared PostgreSQL container's connection budget, and the suite is
      already near it.
- [x] 5.4 Auto-count test: it counts only breaker pauses and returns to zero once the window elapses.

## 6. Specs

- [x] 6.1 Confirm the delta specs match what shipped (`resilience-and-dlq`, `runner-ops`, `observability`)
      and run `openspec validate auto-resume-paused-pipelines --strict`.

## 7. Verification

- [x] 7.1 `./gradlew clean build` passes (compile + checkstyle + tests); prefix with
      `TESTCONTAINERS_RYUK_DISABLED=true` if the reaper cannot start in this Docker environment.
