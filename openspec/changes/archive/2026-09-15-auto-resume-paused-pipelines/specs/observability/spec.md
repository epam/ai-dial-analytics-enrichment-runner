## ADDED Requirements

### Requirement: An auto-pause is alertable on its own

The runner SHALL expose the number of pipelines paused **by the circuit breaker** separately from the
total number paused.

The total cannot carry an alert. An operator's pause is a legitimate steady state, so a rule written
against the total is either permanently lit or permanently ignored — and with only the total, a pipeline
can sit auto-paused for hours with every surface reading healthy. A breaker-origin
pause has no legitimate steady state: it is by construction a temporary inference about an upstream, so
"one of these has persisted" is exactly the alert condition, and the separate series is what makes it
expressible.

Both series SHALL be untagged counts. The condition is about the runner's state, not about any one
pipeline's identity, and the listing is where a specific pipeline is named.

#### Scenario: An operator pause does not light the auto-pause alarm

- **GIVEN** a pipeline an operator has paused
- **WHEN** the metrics are scraped
- **THEN** the total paused gauge counts it and the auto-paused gauge does not

#### Scenario: A resumed auto-pause clears its series

- **GIVEN** an auto-paused pipeline whose pause window has elapsed
- **WHEN** the metrics are scraped after it resumes
- **THEN** the auto-paused gauge reads zero
