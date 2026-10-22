## ADDED Requirements

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

The charge SHALL be recorded together with the group's computed stamp, so a group cannot be recorded as
evaluated without also being charged for it.

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

#### Scenario: An empty group is not charged

- **GIVEN** a group whose assembly returns no members
- **WHEN** the work item executes
- **THEN** the group is stamped clean and its evaluation count is unchanged

#### Scenario: An evaluation that reached the model is charged even when it fails

- **GIVEN** a group whose model response cannot be mapped
- **WHEN** the work item executes
- **THEN** the group is dead-lettered and its evaluation count has increased

### Requirement: Ceiling-blocked groups are observable

The runner SHALL report how many groups the readiness sweep declined on the cost ceiling. A working ceiling
is otherwise indistinguishable from a group that simply stopped changing — no work items, no errors, no log
output — so without this signal an operator cannot tell a budget that is protecting the rule from a rule
that has broken.

#### Scenario: Declined groups are reported

- **WHEN** a sweep pass declines groups because they are at their ceiling
- **THEN** the number declined is published for scraping

## MODIFIED Requirements

### Requirement: Supported readiness declarations

The runner SHALL support `ready_when.idle` and `ready_when.max_staleness`, and SHALL refuse a group rule
whose readiness depends on `ready_when.signal`. ADAS projects the raw columns that predicate references
rather than a computed result, so honouring it would require a second implementation of ADAS's bounded
predicate grammar, whose divergence would silently mark the wrong groups ready. The refusal SHALL name
`signal` as the reason. `ready_when.cost_ceiling` SHALL be accepted and enforced as a per-group daily
evaluation budget.

#### Scenario: A rule declaring a signal is refused

- **WHEN** a sync returns a group rule whose `ready_when` includes `signal`
- **THEN** the rule is refused with `signal` named as the reason, is absent from the rule cache, and has
  no recurring task instance

#### Scenario: A rule declaring only idle and staleness is executed

- **WHEN** a sync returns a group rule whose `ready_when` declares `idle` and `max_staleness` but no
  `signal`
- **THEN** the rule is cached and its recurring task instance is created

#### Scenario: A rule declaring a cost ceiling is executed and bounded

- **WHEN** a sync returns a group rule whose `ready_when` declares `idle` and a `cost_ceiling`
- **THEN** the rule is cached, its recurring task instance is created, and its groups are bounded by that
  ceiling

### Requirement: Readiness sweep enqueues dirty and ready groups

The runner SHALL run a scheduled sweep, independent of the scan, that enqueues one work item per group
which is **dirty**, **ready**, and **within its budget**. A group is dirty when its `group_version` exceeds
its `computed_version`, or it has never been computed. A group is ready when `now − last_activity_at` has
reached the rule's `ready_when.idle`, or `now − computed_at` has reached its `ready_when.max_staleness`. A
group is within budget when its evaluations for the current UTC day have not reached the rule's
`ready_when.cost_ceiling`, which a rule declaring no ceiling always satisfies.

Readiness thresholds and the ceiling SHALL be read from the rule as synced, not stored per group. All three
conditions SHALL be applied in the selection itself rather than by filtering afterwards, so an ineligible
group cannot consume the pass's budget and crowd out an eligible one. Each pass SHALL be bounded by a
configured batch size so a large dirty backlog cannot monopolise one pass, and SHALL honour the same
rule-lifecycle, pause and spend gates as the scan. Time SHALL be read through an injected clock.

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

#### Scenario: A clean group is never enqueued

- **GIVEN** a group whose `computed_version` equals its `group_version`
- **WHEN** the sweep runs
- **THEN** no work item is enqueued for it

#### Scenario: A pass is bounded by its batch size

- **GIVEN** more dirty and ready groups than the configured per-pass batch size
- **WHEN** the sweep runs once
- **THEN** at most the batch size of work items is enqueued and the remainder is left for the next pass

#### Scenario: A group over its budget does not consume the pass

- **GIVEN** groups at their ceiling ordered ahead of eligible groups, and a per-pass batch size smaller than
  their combined count
- **WHEN** the sweep runs
- **THEN** the eligible groups are enqueued rather than being crowded out by the blocked ones
