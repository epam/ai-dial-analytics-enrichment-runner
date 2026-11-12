## MODIFIED Requirements

### Requirement: Row enrichment via DIAL

For each unit of work — a row of a chunk, or a whole group — the runner SHALL render the pipeline's
`transform.request_template` by substituting that unit's input variables, assemble the DIAL request, call DIAL Core, and read the structured output. The call SHALL be dispatched
on `transform.preset`:

- **`chat_completion`**: the body is the rendered template overlaid with `transform.params`, plus a
  `response_format` derived from the entry's top-level `response_schema`. It is POSTed to
  `/openai/deployments/{model}/chat/completions` with the configured `api-version`, and the output is the
  first choice's message content.
- **`dial_app_route`**: the body is the rendered template overlaid with `transform.params`, with no
  `response_format`. It is POSTed to `/v1/deployments/{model}/route/{route}` with no `api-version`, and the
  output is the **whole response body**, parsed as JSON, or, when the transform declares `response_jsonata`,
  that expression's result over it. There is no chat
  envelope to unwrap. The route is appended segment by segment, each segment percent-encoded as a path
  segment, so no character of it can change the URL's structure. `response_format` and `api-version` are
  parts of the chat-completions contract that an application route does not take part in, so sending
  them would only hand the route members it did not ask for.

Everything else on the call SHALL be the same under both presets: rate-limiter acquisition, the DIAL span,
the timer, and the retryable-vs-permanent classification of a failed status. The DIAL span SHALL carry the
preset as an attribute. A
variable whose value is a collection SHALL render as a JSON array, so a group's member list can be
interpolated; a scalar SHALL render as it does today. The render → call → validate → map pipeline SHALL be
shared by both grains rather than reimplemented per grain, so the rate-limiter acquisition, spend accounting
and retryable-status classification have one implementation.

The `response_schema` read here is the **derived top-level** member of the compiled feed entry — the
resolved schema, whether ADAS stored one on the declaration or composed it from the outputs — and SHALL NOT
be read from inside the authored `transform` block, where a member of the same name means only "the schema
as authored".

#### Scenario: A row is rendered and sent to DIAL

- **GIVEN** a chunk row with input variables and a pipeline whose `transform` declares a request template
- **WHEN** the row is processed
- **THEN** the rendered request is sent to `.../deployments/{model}/chat/completions` and the
  response content is read

#### Scenario: A collection variable renders as a JSON array

- **GIVEN** an input variable whose value is a list of objects
- **WHEN** the template is rendered
- **THEN** the variable is substituted with a JSON array, and scalar variables are unaffected

#### Scenario: An app-route transform is sent to the application's route

- **GIVEN** a pipeline whose `transform` carries `preset: dial_app_route`, `model: insights-app` and
  `route: v1/insights`
- **WHEN** a unit of work is processed
- **THEN** the rendered body is POSTed to `/v1/deployments/insights-app/route/v1/insights` with no
  `api-version` query parameter and no `response_format` member, and the whole response body is read as
  the output

#### Scenario: A route failure is classified like a model failure

- **GIVEN** an app-route pipeline whose route answers `503`, and then `400`
- **WHEN** units of work are processed
- **THEN** the `503` is retried as transient and the `400` is dead-lettered at the DIAL-call stage, exactly
  as for a chat-completion call

### Requirement: Response validation with one repair retry

The runner SHALL validate the model's structured output against the entry's derived top-level
`response_schema`. Under `preset: chat_completion`, on a validation failure it SHALL re-request once with
the validation error carried as a corrective message; if the response is still invalid, that row SHALL be
dead-lettered at stage `validate` with the raw response, while the rest of the chunk proceeds.

Under `preset: dial_app_route` there SHALL be **no repair retry**. An invalid output, or a body that is not
JSON, SHALL be dead-lettered at stage `validate` with the raw response on the first attempt. A corrective
message is a conversational turn, and a route's body has no conversation to append it to. Resending the
same body would be charged again for the same deterministic answer, and an application that calls models
internally does its own repair or none.

A reply that is **not parseable JSON at all** — truncated by a token limit, or prose where JSON was asked
for — SHALL be reported as an ordinary validation failure rather than raised, and SHALL be so reported even
for a pipeline whose entry carries **no** `response_schema`, since the reply is parsed for output mapping
either way. Raising it instead would skip the repair retry and dead-letter the unit at a stage that has
nothing to do with validation, carrying no raw response — losing the evidence precisely for the reply that
most needs it.

The no-schema case narrows rather than disappears: because ADAS composes `response_schema` from the outputs
when the declaration stores none, an entry with no `response_schema` at all is now the only shape that
reaches this path unvalidated.

#### Scenario: Invalid output after repair is dead-lettered

- **GIVEN** a row whose model response fails schema validation on both the initial and repair attempt
- **WHEN** the row is processed
- **THEN** a `dlq_item` at stage `validate` is recorded and no enrichment row is written for it

#### Scenario: A reply that is not JSON gets the repair retry

- **GIVEN** a row whose model reply is truncated or is not JSON, with or without a `response_schema` on the
  entry
- **WHEN** the row is processed
- **THEN** the repair retry is attempted, and a reply still unparseable after it is dead-lettered at stage
  `validate` with the raw response attached

#### Scenario: An invalid route response is dead-lettered without a second call

- **GIVEN** an app-route pipeline whose route answers a body that fails the `response_schema`
- **WHEN** the unit of work is processed
- **THEN** exactly one call is made to the route, and a `dlq_item` at stage `validate` carrying the raw
  body is recorded

## ADDED Requirements

### Requirement: A route response is reshaped by the transform's response mapping before validation

When a `dial_app_route` transform carries `response_jsonata`, the runner SHALL evaluate it over the route's
parsed response body. The result SHALL be the output: the object validated against the entry's
`response_schema`, and the object the output mapping then reads. That makes the order parse → reshape →
validate → map. A body that is not JSON, an expression that fails to evaluate, and a result that is not a
JSON object SHALL each be a validation failure. The unit is dead-lettered at stage `validate` with the
**raw route body** attached, not the reshaped one, because the raw body is what an operator needs in
order to fix the expression. The expression SHALL be compiled once per pipeline generation, not once per
call.

#### Scenario: A nested route response is flattened and written

- **GIVEN** a route answering `{"title":"t","intent_counts":{"check":2},"usage":{"cost":1}}`, and
  `response_jsonata` `{"title": title, "intent_counts__check": intent_counts.check}`
- **WHEN** the unit of work is processed
- **THEN** `{"title":"t","intent_counts__check":2}` is validated and mapped, and the `usage` member is never
  seen by validation

#### Scenario: A mapping that yields no object is dead-lettered with the raw body

- **GIVEN** a `response_jsonata` that evaluates to a string for the route's body
- **WHEN** the unit of work is processed
- **THEN** a `dlq_item` at stage `validate` is recorded carrying the route's raw body, after exactly one call

### Requirement: A placeholder may render its value as raw JSON

The template renderer SHALL accept two placeholder forms:

- `{{name}}` renders as today: a string is escaped for a JSON string literal, a collection is rendered
  as JSON and then escaped, a number or boolean stays bare, and a null is empty.
- `{{name:json}}` SHALL render the bound value as a **JSON literal** for value position:
  - a string becomes a quoted JSON string;
  - a number or boolean stays bare;
  - a list or map becomes its JSON text, unescaped;
  - a bound null becomes `null`.

The suffix SHALL be exactly `:json`, immediately after the name. The unbound-placeholder check SHALL apply
to the name before it, and an unbound `{{name:json}}` SHALL fail the work item exactly as an unbound
`{{name}}` does. Substitution SHALL stay single-pass over the template.

A placeholder carrying another suffix is refused by ADAS when the pipeline is armed. If one reaches the
runner anyway, its whole text SHALL be treated as an unbound name, failing the work item rather than
rendering through.

#### Scenario: Members render as a JSON array

- **GIVEN** a group template `{"members": {{members:json}}}` and two assembled members
- **WHEN** it is rendered
- **THEN** the body is a JSON object whose `members` member is an array of two objects

#### Scenario: A raw null renders as JSON null

- **GIVEN** a row template `{"note": {{note:json}}}` whose `note` is bound to null
- **WHEN** it is rendered
- **THEN** the body is `{"note": null}`

#### Scenario: An unbound raw placeholder fails the work item

- **GIVEN** a row template carrying `{{missing:json}}` and no `missing` binding
- **WHEN** it is rendered
- **THEN** the work item is dead-lettered by the unresolvable-template path naming `missing`, and no DIAL call
  is made
