## Why

The group lane shipped with three gaps its own specs and designs record. Each is small; each leaves a
promise the capability makes only partly kept.

- **`ready_when.signal` is refused at sync.** ADAS accepts a group rule whose readiness is a signal —
  validating the predicate, entitlement-checking its columns, storing it — and the runner then refuses to
  execute it, so a registered rule silently never runs. The reason was on ADAS's side of the wire: the scan
  projected the *raw columns* the predicate reads rather than its result, so honouring `signal` would have
  meant a second implementation of ADAS's bounded grammar, free to diverge and to mark the wrong groups
  ready. ADAS's paired change now computes the predicate inside the scan and projects the verdict.
- **A trimmed group's prompt does not say it is trimmed.** The assembly drops the oldest members to fit the
  token budget (and ADAS's own caps may have trimmed before that), and records the fact on the enrichment
  row and in `group_state`. But the *model* is not told: it is handed a suffix of a conversation and asked
  about "the conversation". The provenance tells a later reader that the judgement was partial; nothing
  tells the party forming the judgement.
- **A charge can be lost.** The cost ceiling's stamp-and-charge is one statement guarded by
  `computed_version < :version`. Two versions of one group can be in flight at once — the sweep enqueues a
  new version while the previous item is still running — and if they complete out of order the older one's
  statement matches no rows. Its stamp *should* be refused; its charge should not be, because the model call
  was really spent. The ceiling therefore under-counts exactly the double spend it exists to bound.

## What Changes

- **`ready_when.signal` becomes executable.** The admission gate no longer refuses it; it now refuses only a
  group rule declaring *no* readiness condition at all. `group_state` gains `signalled` (Flyway
  `V7__group_signal.sql`), set from ADAS's `__signal` verdict by the dirty-mark and OR-ed in monotonically,
  and the readiness sweep gains a `signalled` branch with no time bound — a group declared complete does not
  wait out its idle window. The runner **never parses the predicate**; it records a verdict.
- **`GROUP_DEFAULT_IDLE` remains a backstop for a signal-only rule.** Eviction never removes a dirty group,
  so a group that is never signalled would hold its state row forever. The default idle window gets it
  evaluated, clean, and eventually evicted; a signal only makes a group fire *sooner*.
- **The assembly declares its own partiality to the evaluator.** Alongside `members`, the template inputs
  gain `members_truncated`, `members_included`, `members_omitted` and `members_notice` — the last a
  ready-made sentence that is **empty when the input is complete**, because the renderer has no conditionals
  and a template must be able to include the caveat unconditionally. ADAS's cap is folded into the
  assembly's own verdict, so the prompt's caveat and the row's `truncated` provenance cannot disagree.
- **The charge is unconditional; the stamp stays monotone.** They become two statements in one transaction:
  charge first, then the version-guarded stamp. An evaluation superseded by a newer one is still charged for
  the call it spent, and the fact is reported on a new counter rather than being silently swallowed.

Out of scope: aggregating a signal per group inside ADAS (a page is rows; folding is the runner's job);
resetting `signalled` on evaluation (a group declared complete stays complete); making the notice's wording
configurable; and preventing the double model call itself, which would need an enqueue lease on
`group_state` — a larger change than the accounting gap, and worth proposing separately if the new counter
shows the double spend is material.

## Capabilities

### Modified Capabilities

- `group-enrichment`: "Supported readiness declarations" (all three conditions supported, `signal` recorded
  from ADAS's verdict, the default-idle backstop and why); "Dirty-mark rides the scan cursor" (a row's
  verdict marks the group, monotonically); "Readiness sweep enqueues dirty and ready groups" (the third
  branch); "Bounded group input" (the input declares its partiality to the evaluator); "Per-group daily
  evaluation ceiling" (the charge survives an out-of-order completion, and the case is observable).
- `rule-sync`: "Rule admission gate" — the readiness refusal narrows to a rule declaring no condition.
- `service-runtime`: "PostgreSQL runtime-state schema" — `group_state` carries the signal.

## Impact

**New code:** `V7__group_signal.sql`; `GroupStateWriter.Readiness` and `GroupStateWriter.ChargeOutcome`;
`ScanResponse.SIGNAL_COLUMN`.

**Modified code:** `GroupMarkBuilder` (reads the verdict, OR-merges it), `GroupStateEntity` (+`signalled`),
`GroupStateWriter` (upsert, readiness fragments as a disjunction, charge split from stamp),
`RuleRouting` (`hasSignal`, refusal narrowed), `GroupReadinessSweep` (builds `Readiness`),
`GroupInputAssembler` (truncation vars, ADAS's cap folded in), `GroupWorkerService` and `UpsertService`
(report a superseded charge), `RunnerMetrics` (+`runner.group.evaluation.superseded`), `GroupStateDto`
(+`signalled`).

**Migration:** additive — one nullable-free boolean with a `FALSE` default. No backfill: an existing group
starts un-signalled, which for a rule that declares no signal is its only possible state.

**ADAS contract:** consumes `__signal` from the existing scan endpoint. **Requires ADAS's
`add-scan-readiness-signal` to be deployed first**; until then a signal rule's pages carry no verdict, so its
groups fire on the idle backstop rather than on the signal — degraded, not broken.

**Configuration:** none added. The notice's wording, the reserved variable names and the charge fix are all
behaviour, not knobs.
