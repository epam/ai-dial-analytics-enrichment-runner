## 1. Honour `ready_when.signal`

- [x] 1.1 `V7__group_signal.sql`: `group_state.signalled BOOLEAN NOT NULL DEFAULT FALSE`, documented as
      ADAS's verdict recorded rather than evaluated, monotone, and never cleared by an evaluation
- [x] 1.2 `ScanResponse.SIGNAL_COLUMN = "__signal"` — the verdict ADAS projects, named where the rows arrive
- [x] 1.3 `GroupMarkBuilder`: read the verdict (boolean, number or its string form; absent is no signal) and
      OR it across a page's rows, so a member arriving after the completion marker cannot un-signal the group
- [x] 1.4 `GroupStateEntity` gains `signalled`; `GroupStateWriter.UPSERT_MARK` ORs it into the stored value,
      matching the `GREATEST` treatment of version and activity
- [x] 1.5 `GroupStateWriter`: readiness becomes a disjunction composed from fragments (`idle`, `staleness`,
      `signalled`) behind a `Readiness` record, so `findDirtyAndReady` and `countCeilingBlocked` cannot drift;
      every predicate still applied before the `LIMIT`
- [x] 1.6 `RuleRouting`: drop the `signal` refusal, add `hasSignal`, narrow the readiness refusal to a rule
      declaring none of the three; document why `idle`'s default still applies to a signal-only rule
- [x] 1.7 `GroupReadinessSweep`: build the `Readiness` from the rule; no other change — a signalled group
      flows through the existing pause, spend, backpressure and ceiling gates unchanged
- [x] 1.8 `GroupStateDto` exposes `signalled`, so an operator can tell a group that fired on its signal from
      one the sweep merely reached early
- [x] 1.9 `GroupStateWriter.evictQuiet` doc: eviction discards `signalled` with the row, deliberately

## 2. Make truncation visible in the prompt

- [x] 2.1 `GroupInputAssembler.AssembledInput` becomes `(members, truncated, omitted)`: `truncated` folds
      ADAS's caps and the runner's own trimming, `omitted` counts whole members the runner dropped (0 when
      ADAS capped — `groups:fetch` has no pagination, so the remainder is unknowable)
- [x] 2.2 `assemble` takes the fetch's `truncated` flag, so the template inputs and the row's provenance
      cannot disagree about whether the judgement was partial
- [x] 2.3 `asInputs()` exposes `members_truncated`, `members_included`, `members_omitted` and
      `members_notice` — the last empty when complete, because the renderer has no conditionals and a
      template must be able to interpolate the caveat unconditionally
- [x] 2.4 `GroupWorkerService`: `truncated` reads from the assembly; the local OR is gone

## 3. Never lose a charge

- [x] 3.1 `GroupStateWriter`: split `STAMP_COMPUTED_AND_CHARGE` into an unconditional `CHARGE_EVALUATION`
      plus the existing version-guarded `STAMP_COMPUTED`, both in one `@Transactional` method; charge first,
      so a crash between them over-charges rather than recording an uncharged evaluation
- [x] 3.2 Return `ChargeOutcome(charged, stamped)` with `superseded()`, replacing the row count no caller read
- [x] 3.3 `RunnerMetrics`: `runner.group.evaluation.superseded`, incremented (and logged once) by
      `UpsertService.stampGroup` and `GroupWorkerService.stampAndCharge` when the stamp was refused

## 4. Tests

- [x] 4.1 `GroupMarkBuilderTest`: the verdict marks the group; the signal survives a later unsignalled member
      in the same page; either serialization is read and absence is no signal
- [x] 4.2 `GroupSweepIntegrationTest`: a signalled group fires before its (48h) idle window; the verdict is
      ignored for a rule declaring no signal; a signalled group at its ceiling stays blocked; a signalled but
      clean group is not enqueued
- [x] 4.3 `RuleRoutingTest`: a signal rule is supported, signal alone is a readiness condition (with the
      default idle backstop), a blank signal is not one, and the no-condition refusal names all three
- [x] 4.4 `RuleSyncIntegrationTest`: a group rule declaring a signal is cached and scheduled
- [x] 4.5 `GroupInputAssemblerTest`: the reserved variables are exposed; a complete input's notice is empty;
      a trimmed input reports its counts; ADAS-side truncation is announced without a count
- [x] 4.6 `GroupWorkerServiceTest`: the fetch's cap is handed to the assembler rather than re-folded; a
      superseded charge is reported
- [x] 4.7 `GroupWorkerIntegrationTest`: the caveat reaches the DIAL request body when trimmed and is absent
      when complete; and the race — stamp at a newer version, then stamp-and-charge at an older one, leaves
      `computed_version` at the newer and `evaluations` at 2

## 5. Specs, docs and verification

- [x] 5.1 Delta specs: `group-enrichment` (readiness declarations, dirty-mark, sweep, bounded input, ceiling),
      `rule-sync` (admission gate), `service-runtime` (schema)
- [x] 5.2 `docs/configuration.md`: readiness is a disjunction and `signal` needs no configuration; the
      default-idle backstop and why; the prompt's truncation variables; the superseded counter
- [x] 5.3 `docs/e2e-runbook.md`: register a rule with a signal, read `__signal` off a scan page, interpolate
      `{{members_notice}}`, and check `group_state.signalled`
- [x] 5.4 `./gradlew clean build` green (checkstyle + unit + integration, Docker required)
