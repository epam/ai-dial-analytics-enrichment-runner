## MODIFIED Requirements

### Requirement: Per-group state owned by the runner

The runner SHALL maintain one `group_state` row per active group of a group pipeline, keyed
`(pipeline_name, group_key)`, holding the group's `group_version`, `last_activity_at`, and — once evaluated —
`computed_version`, `computed_at` and `computed_truncated`. This state SHALL be what the readiness sweep
reads to decide dirtiness, and SHALL remain authoritative for a group's progress independently of the
lineage an enrichment row records. The enrichment row's `group_version` and `truncated` provenance
duplicate two of these facts deliberately: the row's copy serves downstream readers, while this state
serves the sweep, and the sweep SHALL NOT depend on reading the enrichment table.

#### Scenario: A newly seen group gets a state row

- **GIVEN** a group pipeline and a scanned page containing rows for a group key not yet in `group_state`
- **WHEN** the page is committed
- **THEN** a `group_state` row exists for that `(pipeline_name, group_key)` with a `group_version`, a
  `last_activity_at`, and a null `computed_version`

### Requirement: Dirty-mark rides the scan cursor

For a group pipeline the runner SHALL derive per-group state from the same pipeline-scoped scan page the row
path uses, so the cost of detecting change is proportional to the number of **new rows** and not to the
number of groups. Each scanned row SHALL contribute its `group_by` value as the group key and the
source's version column — projected under the physical name the pipeline's `version_column` names — as both
the version and the activity timestamp. The runner SHALL take that name from the pipeline rather than from
configuration, so it may differ per source.
A group's `group_version` SHALL be the maximum version seen for it, expressed as epoch
milliseconds, and `last_activity_at` SHALL likewise only move forward, so replaying a page or
re-driving an older window SHALL NOT move a group backwards. A row missing its group key SHALL be
skipped, and a row whose version cannot be interpreted SHALL be dead-lettered at stage `input_map`
rather than silently dropped.

For a pipeline declaring `ready_when.signal`, each row SHALL also contribute **ADAS's computed verdict** of that
predicate, which the scan page carries per row. The runner SHALL record that verdict and SHALL NOT evaluate
the predicate itself: it is governed by ADAS's bounded grammar, and a second implementation would be free to
diverge and would then mark the wrong groups ready rather than failing. A group SHALL be marked signalled if
**any** of its rows was signalled, both within a page and across pages, so a member arriving after the
completion marker SHALL NOT un-signal the group. An absent verdict — every pipeline that declares no signal —
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

### Requirement: Atomic dirty-mark and cursor advance

The runner SHALL commit a group page's state upserts, its dead-letter items, and the advance of
`pipeline_cursor` to the scan's `next_cursor` in a single transaction — all committing together or none.
Because the upsert takes the maximum rather than the latest value, repeating a committed page SHALL
leave the state unchanged and SHALL NOT create duplicate rows.

#### Scenario: State and cursor commit together

- **WHEN** a group page commits successfully
- **THEN** the page's group state is persisted and the cursor is advanced to `next_cursor`

#### Scenario: A failed commit rolls back both

- **WHEN** the commit fails
- **THEN** neither the group state nor the cursor advance is persisted

#### Scenario: A repeated tick creates no duplicate state

- **GIVEN** a page already committed for a cursor-before
- **WHEN** the same range is scanned and committed again
- **THEN** the affected groups' state is identical and no additional `group_state` rows exist

### Requirement: Readiness sweep enqueues dirty and ready groups

The runner SHALL run a scheduled sweep, independent of the scan, that enqueues one work item per group
which is **dirty**, **ready**, and **within its budget**. A group is dirty when its `group_version` exceeds
its `computed_version`, or it has never been computed. A group is ready when **any** readiness condition its
pipeline declares holds: `now − last_activity_at` has reached the pipeline's `ready_when.idle`, `now − computed_at`
has reached its `ready_when.max_staleness`, or the group has been recorded as signalled for a pipeline declaring
`ready_when.signal`. The signalled branch SHALL carry **no time bound** — a group declared complete is
evaluated on the next pass rather than waiting out an idle window. A group is within budget when its
evaluations for the current UTC day have not reached the pipeline's `ready_when.cost_ceiling`, which a pipeline
declaring no ceiling always satisfies.

Readiness thresholds, the declared conditions and the ceiling SHALL be read from the pipeline as synced, not
stored per group. Every condition SHALL be applied in the selection itself rather than by filtering
afterwards, so an ineligible group cannot consume the pass's budget and crowd out an eligible one. Readiness
SHALL NOT override dirtiness or the budget: a clean group is never enqueued however it signalled, and a
signalled group at its ceiling stays blocked. Each pass SHALL be bounded by a configured batch size so a
large dirty backlog cannot monopolise one pass, and SHALL honour the same pipeline-lifecycle, pause and spend
gates as the scan. Time SHALL be read through an injected clock.

#### Scenario: An idle group is enqueued once per version

- **GIVEN** a dirty group whose last activity is older than the pipeline's `idle`
- **WHEN** the sweep runs twice without the group changing
- **THEN** exactly one work item exists for that group and version

#### Scenario: A group still receiving members is not enqueued

- **GIVEN** a dirty group whose last activity is more recent than the pipeline's `idle`
- **WHEN** the sweep runs
- **THEN** no work item is enqueued for it

#### Scenario: A stale computed group is re-enqueued regardless of activity

- **GIVEN** a group whose `computed_at` is older than the pipeline's `max_staleness` and which is dirty
- **WHEN** the sweep runs
- **THEN** a work item is enqueued for it even though it has seen no recent activity

#### Scenario: A signalled group is enqueued before its idle window elapses

- **GIVEN** a dirty group recorded as signalled, whose last activity is far more recent than its pipeline's
  `idle`
- **WHEN** the sweep runs
- **THEN** a work item is enqueued for it

#### Scenario: The verdict is ignored for a pipeline that declares no signal

- **GIVEN** a dirty group recorded as signalled, whose pipeline's `ready_when` declares no `signal`
- **WHEN** the sweep runs
- **THEN** no work item is enqueued until a condition the pipeline does declare holds

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

### Requirement: Deterministic group work-item identity

A group work item's instance id SHALL be derived from the pipeline name, evaluator version, group key and
`group_version`, so re-sweeping an unchanged group produces the same id and is suppressed, while a
changed group yields a higher version and therefore a new item. The id SHALL retain the pipeline-name prefix
the backpressure count relies on, so a group pipeline's in-flight depth is countable without deserializing
payloads — and the readiness sweep SHALL consult that count, skipping a pipeline whose queued work already
exceeds `scan.max-inflight`. Skipping a pass costs nothing: the groups remain dirty and the next pass
selects them.

The prefix is delimited by `#`, and the runner SHALL depend on ADAS refusing that character in a pipeline
name at registration (`^[a-z][a-z0-9_-]{0,63}$`) rather than re-validating it here. Without that exclusion the
pipelines `a#b` and `a` would share the prefix `a#`, and one pipeline's in-flight count — and therefore its
backpressure and this sweep's suppression — would silently include another's work. A `_` in a name is safe by
a different mechanism: the prefix scan escapes LIKE wildcards, so it cannot broaden a match.

#### Scenario: An unchanged group is not enqueued twice

- **WHEN** the same pipeline, evaluator version, group key and group version are enqueued again
- **THEN** the deterministic id dedups and no additional work item is created

#### Scenario: A changed group re-fires

- **GIVEN** a group already enqueued at one version
- **WHEN** a new member raises its `group_version` and the sweep runs
- **THEN** a distinct work item is enqueued for the new version

### Requirement: A group with work already in flight is not re-evaluated

The readiness sweep SHALL NOT enqueue a group for which a compute **or** a write work item already exists for
that exact pipeline, evaluator version, group key and `group_version`, and SHALL report how many groups a pass
held back for that reason.

A group is stamped clean by its **write**, not by its computation, so from the moment a compute item finishes
and is deleted until its write lands, the group still selects as dirty and `scheduleIfNotExists` has nothing
left to collide with. Re-evaluating it there produces nothing: the second result's write derives the same
deterministic id as the first, still-queued one, and is discarded — the compute-to-write window is not a
second chance to evaluate, it is a paid model call whose output is thrown away. The window is fully covered
because the write is enqueued inside the compute's own transaction, so one of the two items is always
present. Suppression SHALL be keyed by version, not by group: a group whose `group_version` advanced while a
write for the older version is still pending carries members that pending label never saw and SHALL be
enqueued. The check SHALL cost one query per pipeline per pass, over the same tasks and instance-id prefix the
backpressure count uses, and is therefore bounded by the same `scan.max-inflight` that already stops the
sweep. A dead-lettered or archived write removes the item, so a group whose write never landed is enqueued
again on the next pass — the suppression delays a re-evaluation, it never cancels one.

#### Scenario: A group whose write is still queued is not evaluated again

- **GIVEN** a dirty and ready group whose compute has completed and whose write item is still pending
- **WHEN** the sweep runs
- **THEN** no work item is enqueued for it and the pass reports it as held back

#### Scenario: A group is enqueued once its write is gone and it is still dirty

- **GIVEN** the same group after its write item has left the queue without stamping it clean
- **WHEN** the sweep next runs
- **THEN** a work item is enqueued for it

#### Scenario: A newer version is enqueued while an older version's write is pending

- **GIVEN** a group whose `group_version` advanced while a write for the previous version is still pending
- **WHEN** the sweep runs
- **THEN** a work item is enqueued for the new version and nothing is held back

#### Scenario: Groups held back are reported for scraping

- **WHEN** a sweep pass holds groups back because work for that version is already queued
- **THEN** the number held back is published for scraping, separately from the ceiling count

### Requirement: Group-key sampling

For a group pipeline the runner SHALL apply the pipeline's `sampling` to the **group key** rather than to each
row, so the decision is deterministic and monotone at the grain the work is keyed by. A sampled-out
group SHALL NOT be marked and SHALL therefore hold no state, while the cursor still advances past its
rows.

#### Scenario: A sampled-out group holds no state

- **GIVEN** a group pipeline with a sampling fraction that excludes a given group key
- **WHEN** a page containing only that group's rows is committed
- **THEN** no `group_state` row exists for it and the cursor has advanced

### Requirement: Bounded group state through eviction

The runner SHALL run a scheduled eviction that deletes `group_state` rows for groups quiet longer than
the pipeline's `max(idle, max_staleness)` plus a configured margin, so the table stays bounded under
high group-key cardinality. A dirty group SHALL NOT be evicted, since that would discard pending work.
Evicting a clean group SHALL be safe: a later member re-creates the row with a `group_version` above the
discarded `computed_version`, so the group re-fires as a change.

#### Scenario: A long-quiet computed group is evicted

- **GIVEN** a clean group whose last activity is older than its pipeline's retention window plus the margin
- **WHEN** the eviction runs
- **THEN** its `group_state` row is deleted

#### Scenario: A dirty group is never evicted

- **GIVEN** a dirty group whose last activity is older than the retention window
- **WHEN** the eviction runs
- **THEN** its `group_state` row is retained

### Requirement: Supported readiness declarations

The runner SHALL support all three readiness conditions — `ready_when.idle`, `ready_when.max_staleness` and
`ready_when.signal` — and SHALL refuse only a group pipeline that declares none of them, which would be dirty
forever and never ready. `ready_when.cost_ceiling` SHALL be accepted and enforced as a per-group daily
evaluation budget.

`ready_when.signal` SHALL be honoured by **recording ADAS's computed verdict**, which the scan page carries
per row, and never by evaluating the predicate in the runner. The predicate parses under ADAS's bounded
grammar and is validated and entitlement-checked at registration; re-implementing it here would create a
second grammar free to diverge, whose divergence would mark the wrong groups ready rather than fail.

The configured **default idle window SHALL apply to a pipeline that declares no `idle`, including one whose only
declared condition is a `signal`.** This is deliberate rather than incidental: eviction never removes a dirty
group, so a group that is never signalled would hold its state row forever and the table would be unbounded
at high group-key cardinality. The default idle window is the backstop that gets such a group evaluated,
clean, and eventually evictable — so a signal makes a group fire *sooner* than that window, not instead of it.

#### Scenario: A pipeline declaring a signal is executed

- **WHEN** a sync returns a group pipeline whose `ready_when` includes `signal`
- **THEN** the pipeline is cached, its recurring task instance is created, and its groups become ready on the
  recorded verdict

#### Scenario: A pipeline declaring only a signal still has an idle backstop

- **GIVEN** a group pipeline whose `ready_when` declares `signal` and neither `idle` nor `max_staleness`
- **WHEN** one of its groups is never signalled
- **THEN** the group becomes ready through the configured default idle window, is evaluated, and becomes
  evictable

#### Scenario: A pipeline declaring no readiness condition is refused

- **WHEN** a sync returns a group pipeline whose `ready_when` declares none of `signal`, `idle` or
  `max_staleness`
- **THEN** the pipeline is refused naming all three, is absent from the pipeline cache, and has no recurring task
  instance

#### Scenario: A pipeline declaring a cost ceiling is executed and bounded

- **WHEN** a sync returns a group pipeline whose `ready_when` declares `idle` and a `cost_ceiling`
- **THEN** the pipeline is cached, its recurring task instance is created, and its groups are bounded by that
  ceiling

### Requirement: Group input assembly

When a group work item executes, the runner SHALL fetch the group's members from ADAS
`POST /v1/pipelines/{name}/group-rows`, sending only the group key and a row limit — ADAS resolves the source,
applies the membership filter, orders members by the source's total order, and enforces its row and
response-byte caps. The runner SHALL then apply the pipeline's `input_bindings` to **each** member row,
yielding one object per member, and SHALL expose that collection to the evaluator's request template as a
single variable rendered as a JSON array. Member field correlation SHALL be preserved, so a member's
bound fields stay together rather than being split across parallel per-variable collections. The runner
SHALL NOT name a table, a column set or an ordering in the request.

#### Scenario: Members become one object each under a collection variable

- **GIVEN** a group pipeline binding two input variables, and a group of three members
- **WHEN** the work item executes
- **THEN** the rendered prompt interpolates a JSON array of three objects, each carrying that member's two
  bound fields

#### Scenario: One prompt, one model call per group

- **WHEN** a group of many members is evaluated
- **THEN** exactly one prompt is rendered and exactly one model call is made for the whole group

### Requirement: Bounded group input

The runner SHALL apply its own token budget to the assembled input on top of ADAS's caps, dropping the
**oldest** members first and truncating per member only when dropping whole members still does not fit —
members arrive in ascending source order, so the newest are the most likely to bear on the label. A per-member
cut SHALL keep the field's **tail**, SHALL apply to a field of any type, and SHALL be marked in the value
itself. The direction is the same decision as dropping the oldest members, for the same reason: the fields a
per-member cut reaches are payloads such as `assembled_response`, whose newest content is at the end, so
keeping the head would answer a question about how a conversation ended from how it opened. A bound only a
string can trip is not a bound — a binding that yields a list or an object would pass through at full size —
and an unmarked fragment reads to the model as a complete field. A list SHALL keep its shape when it is cut —
its **leading** elements go and the marker takes their place as an element — because a silently shortened array
is the same complete-looking fragment in a different costume. The allowance SHALL be shared so that a field
which fits is never charged room it cannot use, leaving it for the field that needs it; a member whose fields
all fit once the allowance is shared SHALL NOT be reported cut or truncated, because the estimate rounds up and
being over it is not proof that anything had to go. The row
SHALL be marked truncated when the input was trimmed by **either** the runner's budget or ADAS's caps; the
two SHALL NOT be distinguished on the row, because what a reader needs to know is that the judgement was
partial. A truncated group SHALL still be evaluated: refusing to label it would leave it permanently dirty
and re-swept forever, converting a degraded label into an unbounded retry loop.

`truncated` therefore means exactly **"the label saw part of the group"**, for any reason — and ADAS's caps
include the pipeline's own `member_select` policy, which narrows a group whenever it returns fewer members than
the group holds. A pipeline declaring `member_select.limit: 1` reports `truncated` on **every** multi-member
group by design, so the flag is **not** a quality signal on such a pipeline: reading a high truncated rate as
degraded input mistakes the pipeline's intended shape for a budget problem. What the flag cannot express — and
the only budget signal there is — is whether a member that *is* present had its own content cut, so the
runner SHALL count that case separately (see *Content cut to fit the budget is observable*).

**The assembled input SHALL declare its own partiality to the evaluator.** Alongside the member collection,
the template inputs SHALL carry whether the input is truncated, how many members it carries, how many the
runner dropped, and a **pre-composed caveat that is empty when the input is complete**. The caveat SHALL
disclose a member that is present but whose own content was cut, and SHALL NOT report omitted earlier items
when none were dropped: a member list of one cut member is partial in a way no member count expresses, and a
count-shaped caveat would both present a fragment as a whole item and claim an omission that never happened. The caveat is
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

#### Scenario: An oversized member keeps the most recent end of its payload

- **GIVEN** a group whose single newest member alone exceeds the token budget
- **WHEN** the work item executes
- **THEN** the oversized field keeps its final characters, opens with a marker naming the cut, and the group is
  still evaluated and recorded as truncated

#### Scenario: An oversized list keeps its newest elements, marked

- **GIVEN** a member field bound to a list too long for the budget
- **WHEN** the work item executes
- **THEN** the field is still a list, its leading elements are gone, and its first element is the marker naming
  the cut

#### Scenario: A member the shared allowance accommodates is not reported cut

- **GIVEN** a single member the token estimate puts over the budget but whose fields all fit once the allowance
  is shared between them
- **WHEN** the work item executes
- **THEN** nothing is removed from the member and the input is reported neither cut nor truncated

#### Scenario: A cut member's caveat claims no omission it cannot support

- **GIVEN** a group of one member, cut to fit rather than dropped
- **WHEN** the prompt is rendered
- **THEN** the caveat states that the item shown is itself cut, and does not report earlier items as omitted

#### Scenario: ADAS-reported truncation is propagated

- **GIVEN** a `group-rows` response reporting `truncated=true`
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

### Requirement: One enrichment row per group with group provenance

The runner SHALL write exactly one enrichment row per evaluated group, whose key value is the group key
and whose key **column** is the target enrichment's `grain_key`, carrying the mapped output columns and
the provenance columns `evaluator_version`, `model`, `enriched_at`, `group_version` and `truncated`.

The key column SHALL be `grain_key` and SHALL NOT be the pipeline's `group_by`. The two coincide only while
`group_by` names a bare source column; ADAS validates `group_by` to *resolve* to the grain, and when the
key is reachable only through an enrichment of the read source `group_by` carries the qualified flat name
`<enrichment>.<column>` that the scan projects it under. No enrichment table has a column of that name, so
writing it is rejected by ADAS with HTTP 422 `write_column_not_allowed` — a permanent rejection, which
dead-letters and stamps clean **every** group of such a pipeline. The runner therefore SHALL read a scanned
row's key under `group_by` and SHALL write it back under `grain_key`. `group_version` SHALL be the version the input was judged
at and `truncated` whether that input was trimmed, so a partial label is distinguishable from a complete
one downstream and a label's input version is identifiable after the fact. Provenance columns the target
enrichment does not physically carry SHALL be tolerated (ADAS drops them silently), so the write SHALL NOT
be conditioned on the target's provenance columns.

#### Scenario: A group's label is written keyed by its group key

- **WHEN** a group is evaluated successfully
- **THEN** one row is written whose grain value is the group key, carrying the mapped outputs and the five
  provenance columns

#### Scenario: An enrichment-sourced group key is written under the plain grain key

- **GIVEN** a pipeline whose `group_by` is the qualified flat name of an enrichment column (for example
  `usage_client_identity.client_session_id`) and whose target enrichment's `grain_key` is `client_session_id`
- **WHEN** a group is evaluated successfully
- **THEN** the written row carries the group key under `client_session_id` and does not name the qualified
  spelling, and ADAS accepts the write

#### Scenario: A target without group provenance columns still enriches

- **GIVEN** a target enrichment carrying neither `group_version` nor `truncated`
- **WHEN** a group is evaluated successfully
- **THEN** the write succeeds with those columns dropped by ADAS and no dead-letter is produced

### Requirement: Group failure isolation at group granularity

Because a group is 1:0..1 there SHALL be no partial-row outcome within a group: the group either produces a
label or fails as a whole. A retryable `group-rows` or DIAL failure (5xx, timeout, 429, or rate-limiter
exhaustion) SHALL fail the work item and be retried with the group's state untouched, so the group stays
dirty. A deterministic assembly or output-mapping failure SHALL dead-letter the group at stage `group_map`,
and a non-retryable `group-rows` rejection at stage `group_fetch`. Retry exhaustion SHALL archive the
item for requeue. The stale-evaluator and pipeline-lifecycle guards SHALL apply as they do to a row chunk.

#### Scenario: A transient fetch failure retries without touching state

- **GIVEN** a group work item whose `group-rows` returns a 5xx
- **WHEN** the item fails
- **THEN** it is retried later, no dead-letter is produced, and the group's `computed_version` is unchanged

#### Scenario: A mapping failure dead-letters the group

- **GIVEN** a group whose model response cannot be output-mapped
- **WHEN** the item executes
- **THEN** a `dlq_item` at stage `group_map` carrying the group key is written

#### Scenario: A stale group item is dropped without calling the model

- **GIVEN** a group work item whose evaluator version no longer matches the cached pipeline
- **WHEN** the item executes
- **THEN** no model call is made and nothing is written

### Requirement: Per-group daily evaluation ceiling

The runner SHALL enforce a group pipeline's `ready_when.cost_ceiling` as a per-group-key budget of evaluations
per UTC day, tracked on `group_state` as an evaluation count alongside the day it belongs to. A pipeline that
declares no `cost_ceiling` SHALL be unlimited, so the enforcement is opt-in per pipeline and declared by the
pipeline's author in ADAS rather than defaulted by the runner.

The count SHALL reset when the UTC day rolls over, without a scheduled reset: a count recorded against a
previous day is not the current day's count. The runner SHALL read the current day through its injected
clock.

An evaluation SHALL be charged when the model is actually called. A group whose assembly returns no members,
or whose assembly ADAS refuses, SHALL NOT be charged — charging those would let a misconfigured pipeline exhaust
a ceiling without ever producing a label. A group whose evaluation reached the model but produced an
unusable response SHALL be charged, because the call was spent regardless of the outcome.

The charge SHALL be recorded in the same transaction as the group's computed stamp, so a group cannot be
recorded as evaluated without also being charged for it. The two SHALL NOT share one condition: the stamp
SHALL never move `computed_version` **backwards**, while **the charge SHALL apply regardless of whether the
stamp did**. Two versions of one group can be in flight at once — the sweep enqueues a new version while the
previous item is still running — and when they complete out of order the older evaluation's stamp is
correctly refused while its model call was really spent. Refusing the charge with the stamp would under-count
exactly the double spend the ceiling exists to bound.

Only a **strictly newer** stamp SHALL refuse an older one. A re-evaluation at the version already stamped
SHALL stamp, refreshing `computed_at` and `computed_truncated` to describe the evaluation that just ran: that
is the ordinary shape of an operator re-drive, whose work item is built at the group's current version, which
for a clean group is exactly its `computed_version`. Refusing it would leave the state describing the
*previous* evaluation while the enrichment table carried the new one — and since `max_staleness` is measured
against `computed_at`, would leave a freshly recomputed group as stale as before.

An evaluation whose stamp was refused SHALL be **observable**, since the charge is otherwise silent and the
refusal matches no rows: it is the direct signal that a group's evaluations are taking longer than the sweep
interval. Only a genuine refusal SHALL be reported: a stamp that matched nothing because the group's state row
was evicted between the evaluation and the write has no newer label to have superseded it, and the charge —
which carries no version guard, so it lands if and only if the row exists — is what tells the two apart.

#### Scenario: A group at its ceiling is not enqueued

- **GIVEN** a dirty and ready group whose evaluations today have reached its pipeline's `cost_ceiling`
- **WHEN** the readiness sweep runs
- **THEN** no work item is enqueued for that group

#### Scenario: A group under its ceiling is enqueued normally

- **GIVEN** a dirty and ready group whose evaluations today are below its pipeline's `cost_ceiling`
- **WHEN** the readiness sweep runs
- **THEN** a work item is enqueued for it

#### Scenario: A pipeline without a ceiling is unlimited

- **GIVEN** a group pipeline whose `ready_when` declares no `cost_ceiling`
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

#### Scenario: A re-evaluation at the same version re-stamps and is not superseded

- **GIVEN** a clean group being re-evaluated at the version already stamped
- **WHEN** the evaluation completes
- **THEN** `computed_at` and `computed_truncated` describe this evaluation, it is charged, and it is not
  reported as superseded

#### Scenario: A stamp for an evicted group is not reported as superseded

- **GIVEN** a group whose state row was evicted between its evaluation and the write
- **WHEN** the stamp is attempted
- **THEN** neither the charge nor the stamp lands, and nothing is reported as superseded

### Requirement: Content cut to fit the budget is observable

The runner SHALL count every group input whose remaining member had its own content cut to fit the token
budget, separately from the row's `truncated` provenance.

The two answer different questions and only one of them is about the budget. `truncated` is set by any
narrowing, the pipeline's own member-selection policy included, so on a pipeline that selects one member per group it
is true almost always and says nothing about whether content was lost. The cut count rises only when content
the model would otherwise have seen was removed, which makes it the measurement — rather than a log grep —
that any change to `group.input-token-budget` has to be argued from.

#### Scenario: A budget cut is counted, a policy-narrowed group is not

- **GIVEN** a pipeline whose member policy narrows every group, and a group whose remaining member is cut to fit
  the budget
- **WHEN** the work items execute
- **THEN** only the cut member's evaluation increments the content-cut count, while both are recorded
  truncated

### Requirement: Ceiling-blocked groups are observable

The runner SHALL report how many groups the readiness sweep declined on the cost ceiling. A working ceiling
is otherwise indistinguishable from a group that simply stopped changing — no work items, no errors, no log
output — so without this signal an operator cannot tell a budget that is protecting the pipeline from a pipeline
that has broken.

#### Scenario: Declined groups are reported

- **WHEN** a sweep pass declines groups because they are at their ceiling
- **THEN** the number declined is published for scraping
