## MODIFIED Requirements

### Requirement: Dirty-mark rides the scan cursor

For a group rule the runner SHALL derive per-group state from the same rule-scoped scan page the row
path uses, so the cost of detecting change is proportional to the number of **new rows** and not to the
number of groups. Each scanned row SHALL contribute its `group_by` value as the group key and the
source's version column — projected under the physical name the rule's `version_column` names — as both
the version and the activity timestamp. The runner SHALL take that name from the rule rather than from
configuration, so it may differ per source.
A group's `group_version` SHALL be the maximum version seen for it, expressed as epoch
milliseconds, and `last_activity_at` SHALL likewise only move forward, so replaying a page or
re-driving an older window SHALL NOT move a group backwards. A row missing its group key SHALL be
skipped, and a row whose version cannot be interpreted SHALL be dead-lettered at stage `input_map`
rather than silently dropped.

For a rule declaring `ready_when.signal`, each row SHALL also contribute **ADAS's computed verdict** of that
predicate, which the scan page carries per row. The runner SHALL record that verdict and SHALL NOT evaluate
the predicate itself: it is governed by ADAS's bounded grammar, and a second implementation would be free to
diverge and would then mark the wrong groups ready rather than failing. A group SHALL be marked signalled if
**any** of its rows was signalled, both within a page and across pages, so a member arriving after the
completion marker SHALL NOT un-signal the group. An absent verdict — every rule that declares no signal —
SHALL NOT signal anything.

#### Scenario: A group's version is the maximum seen

- **GIVEN** a page with three rows for one group key at increasing versions
- **WHEN** the page is committed
- **THEN** the group's `group_version` is the largest of the three, as epoch milliseconds

#### Scenario: Re-driving an older window does not move a group backwards

- **GIVEN** a group whose `group_version` reflects a recent member
- **WHEN** a page carrying only older members of that group is committed
- **THEN** the group's `group_version` and `last_activity_at` are unchanged

#### Scenario: A signalling row marks its group signalled

- **GIVEN** a page in which one row of a group carries a true readiness verdict
- **WHEN** the page is committed
- **THEN** that group's state records it as signalled, and a group with no signalling row does not

#### Scenario: A later member does not un-signal a group

- **GIVEN** a group already recorded as signalled
- **WHEN** a page carrying a newer, non-signalling member of that group is committed
- **THEN** the group's version advances and it remains signalled

#### Scenario: An uninterpretable version dead-letters only its row

- **GIVEN** a page in which one row's version column cannot be interpreted as a timestamp
- **WHEN** the page is committed
- **THEN** that row produces a `dlq_item` at stage `input_map`, the page's other rows still mark their
  groups, and the cursor advances

### Requirement: Readiness sweep enqueues dirty and ready groups

The runner SHALL run a scheduled sweep, independent of the scan, that enqueues one work item per group
which is **dirty**, **ready**, and **within its budget**. A group is dirty when its `group_version` exceeds
its `computed_version`, or it has never been computed. A group is ready when **any** readiness condition its
rule declares holds: `now − last_activity_at` has reached the rule's `ready_when.idle`, `now − computed_at`
has reached its `ready_when.max_staleness`, or the group has been recorded as signalled for a rule declaring
`ready_when.signal`. The signalled branch SHALL carry **no time bound** — a group declared complete is
evaluated on the next pass rather than waiting out an idle window. A group is within budget when its
evaluations for the current UTC day have not reached the rule's `ready_when.cost_ceiling`, which a rule
declaring no ceiling always satisfies.

Readiness thresholds, the declared conditions and the ceiling SHALL be read from the rule as synced, not
stored per group. Every condition SHALL be applied in the selection itself rather than by filtering
afterwards, so an ineligible group cannot consume the pass's budget and crowd out an eligible one. Readiness
SHALL NOT override dirtiness or the budget: a clean group is never enqueued however it signalled, and a
signalled group at its ceiling stays blocked. Each pass SHALL be bounded by a configured batch size so a
large dirty backlog cannot monopolise one pass, and SHALL honour the same rule-lifecycle, pause and spend
gates as the scan. Time SHALL be read through an injected clock.

#### Scenario: An idle group is enqueued once per version

- **GIVEN** a dirty group whose last activity is older than the rule's `idle`
- **WHEN** the sweep runs twice without the group changing
- **THEN** exactly one work item exists for that group and version

#### Scenario: A group still receiving members is not enqueued

- **GIVEN** a dirty group whose last activity is more recent than the rule's `idle`
- **WHEN** the sweep runs
- **THEN** no work item is enqueued for it

#### Scenario: A stale computed group is re-enqueued regardless of activity

- **GIVEN** a group whose `computed_at` is older than the rule's `max_staleness` and which is dirty
- **WHEN** the sweep runs
- **THEN** a work item is enqueued for it even though it has seen no recent activity

#### Scenario: A signalled group is enqueued before its idle window elapses

- **GIVEN** a dirty group recorded as signalled, whose last activity is far more recent than its rule's
  `idle`
- **WHEN** the sweep runs
- **THEN** a work item is enqueued for it

#### Scenario: The verdict is ignored for a rule that declares no signal

- **GIVEN** a dirty group recorded as signalled, whose rule's `ready_when` declares no `signal`
- **WHEN** the sweep runs
- **THEN** no work item is enqueued until a condition the rule does declare holds

#### Scenario: A clean group is never enqueued

- **GIVEN** a group whose `computed_version` equals its `group_version`
- **WHEN** the sweep runs
- **THEN** no work item is enqueued for it, even if it is recorded as signalled

#### Scenario: A pass is bounded by its batch size

- **GIVEN** more dirty and ready groups than the configured per-pass batch size
- **WHEN** the sweep runs once
- **THEN** at most the batch size of work items is enqueued and the remainder is left for the next pass

#### Scenario: A group over its budget does not consume the pass

- **GIVEN** groups at their ceiling ordered ahead of eligible groups, and a per-pass batch size smaller than
  their combined count
- **WHEN** the sweep runs
- **THEN** the eligible groups are enqueued rather than being crowded out by the blocked ones

### Requirement: Supported readiness declarations

The runner SHALL support all three readiness conditions — `ready_when.idle`, `ready_when.max_staleness` and
`ready_when.signal` — and SHALL refuse only a group rule that declares none of them, which would be dirty
forever and never ready. `ready_when.cost_ceiling` SHALL be accepted and enforced as a per-group daily
evaluation budget.

`ready_when.signal` SHALL be honoured by **recording ADAS's computed verdict**, which the scan page carries
per row, and never by evaluating the predicate in the runner. The predicate parses under ADAS's bounded
grammar and is validated and entitlement-checked at registration; re-implementing it here would create a
second grammar free to diverge, whose divergence would mark the wrong groups ready rather than fail.

The configured **default idle window SHALL apply to a rule that declares no `idle`, including one whose only
declared condition is a `signal`.** This is deliberate rather than incidental: eviction never removes a dirty
group, so a group that is never signalled would hold its state row forever and the table would be unbounded
at high group-key cardinality. The default idle window is the backstop that gets such a group evaluated,
clean, and eventually evictable — so a signal makes a group fire *sooner* than that window, not instead of it.

#### Scenario: A rule declaring a signal is executed

- **WHEN** a sync returns a group rule whose `ready_when` includes `signal`
- **THEN** the rule is cached, its recurring task instance is created, and its groups become ready on the
  recorded verdict

#### Scenario: A rule declaring only a signal still has an idle backstop

- **GIVEN** a group rule whose `ready_when` declares `signal` and neither `idle` nor `max_staleness`
- **WHEN** one of its groups is never signalled
- **THEN** the group becomes ready through the configured default idle window, is evaluated, and becomes
  evictable

#### Scenario: A rule declaring no readiness condition is refused

- **WHEN** a sync returns a group rule whose `ready_when` declares none of `signal`, `idle` or
  `max_staleness`
- **THEN** the rule is refused naming all three, is absent from the rule cache, and has no recurring task
  instance

#### Scenario: A rule declaring a cost ceiling is executed and bounded

- **WHEN** a sync returns a group rule whose `ready_when` declares `idle` and a `cost_ceiling`
- **THEN** the rule is cached, its recurring task instance is created, and its groups are bounded by that
  ceiling

### Requirement: Bounded group input

The runner SHALL apply its own token budget to the assembled input on top of ADAS's caps, dropping the
**oldest** members first and truncating per member only when dropping whole members still does not fit —
members arrive in ascending source order, so the newest are the most likely to bear on the label. The row
SHALL be marked truncated when the input was trimmed by **either** the runner's budget or ADAS's caps; the
two SHALL NOT be distinguished on the row, because what a reader needs to know is that the judgement was
partial. A truncated group SHALL still be evaluated: refusing to label it would leave it permanently dirty
and re-swept forever, converting a degraded label into an unbounded retry loop.

**The assembled input SHALL declare its own partiality to the evaluator.** Alongside the member collection,
the template inputs SHALL carry whether the input is truncated, how many members it carries, how many the
runner dropped, and a **pre-composed caveat that is empty when the input is complete**. The caveat is
composed by the runner rather than left to each evaluator because the template renderer performs substitution
only — with no conditionals, an evaluator cannot render a caveat only when one applies, and a template must be
able to interpolate it unconditionally. The dropped-member count SHALL be zero when only ADAS's caps trimmed,
because the group-assembly endpoint has no pagination and the remainder is unknowable; the truncation flag,
not the count, is the fact.

Both facts SHALL be derived from one verdict: ADAS's cap is folded into the assembly, so the caveat the model
sees and the `truncated` provenance the row carries cannot disagree. Without the caveat a model summarizes a
trimmed group as though it were whole, and the row's provenance tells only a later reader — after the
judgement has been formed.

#### Scenario: An over-budget group is trimmed oldest-first and marked

- **GIVEN** a group whose assembled input exceeds the configured token budget
- **WHEN** the work item executes
- **THEN** the oldest members are dropped until it fits, the group is still evaluated, and the result is
  recorded as truncated

#### Scenario: ADAS-reported truncation is propagated

- **GIVEN** a `groups:fetch` response reporting `truncated=true`
- **WHEN** the work item executes
- **THEN** the result is recorded as truncated even though the runner's own budget did not trim it

#### Scenario: A trimmed group's prompt says it is trimmed

- **GIVEN** a group whose input was trimmed and an evaluator whose template interpolates the caveat
- **WHEN** the work item executes
- **THEN** the rendered prompt states that the input is a partial view of the group, and how many members it
  carries

#### Scenario: A complete group's prompt carries no caveat

- **GIVEN** a group whose input was not trimmed and the same evaluator template
- **WHEN** the work item executes
- **THEN** the caveat renders as nothing and the prompt reads as though it were not there

### Requirement: Per-group daily evaluation ceiling

The runner SHALL enforce a group rule's `ready_when.cost_ceiling` as a per-group-key budget of evaluations
per UTC day, tracked on `group_state` as an evaluation count alongside the day it belongs to. A rule that
declares no `cost_ceiling` SHALL be unlimited, so the enforcement is opt-in per rule and declared by the
rule's author in ADAS rather than defaulted by the runner.

The count SHALL reset when the UTC day rolls over, without a scheduled reset: a count recorded against a
previous day is not the current day's count. The runner SHALL read the current day through its injected
clock.

An evaluation SHALL be charged when the model is actually called. A group whose assembly returns no members,
or whose assembly ADAS refuses, SHALL NOT be charged — charging those would let a misconfigured rule exhaust
a ceiling without ever producing a label. A group whose evaluation reached the model but produced an
unusable response SHALL be charged, because the call was spent regardless of the outcome.

The charge SHALL be recorded in the same transaction as the group's computed stamp, so a group cannot be
recorded as evaluated without also being charged for it. The two SHALL NOT share one condition: the stamp
SHALL only ever move `computed_version` forward, while **the charge SHALL apply regardless of whether the
stamp did**. Two versions of one group can be in flight at once — the sweep enqueues a new version while the
previous item is still running — and when they complete out of order the older evaluation's stamp is
correctly refused while its model call was really spent. Refusing the charge with the stamp would under-count
exactly the double spend the ceiling exists to bound. An evaluation whose stamp was refused SHALL be
**observable**, since the charge is otherwise silent and the refusal matches no rows: it is the direct signal
that a group's evaluations are taking longer than the sweep interval.

#### Scenario: A group at its ceiling is not enqueued

- **GIVEN** a dirty and ready group whose evaluations today have reached its rule's `cost_ceiling`
- **WHEN** the readiness sweep runs
- **THEN** no work item is enqueued for that group

#### Scenario: A group under its ceiling is enqueued normally

- **GIVEN** a dirty and ready group whose evaluations today are below its rule's `cost_ceiling`
- **WHEN** the readiness sweep runs
- **THEN** a work item is enqueued for it

#### Scenario: A rule without a ceiling is unlimited

- **GIVEN** a group rule whose `ready_when` declares no `cost_ceiling`
- **WHEN** a group is evaluated many times in one day and the sweep runs again
- **THEN** the group is still enqueued

#### Scenario: The count resets when the day rolls over

- **GIVEN** a group that reached its ceiling yesterday and has not been evaluated today
- **WHEN** the readiness sweep runs
- **THEN** the group is enqueued, and its count restarts at this evaluation rather than continuing

#### Scenario: A stale group at its ceiling is still blocked

- **GIVEN** a group that is dirty, past its `max_staleness`, and at its ceiling for today
- **WHEN** the readiness sweep runs
- **THEN** no work item is enqueued — staleness does not override the budget

#### Scenario: A signalled group at its ceiling is still blocked

- **GIVEN** a group that is dirty, recorded as signalled, and at its ceiling for today
- **WHEN** the readiness sweep runs
- **THEN** no work item is enqueued — a signal means "evaluate now", not "evaluate outside the budget"

#### Scenario: An evaluation superseded by a newer one is still charged

- **GIVEN** two evaluations of one group in flight at different versions
- **WHEN** the newer one completes first and stamps the group, and the older one then completes
- **THEN** the group's `computed_version` remains the newer one, both evaluations are charged, and the
  superseded evaluation is reported
