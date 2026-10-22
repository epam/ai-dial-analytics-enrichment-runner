## Context

Three gaps, one lane. They are grouped because each is a completion of the group capability rather than a new
mechanism, and because two of them touch the same file (`GroupStateWriter`) and the same spec requirements.

What already exists to build on: `group_state` is per group, upserted with `GREATEST` so every marked fact is
monotone; the sweep composes its predicate from fragments and applies all of them before the `LIMIT`; the
assembly already computes whether it trimmed; the stamp already carries a version guard; and `Clock` plus
`SettableClock` make every time threshold testable.

## Decisions

### D-1: the runner records the verdict, it never evaluates the predicate

`signalled` is set from ADAS's `__signal` column. The predicate is governed by ADAS's bounded Calcite
grammar, validated at registration and evaluated inside the scan query.

*Rejected: evaluate `ready_when.signal` runner-side over projected columns* — the original plan's
sketch, and the reason the feature was refused instead. It needs a second implementation of a SQL predicate
grammar (functions, null semantics, coercions). Divergence there does not fail loudly; it marks the wrong
groups ready, which shows up as labels computed too early and is nearly undiagnosable.

### D-2: `signalled` is a monotone boolean on the group, not a version or a timestamp

The dirty-mark ORs the incoming verdict into the stored one, so replaying a page or re-driving an older window
cannot un-signal a group — the same property `GREATEST` gives `group_version` and `last_activity_at`. Within
one page the merge carries the flag across rows rather than taking the newest row's value: a member arriving
after the completion marker must not un-signal the group.

An evaluation does **not** clear it. A group declared complete stays complete, so a late member makes it dirty
again and it is re-evaluated on the next pass rather than after another idle window — which is what a caller
declaring "this conversation ended" is asking for.

*Rejected: `signalled_version`* (the version of the signalling row). It would allow "signalled at or above the
current version" semantics, but nothing needs that: dirtiness already decides *whether* to evaluate, and the
signal only decides *when*. A boolean says exactly what the sweep asks.

### D-3: readiness is the disjunction of the declared branches, and `idle` is always one of them

The sweep's predicate becomes `(idle OR [staleness] OR [signalled])`, composed from fragments. `idle` is always
present, falling back to `GROUP_DEFAULT_IDLE` — including for a rule whose only declared condition is a
signal.

That last part is a deliberate deviation from "honour exactly what the rule declares". Eviction only removes
**clean** groups, so a group that is never signalled would stay dirty and hold its `group_state` row forever;
at conversation-key cardinality that is an unbounded table. The default idle window is the backstop that gets
such a group evaluated, clean, and eventually evicted. The cost is that a signal-only rule also fires after
`GROUP_DEFAULT_IDLE` of quiet — which is the pre-existing behaviour of every rule that declares no `idle`, and
is visible in configuration rather than hidden.

*Rejected: honour signal-only exactly and add a give-up eviction for very old dirty groups* — a second
eviction policy, with its own threshold, to bound a table the existing one already bounds.

### D-4: the caveat is a pre-composed variable, empty when there is nothing to say

`TemplateRenderer` substitutes and nothing more — no conditionals. So an evaluator cannot render "some
messages were omitted" only when some were. Composing the sentence in the assembler and making it the empty
string otherwise is what lets a template carry `{{members_notice}}` unconditionally and stay correct in both
cases. `members_truncated` / `members_included` / `members_omitted` are exposed alongside it for a template
that wants to phrase its own.

*Rejected: a boolean alone.* `{{members_truncated}}` renders as `false`, and a prompt saying "Truncated:
false" spends tokens teaching the model about the runner's budget.

*Rejected: conditionals in the renderer.* A template language is a much larger commitment than a variable,
and it would be introduced here to solve one case.

`members_omitted` is `0` when only ADAS's caps trimmed: `groups:fetch` has no pagination, so the remainder is
genuinely unknowable. The notice degrades to "earlier items were omitted" rather than claiming a count it does
not have.

Name collision is not a risk: the rule's `input_bindings` bind variables *inside* each member object, so the
top-level namespace is entirely runner-owned.

### D-5: ADAS's cap is folded inside the assembly, not by the worker afterwards

`assemble` takes the fetch's `truncated` flag and returns one verdict. Previously the worker OR-ed the two,
which meant the template inputs (built from the assembly alone) and the row's provenance (built from the OR)
could disagree about whether the judgement was partial — exactly the divergence the notice exists to prevent.

### D-6: the charge is unconditional and the stamp stays guarded, in one transaction

Two statements, `@Transactional`: charge first, then the version-guarded stamp.

- **Why not one statement:** expressing "guard three columns, not the other two" needs the guard repeated in a
  `CASE` per column — eleven positional parameters whose order is the only thing keeping it correct. Two
  statements reuse `stampComputed` verbatim.
- **Why charge first:** if the process dies between them the group is charged but left dirty, so it is
  re-evaluated and over-charged. The reverse order could stamp a group as evaluated without charging it, which
  is the invariant the spec names. Failing toward over-charging is failing toward protecting spend.
- **Why not a `computed_version` re-check in the worker instead:** it moves the decision to a second place
  where it can be enforced inconsistently, and it would drop the charge for a call that was still spent.
- *Rejected: an enqueue lease so a second version is never enqueued while one is in flight.* That prevents the
  double model call rather than accounting for it — better, and a larger change: it needs a lease with an
  expiry (a crashed worker must not block its group forever) and turns the sweep's stateless query into a
  stateful one. The new counter is what would justify it.

### D-7: a superseded charge is reported, not silent

The writer returns `ChargeOutcome(charged, stamped)`, and both call sites count
`runner.group.evaluation.superseded` and log once. The condition is otherwise invisible — the charge succeeds
quietly and the stamp simply matches nothing — yet it is the direct signal that a group's evaluations take
longer than `GROUP_SWEEP_INTERVAL`, which is a configuration problem an operator can act on.

## Risks / Trade-offs

- **A signal rule deployed before ADAS's change** → its pages carry no `__signal`, so its groups are never
  marked signalled and fire on the idle backstop instead. Degraded, not broken, and it self-corrects when
  ADAS is deployed. Called out as an ordering requirement in the proposal.

- **Eviction discards `signalled`** → a group re-created after quiet longer than its whole readiness window
  plus the margin starts un-signalled and fires by idle. Deliberate, and identical to how the evaluation count
  is treated: such a group is a new conversation in all but identity.

- **The notice spends prompt tokens** → only when the input was trimmed, which is exactly when the group's
  content is already at the budget. A sentence against a 60k-token budget is not the constraint.

- **A rule whose template omits `{{members_notice}}` gains nothing** → true; the variables are available, not
  imposed. Rendering them into a prompt the evaluator author did not write is not something the runner can do
  safely, since where a caveat belongs in a prompt is part of the prompt's design.

- **Over-charging on a crash between the charge and the stamp** → the group is re-evaluated anyway (it stayed
  dirty), so the extra charge corresponds to a real extra call. The count is of calls spent, and it stays
  correct.
