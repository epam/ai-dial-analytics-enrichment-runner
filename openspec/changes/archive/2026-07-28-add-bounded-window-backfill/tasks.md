## 1. Window model

- [x] 1.1 `adas/dto/ScanRequest` += `from`, `to` (`Instant`), with a two-arg convenience constructor so the
  live tick's call sites are unchanged — mirroring what `MaterializeRequest` already does
- [x] 1.2 `adas/AdasScanClient.scan` — a windowed overload; the existing signature delegates with a null window
- [x] 1.3 `backfill/BackfillWindow` record (`from`, `to`) with the `from < to` validation and a null-safe
  "unbounded" representation, so every layer passes one thing rather than two nullable fields
- [x] 1.4 `web/dto/backfill/BackfillRequest` — the optional window on the start endpoint

## 2. Persistence

- [x] 2.1 `db/migration/postgres/V6__run_history_window.sql` — `run_history` += `window_from TIMESTAMPTZ`,
  `window_to TIMESTAMPTZ`. Additive; an existing run reads as unbounded, which is what it was
- [x] 2.2 `persistence/RunHistoryEntity` += `windowFrom`, `windowTo`
- [x] 2.3 `web/dto/backfill/BackfillRunDto` + `web/mapper/BackfillRunMapper` — expose the window, so an
  operator can tell what a past run covered

## 3. Lifecycle & validation

- [x] 3.1 `backfill/BackfillService.start(ruleId, window)` — persist the window on the run; keep the existing
  404 (unknown rule) and 409 (already running) behaviour
- [x] 3.2 Reject an inverted or empty window with a `400` at the runner's API — creating a run that could only
  fail on its first batch puts the error far from the operator's mistake. New exception +
  `web/handler/DefaultExceptionHandler` mapping, in the standard `ErrorView` shape
- [x] 3.3 `web/controller/BackfillController` — accept the optional body on `POST /v1/rules/{id}/backfill`;
  an absent body stays a full replay
- [x] 3.4 Resume retains the run's window (it is read from `run_history`, so this falls out — assert it)

## 4. Three-lane backfill

- [x] 4.1 `backfill/BackfillExecutor` — route by `RuleRouting`, mirroring the live path: row → scan+chunks,
  group → dirty-mark, Class-1 → materialize. Keep the run lifecycle (state, cursor, pause/resume, chaining,
  gates, stall guard) shared rather than per-lane
- [x] 4.2 Row lane: pass the window to every `scan` call
- [x] 4.3 Group lane: `GroupMarkBuilder` + commit marks against the **run** cursor. Do **not** enqueue group
  work items — the readiness sweep decides when a marked group is evaluated, so `idle`, `max_staleness` and
  `cost_ceiling` still apply. Completing the run means the window is marked, not evaluated
- [x] 4.4 Class-1 lane: `AdasMaterializeClient` with the window, advancing the run cursor on 2xx only;
  `rows_done` accumulates `rows_written`
- [x] 4.5 `backfill/BackfillPageCommitter` — commit group marks as well as chunks, so the run-cursor
  bookkeeping stays in one place rather than being duplicated into a sibling committer

## 5. Verification

- [x] 5.1 Unit tests: `BackfillWindow` validation (inverted, equal, null-safe unbounded); `ScanRequest`'s
  convenience constructor leaves the window absent
- [x] 5.2 Integration test: a bounded row backfill sends the window on **every** scan request (assert on the
  outbound WireMock bodies, not just the first)
- [x] 5.3 Integration test: a group rule's backfill marks `group_state` over the window and enqueues **zero**
  `enrich-group` items
- [x] 5.4 Integration test: a Class-1 rule's backfill materializes with the window, enqueues nothing, and
  advances the run cursor only on 2xx
- [x] 5.5 Integration test: an inverted window is a `400` and creates no run; an absent window is a full
  replay that behaves exactly as before
- [x] 5.6 Integration test: the run reports its window, and a paused-then-resumed run retains it
- [x] 5.7 `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build` passes
- [x] 5.8 `openspec validate add-bounded-window-backfill --strict` passes
