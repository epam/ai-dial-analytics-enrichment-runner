## ADDED Requirements

### Requirement: A route call is accounted as a call

A `dial_app_route` call SHALL be recorded in `spend_counter` like any other DIAL call: a success increments
the call count, and a failure increments the failure count. Its usage amount SHALL be `0`, with unit
`calls`, on a failed call as on a successful one: the counter row keeps the unit of the day's first record,
so a failed first call recorded as `tokens` would mislabel the whole day. An application route reports usage,
if it reports any, in a shape of its own, and the runner SHALL NOT guess a token count from one. The daily spend gate's **calls** budget therefore bounds a route pipeline,
and its **tokens** budget does not accrue for it.

#### Scenario: Route calls count as calls with no tokens

- **WHEN** two successful route calls and one failed one are recorded for a pipeline today
- **THEN** the day's totals are 2 calls, 0 tokens and 1 failure

#### Scenario: A failed first call of the day is recorded in calls

- **GIVEN** no spend recorded today for a route pipeline
- **WHEN** its first route call is answered `400`
- **THEN** the day's row records 1 failure with unit `calls`

#### Scenario: The calls budget pauses a route pipeline

- **GIVEN** the gate is enabled with a daily calls budget of 2
- **WHEN** a route pipeline has made 2 successful calls today
- **THEN** it is reported over budget and its next scan fire is skipped
