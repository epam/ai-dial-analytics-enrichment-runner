## ADDED Requirements

### Requirement: A dry run evaluates a sampled row or group through the model

The runner SHALL expose `POST /v1/pipelines/{name}/try` for `llm` enrich pipelines of either preset,
`chat_completion` or `dial_app_route`, enabled or **disabled**. The body MAY carry `group_key`, `from`, `to`
and `limit`, and SHALL be refused with HTTP 400 `bad_request` for any other member. `group_key`, `from` and
`to` mean what they mean on ADAS's preview. `limit` is the number of row-grain rows to evaluate, between `1`
and `5`, defaulting to `1`. A `limit` outside that range SHALL be refused with HTTP 422
`pipeline_validation_failed`, the answer ADAS's trial-input gives the same body, before trial-input is read. A
`limit` on a `group` pipeline is refused by ADAS, whose refusal is passed through: only the read tells the
runner which trigger the pipeline has.

The runner SHALL obtain its input from ADAS's `POST /v1/pipelines/{name}/trial-input` with the same body, and
SHALL evaluate what it returns:

- for a **row-grain** pipeline, each entry of `input_rows`, through the input mapping the scan applies, then
  the transform;
- for a **group** pipeline, the whole of `input_rows` as one group, through the group-input assembly the
  group worker applies (with `truncated` as the fetch reported it, and the pipeline's input token budget),
  then the transform.

The transform SHALL be evaluated by the **same code path** the workers use: the same template renderer, the
same request body for the preset, the same DIAL endpoint and read timeout, the same corrective retry for
`chat_completion` and none for `dial_app_route`, the same response validation against the response schema,
and the same output mapping. The declaration evaluated SHALL be the `pipeline` member trial-input returned,
not this runner's cached copy, so a disabled draft, which the cache never holds, is evaluated as arming
would store it.

The runner SHALL evaluate the rows of one dry run concurrently, at most `limit` at a time.

#### Scenario: A row-grain dry run calls the model once

- **GIVEN** a disabled row-grain `chat_completion` pipeline whose newest matching row ADAS samples
- **WHEN** `POST /v1/pipelines/{name}/try` is called with no body
- **THEN** exactly one chat-completion request is sent to the pipeline's deployment, with
  `response_format` carrying the response schema, and one evaluation is returned

#### Scenario: A row-grain limit evaluates that many rows

- **WHEN** the same pipeline is tried with `{"limit": 3}` and ADAS returns three input rows
- **THEN** three requests are sent and three evaluations are returned, in the order of the input rows

#### Scenario: A group dry run evaluates the whole fetched group

- **GIVEN** a `group` pipeline whose sampled group ADAS returns with twelve members
- **WHEN** it is tried
- **THEN** one request is sent whose rendered `members` are the twelve members after the input token budget,
  and the evaluation reports how many members were included and omitted

#### Scenario: An app-route dry run calls the route

- **GIVEN** a `dial_app_route` pipeline with `model` `insights-app` and `route` `insights`
- **WHEN** it is tried
- **THEN** one request is sent to the route endpoint of `insights-app` with the rendered body and no
  `response_format`, with no corrective retry, and the outputs are read through each output's `source`

#### Scenario: The cached declaration is not used

- **GIVEN** a pipeline whose cached compiled declaration differs from the `pipeline` trial-input returns
- **WHEN** it is tried
- **THEN** the request is rendered and the reply validated and mapped against the declaration trial-input
  returned

#### Scenario: A limit out of range is refused before ADAS is read

- **WHEN** a pipeline is tried with `{"limit": 6}` or `{"limit": 0}`
- **THEN** the response is HTTP 422 `pipeline_validation_failed`, trial-input is not called, and no DIAL call is
  made

### Requirement: The dry run's input is read under the caller's identity

The runner SHALL call trial-input with the **caller's own credential**, the inbound `Authorization` header or
DIAL `Api-Key` that authenticated the `try` request, and SHALL NOT send its own service credential on that
call. ADAS then applies the caller's column policy, sensitive-column clearance and row confinement, the arm
gate of a disabled pipeline, and the `app-routes` allowlist of a `dial_app_route` transform. A dry run
SHALL NOT read, render or send anything the caller's own ADAS preview would refuse.

Under `security.mode=none` there is no inbound credential. The runner SHALL then call trial-input with its
service credential, since a runner that admits unauthenticated callers has no caller identity to preserve.

Every other check this runner makes on a declaration it admits SHALL also be made on the declaration
trial-input returns, before any DIAL call. A declaration the pipeline sync would refuse to admit (an
unparseable route, an output `source` that does not compile, a missing template, model or response schema)
SHALL be refused with HTTP 422 `pipeline_not_runnable`, whose message names the problem as the sync's
admission does.

#### Scenario: ADAS refuses for the caller and the dry run refuses with ADAS's answer

- **GIVEN** a caller who lacks clearance for a `sensitive` column the pipeline binds
- **WHEN** that caller tries the pipeline
- **THEN** trial-input is called with the caller's bearer token, ADAS answers HTTP 403
  `sensitive_column_not_entitled`, the dry run answers the same status and code, and no DIAL call is made

#### Scenario: A route not on the allowlist is never called

- **GIVEN** a disabled `dial_app_route` pipeline whose pair is not in ADAS's `app-routes` setting
- **WHEN** it is tried
- **THEN** the response is HTTP 422 `pipeline_validation_failed` as ADAS reported it, and no DIAL call is made

#### Scenario: The service credential is not sent with a caller's request

- **WHEN** a caller authenticated by a bearer token tries a pipeline
- **THEN** the trial-input request carries that bearer token and no service `Api-Key`

#### Scenario: A declaration the sync would refuse is not called

- **GIVEN** trial-input returns a `dial_app_route` declaration whose output `source` does not compile
- **WHEN** it is tried
- **THEN** the response is HTTP 422 `pipeline_not_runnable` naming that output, and no DIAL call is made

### Requirement: A dry run spends, but changes no other runtime state

A dry run's DIAL calls SHALL be recorded in `spend_counter` under the pipeline and the `generation` of the
declaration evaluated, exactly as a worker's calls are: tokens for `chat_completion`, one call for
`dial_app_route`, and a permanently refused call as a failure. The money is spent, so leaving it out would
let dry runs exceed a budget that reports itself as respected.

When the soft daily spend gate is enabled and the pipeline's day-to-date usage has reached its budget, the
dry run SHALL be refused with HTTP 429 `spend_budget_exhausted` before trial-input is read and before any DIAL
call. Each DIAL call SHALL take a permit from the pipeline's rate limiter only if one is free, rather than
delay the pipeline's own work. When the limiter refuses a call and the dry run has made no DIAL call, the dry
run SHALL be refused with HTTP 429 `dial_rate_limited`. Once one of its calls has been made, a refused call
SHALL instead end its own evaluation as `failed` at `dial_call`, with a `message` naming the rate limit and
the `attempts`, `response` and `usage` of the calls before it, and every other evaluation SHALL be answered
as usual: those calls were paid for, so their answers are returned rather than discarded behind a 429.

At most one dry run SHALL be in progress per pipeline. A second one SHALL be refused with HTTP 409
`dry_run_in_progress`.

A dry run SHALL NOT write a dead-letter entry, enqueue or send a write-back, move a scan cursor, stamp or
charge group state or a group's `cost_ceiling`, count towards the dead-letter circuit breaker, or pause or
resume the pipeline. It SHALL run whether or not the pipeline is paused, since pausing is about scheduled
work.

#### Scenario: A dry run's tokens count towards the day's spend

- **WHEN** a `chat_completion` dry run's call reports 1200 total tokens
- **THEN** the pipeline's day-to-date usage rises by one call and 1200 tokens

#### Scenario: A pipeline over its daily budget is not called

- **GIVEN** the spend gate is enabled and the pipeline has reached its daily call budget
- **WHEN** it is tried
- **THEN** the response is HTTP 429 `spend_budget_exhausted`, trial-input is not called, and no DIAL call is
  made

#### Scenario: A failing dry run leaves no dead letter

- **WHEN** a dry run's reply fails response validation
- **THEN** no DLQ entry exists for it, no write-back is enqueued, and the pipeline's cursor and group state are
  unchanged

#### Scenario: Concurrent dry runs of one pipeline are refused

- **WHEN** a second `try` for a pipeline arrives while the first is still running
- **THEN** the second is answered HTTP 409 `dry_run_in_progress`, and the first completes unaffected

#### Scenario: The rate limiter refuses rather than queues

- **GIVEN** the pipeline's rate limiter has no permit available
- **WHEN** it is tried
- **THEN** the response is HTTP 429 `dial_rate_limited` and no DIAL call is made

#### Scenario: A limiter refusal after a paid call keeps that call's answer

- **GIVEN** a `chat_completion` pipeline whose rate limiter has one permit left, and a model whose first reply
  fails response validation
- **WHEN** it is tried
- **THEN** the response is HTTP 200 with one evaluation whose `status` is `failed`, `stage` is `dial_call`,
  `attempts` is `1`, `message` names the rate limit and `response` holds the first reply
- **AND** the pipeline's day-to-date usage rises by that one call

### Requirement: A dry run answers with the preview and one evaluation per subject

A dry run that reached evaluation SHALL answer HTTP 200 with:

- `preview`: the `preview` member trial-input returned, unchanged, so a client renders the same sample it
  renders for ADAS's preview;
- `evaluations`: one entry per evaluated row (identified by `grain_key`) or for the group (identified by
  `group_key`), each carrying:
  - `status`: `ok` or `failed`;
  - `stage`, when failed: the stage named as the dead-letter queue names it (`input_map`, `group_map`,
    `dial_call`, `validate` or `output_map`);
  - `outputs`: the mapped value of each output, keyed by target column, when `ok`;
  - `request`: the rendered request body sent on the first attempt, and `response`: the raw reply of the last
    attempt, each capped at 64 KiB, with `request_truncated` and `response_truncated` saying whether the cap
    cut them;
  - `dial_status` and `message` when DIAL refused the call, with the message holding DIAL's error text;
  - `message` and the validation errors when the reply failed validation or mapping;
  - `attempts`: the number of DIAL calls made, the corrective retries included;
  - `usage`: `{amount, unit}`, in tokens or calls;
  - `latency_ms`;
  - for a group: `members_included`, `members_omitted` and `members_truncated`.

An empty sample (trial-input's `input_rows` empty, with ADAS's notes saying why) SHALL answer HTTP 200 with
no evaluations and make no DIAL call.

A model or application failure SHALL NOT be an HTTP error, because the dry run did what it was asked and the
failure is its answer. A permanently refused DIAL call (a `4xx` other than `408`, `425` and `429`, such as a
`404` for an unknown deployment) is a failed evaluation at `dial_call`. A transient one (a `5xx`, `408`, `425`,
`429` or a transport failure) is also a failed evaluation at `dial_call`, and SHALL NOT be retried beyond the
worker's corrective retry.

ADAS's refusal of trial-input SHALL be answered with ADAS's status and `error` code and message in this
runner's `ErrorView`. An ADAS that cannot be reached, or answers a gateway error, SHALL be HTTP 503
`adas_unavailable`. An ADAS on another contract version SHALL be HTTP 503 `adas_contract_mismatch`, and the
verdict SHALL be recorded as for any other call.

The rendered request, the members and the reply SHALL appear only in the response. They SHALL NOT be logged
at any level, as no rendered prompt or member row is.

#### Scenario: A missing deployment is reported, not raised

- **GIVEN** a pipeline whose `model` names a deployment DIAL does not know
- **WHEN** it is tried
- **THEN** the response is HTTP 200 with one evaluation whose `status` is `failed`, `stage` is `dial_call`,
  `dial_status` is `404` and `message` carries DIAL's error text

#### Scenario: A reply that does not fit the schema is reported with what was received

- **GIVEN** a `chat_completion` pipeline whose model answers without a required output, before and after the
  corrective retry
- **WHEN** it is tried
- **THEN** the evaluation's `status` is `failed`, `stage` is `validate`, `attempts` is `2`, `response` holds the
  last reply, and the validation errors name the missing property

#### Scenario: A successful evaluation carries typed output values

- **WHEN** a pipeline with outputs `title` and `sentiment` is tried and the reply validates
- **THEN** the evaluation's `status` is `ok` and `outputs` carries a value for each target column, mapped as a
  worker would write it

#### Scenario: An oversized rendered request is capped in the response

- **WHEN** the rendered request of a group evaluation exceeds 64 KiB
- **THEN** `request` holds its first 64 KiB and `request_truncated` is `true`, while the full request was sent

#### Scenario: An empty sample makes no call

- **WHEN** trial-input answers with no input rows and a `no_group_found` note
- **THEN** the dry run answers HTTP 200 with that preview, no evaluations, and no DIAL call

#### Scenario: An unreachable ADAS is a service error

- **WHEN** trial-input times out
- **THEN** the dry run answers HTTP 503 `adas_unavailable`, no DIAL call is made, and the recorded contract
  verdict is unchanged

#### Scenario: Dry-run content is never logged

- **GIVEN** logging configured down to its most verbose level
- **WHEN** a group pipeline is tried
- **THEN** no log record contains the members' content, the rendered request or the reply
