## ADDED Requirements

### Requirement: The input token budget is chosen per pipeline

The runner SHALL resolve each group pipeline's input token budget from the pipeline's own
`advanced.input_token_budget`, falling back to the runner's `group.input-token-budget`:

- a declared `advanced.input_token_budget` of `0` or more SHALL be the budget; `0` means the runner does not
  trim;
- when it is absent, the budget SHALL be `group.input-token-budget`.

The resolution SHALL NOT depend on `transform.preset`. Whether the runner's estimate should bound a group is a
property of what receives it, and the preset does not say that. A `dial_app_route` application that tokenizes
properly and chooses what to keep is better served untrimmed, and its pipeline opts out with `0`. An application
that forwards the group to a model without managing its size needs the guard as much as a `chat_completion` call
does. Only the pipeline author knows which kind it is. One default also means a pipeline that declares nothing
keeps the input it had before the knob existed.

A negative `advanced.input_token_budget` SHALL be read as absent, falling back to `group.input-token-budget`, and
SHALL be logged at most once per pipeline generation. ADAS validates the knob, so a negative value is a producer
defect. Reading it as `0` would silently drop the guard, and refusing the pipeline would stop it over a knob that
bounds only its input size.

The budget SHALL apply to the group lane only. The row lane and the Class-1 lane have no input budget, and the runner
SHALL ignore the knob on them rather than refusing the pipeline.

#### Scenario: A pipeline without the knob uses the runner default under either preset

- **GIVEN** a `chat_completion` group pipeline and a `dial_app_route` group pipeline, neither declaring
  `advanced.input_token_budget`
- **WHEN** their budgets are resolved
- **THEN** both budgets are `group.input-token-budget`

#### Scenario: An app-route pipeline declaring zero is not trimmed

- **GIVEN** a `dial_app_route` group pipeline declaring `advanced.input_token_budget: 0`, and a group whose
  assembled input exceeds `group.input-token-budget`
- **WHEN** the work item executes
- **THEN** the runner drops and cuts nothing, and the application receives every member ADAS returned

#### Scenario: A declared budget overrides the default

- **GIVEN** a group pipeline declaring `advanced.input_token_budget: 1000`
- **WHEN** its budget is resolved
- **THEN** the budget is `1000`, whatever `group.input-token-budget` is

#### Scenario: A negative budget falls back to the runner default

- **GIVEN** a `chat_completion` group pipeline declaring `advanced.input_token_budget: -1`
- **WHEN** its budget is resolved
- **THEN** the budget is `group.input-token-budget`, and the fallback is logged once for that generation

## MODIFIED Requirements

### Requirement: Bounded group input

The runner SHALL apply the pipeline's input token budget (see *The input token budget is chosen per pipeline*)
to the assembled input on top of ADAS's caps, dropping the
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

#### Scenario: A pipeline whose budget is off is not trimmed by the runner

- **GIVEN** a group pipeline whose resolved input token budget is `0`, and a group whose assembled input is
  larger than `group.input-token-budget`
- **WHEN** the work item executes
- **THEN** every member ADAS returned is passed to the transform with its content intact, no member is
  reported omitted or cut, and the content-cut count does not move

#### Scenario: ADAS truncation still reaches a pipeline whose budget is off

- **GIVEN** a group pipeline whose resolved input token budget is `0`, and a `group-rows` response reporting
  `truncated=true`
- **WHEN** the work item executes
- **THEN** the template inputs carry `members_truncated` true, `members_omitted` 0 and a non-empty
  `members_notice`, and the row is recorded as truncated

### Requirement: Content cut to fit the budget is observable

The runner SHALL count every group input whose remaining member had its own content cut to fit the token
budget, separately from the row's `truncated` provenance, and SHALL attribute each count to the pipeline whose
budget made the cut. Budgets differ per pipeline, so a runner-wide count cannot show which pipeline's budget is
too tight.

The two answer different questions and only one of them is about the budget. `truncated` is set by any
narrowing, the pipeline's own member-selection policy included, so on a pipeline that selects one member per group it
is true almost always and says nothing about whether content was lost. The cut count rises only when content
the model would otherwise have seen was removed, which makes it the measurement — rather than a log grep —
that any change to a pipeline's `advanced.input_token_budget`, or to `group.input-token-budget`, has to be argued from.

#### Scenario: A budget cut is counted, a policy-narrowed group is not

- **GIVEN** a pipeline whose member policy narrows every group, and a group whose remaining member is cut to fit
  the budget
- **WHEN** the work items execute
- **THEN** only the cut member's evaluation increments the content-cut count, while both are recorded
  truncated

#### Scenario: A cut is attributed to its pipeline

- **GIVEN** two group pipelines, one of which cuts a member to fit its budget
- **WHEN** the work items execute
- **THEN** the content-cut count rises for that pipeline only
