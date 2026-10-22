## MODIFIED Requirements

### Requirement: Deterministic group work-item identity

A group work item's instance id SHALL be derived from the pipeline name, the pipeline's `generation`, group
key and `group_version`, so re-sweeping an unchanged group produces the same id and is suppressed, while a
changed group yields a higher version and therefore a new item. The id SHALL retain the pipeline-name prefix
the backpressure count relies on, so a group pipeline's in-flight depth is countable without deserializing
payloads — and the readiness sweep SHALL consult that count, skipping a pipeline whose queued work already
exceeds `scan.max-inflight`. Skipping a pass costs nothing: the groups remain dirty and the next pass
selects them.

Substituting `generation` for the evaluator version widens what re-fires a group, and that is a behaviour
change rather than a renaming: `generation` bumps on **any** declaration edit — including `cadence`,
`rate_rpm` and `batch_scan_limit`, which no evaluator version ever moved for — so after such an edit an
unchanged group re-sweeps to a **different** instance id, `scheduleIfNotExists` no longer suppresses it, and
it is re-evaluated at the cost of one model call per affected group. Every group of a pipeline is affected
by one knob edit, so the cost is proportional to the pipeline's group count, not to what changed.

The identity is derived by `DeterministicId.groupInstanceId(String pipelineName, int evaluatorVersion,
String groupKey, long groupVersion)` in
`src/main/java/com/epam/aidial/enrichment/scan/DeterministicId.java`, which hashes
`pipelineName + "|" + evaluatorVersion + "|" + groupKey + "|" + groupVersion` and returns
`pipelineName + "#g#" + sha1Hex(basis)`. Its second parameter SHALL become the pipeline's `generation`, and
SHALL be typed `long` rather than `int`, since a `generation` is a `long`.

The prefix is delimited by `#`, and the runner SHALL depend on ADAS refusing that character in a pipeline
name at registration (`^[a-z][a-z0-9_-]{0,63}$`) rather than re-validating it here. Without that exclusion the
pipelines `a#b` and `a` would share the prefix `a#`, and one pipeline's in-flight count — and therefore its
backpressure and this sweep's suppression — would silently include another's work. A `_` in a name is safe by
a different mechanism: the prefix scan escapes LIKE wildcards, so it cannot broaden a match.

#### Scenario: An unchanged group is not enqueued twice

- **WHEN** the same pipeline, `generation`, group key and group version are enqueued again
- **THEN** the deterministic id dedups and no additional work item is created

#### Scenario: A changed group re-fires

- **GIVEN** a group already enqueued at one version
- **WHEN** a new member raises its `group_version` and the sweep runs
- **THEN** a distinct work item is enqueued for the new version

### Requirement: A group with work already in flight is not re-evaluated

The readiness sweep SHALL NOT enqueue a group for which a compute **or** a write work item already exists for
that exact pipeline, `generation`, group key and `group_version`, and SHALL report how many groups a pass
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

Because the key now carries `generation`, a declaration edit mid-flight also ends suppression: the in-flight
item was enqueued under the old `generation` and the sweep looks for one under the new, so the group is
enqueued again while the older item is still pending.

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

### Requirement: Group input assembly

When a group work item executes, the runner SHALL fetch the group's members from ADAS
`POST /v1/pipelines/{name}/group-rows`, sending only the group key and a row limit — ADAS resolves the source,
applies the membership filter, orders members by the source's total order, and enforces its row and
response-byte caps. The runner SHALL then apply the pipeline's `transform.inputs` to **each** member row —
the same map, resolved the same way, as on the row path — yielding one object per member keyed by variable
name, and SHALL expose that collection to the `transform`'s request template as a single variable rendered
as a JSON array. Member field correlation SHALL be preserved, so a member's bound fields stay together rather
than being split across parallel per-variable collections. The runner SHALL NOT name a table, a column set
or an ordering in the request.

#### Scenario: Members become one object each under a collection variable

- **GIVEN** a group pipeline whose `transform.inputs` declares two variables, and a group of three members
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

**The assembled input SHALL declare its own partiality to the transform.** Alongside the member collection,
the template inputs SHALL carry whether the input is truncated, how many members it carries, how many the
runner dropped, and a **pre-composed caveat that is empty when the input is complete**. The caveat SHALL
disclose a member that is present but whose own content was cut, and SHALL NOT report omitted earlier items
when none were dropped: a member list of one cut member is partial in a way no member count expresses, and a
count-shaped caveat would both present a fragment as a whole item and claim an omission that never happened. The caveat is
composed by the runner rather than left to each pipeline's `transform` because the template renderer performs
substitution only — with no conditionals, a `transform` cannot render a caveat only when one applies, and a
template must be able to interpolate it unconditionally. The dropped-member count SHALL be zero when only
ADAS's caps trimmed, because the group-assembly endpoint has no pagination and the remainder is unknowable;
the truncation flag, not the count, is the fact.

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

- **GIVEN** a group whose input was trimmed and a pipeline whose `transform` template interpolates the caveat
- **WHEN** the work item executes
- **THEN** the rendered prompt states that the input is a partial view of the group, and how many members it
  carries

#### Scenario: A complete group's prompt carries no caveat

- **GIVEN** a group whose input was not trimmed and the same `transform` template
- **WHEN** the work item executes
- **THEN** the caveat renders as nothing and the prompt reads as though it were not there

### Requirement: One enrichment row per group with group provenance

The runner SHALL write exactly one enrichment row per evaluated group, whose key value is the group key
and whose key **column** is the target enrichment's `grain_key`, carrying the mapped output columns and
the provenance columns `pipeline_generation`, `model`, `enriched_at`, `group_version` and `truncated`.

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

That toleration SHALL NOT be read as covering `evaluator_version`, which is no longer a provenance column at
all: ADAS refuses a row naming it with HTTP 422 rather than dropping it, so a group write naming it fails
permanently instead of being ignored.

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
item for requeue. The stale-declaration and pipeline-lifecycle guards SHALL apply as they do to a row chunk.

#### Scenario: A transient fetch failure retries without touching state

- **GIVEN** a group work item whose `group-rows` returns a 5xx
- **WHEN** the item fails
- **THEN** it is retried later, no dead-letter is produced, and the group's `computed_version` is unchanged

#### Scenario: A mapping failure dead-letters the group

- **GIVEN** a group whose model response cannot be output-mapped
- **WHEN** the item executes
- **THEN** a `dlq_item` at stage `group_map` carrying the group key is written

#### Scenario: A stale group item is dropped without calling the model

- **GIVEN** a group work item whose `generation` no longer matches the cached pipeline
- **WHEN** the item executes
- **THEN** no model call is made and nothing is written
