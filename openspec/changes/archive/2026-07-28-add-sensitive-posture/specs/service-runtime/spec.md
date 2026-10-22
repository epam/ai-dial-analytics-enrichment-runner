## ADDED Requirements

### Requirement: Sensitive-data identity posture

The runner SHALL operate against ADAS with an identity entitled to read `sensitive` columns —
`FULL_ADMIN` — for any rule that references one. This **supersedes** the original least-privilege stance
(per-target grants only, no global admin role) and is recorded as a deliberate deviation rather than an
oversight. It is forced, not chosen: ADAS re-checks sensitive entitlement at scan, group-assembly and
materialize egress against the calling identity and **rejects rather than strips**, so a least-privilege
runner receives an error rather than a redacted row and no degraded mode exists.

What the entitlement widens SHALL be stated as precisely as the entitlement itself: it widens *which columns
a rule may reference*, not *which rules the runner may operate*. The surface stays rule-scoped in both
directions — the scan projects only rule-referenced columns, group assembly returns only the rule's
membership, and write-back rejects any column outside the rule's declared outputs plus permitted provenance.

The runner SHALL state this expectation in its startup logs, so a deployment's posture is visible where that
deployment's operator will look. It SHALL also warn that ADAS's sensitive check fails **open** when ADAS runs
with security disabled, because that is the local-development default and therefore cannot surface a
misconfigured production identity.

A narrower grant — per-rule or per-service-identity — that would let the runner return to per-target access
SHALL be recorded as the follow-up rather than implied to exist.

#### Scenario: The identity expectation is stated at startup

- **WHEN** the application starts
- **THEN** it logs that it expects an identity entitled to read sensitive columns, and that ADAS's check
  fails open when ADAS security is disabled

#### Scenario: The entitlement does not widen which rules the runner may operate

- **WHEN** the runner holds a sensitive-entitled identity
- **THEN** it still reads only columns its rules reference and writes only columns its rules declare

### Requirement: Body-handling constraints

The runner SHALL NOT log assembled group input, member rows, or rendered prompts at **any** level. This
covers the framework as well as the runner's own code: Spring's HTTP client logs request and response bodies
at DEBUG/TRACE, and for this service those bodies *are* the enrichment payloads — a rendered prompt carries the
source content a rule reads. Those loggers SHALL therefore be pinned independently of the application log
level, so raising verbosity for diagnosis cannot silently start writing source content to the log pipeline.
Supplying a logging configuration that overrides the pin re-enables body logging, which a deployment handling
sensitive data SHALL NOT do.

The runner SHALL NOT place member rows in a work-item payload: a group work item carries identifying keys only, so member
bodies never reach `scheduled_tasks`. Model output SHALL persist in exactly one place — a dead-letter item's
raw response, bounded by the dead-letter retention window — so an audit of where derived content lands has a
single answer.

These constraints SHALL be verified rather than trusted, because each is a property of every present and
future line in the enrichment path and the cost of breaking one is source content in a log aggregator.

#### Scenario: Member content never reaches the logs

- **GIVEN** a group whose members carry identifiable content, and logging raised to its most verbose level
- **WHEN** the group is evaluated, including its failure paths
- **THEN** no log record contains that content, from the runner's code or from the HTTP client

#### Scenario: A group work item carries no member rows

- **WHEN** a group work item is enqueued and serialized
- **THEN** its payload carries only identifying keys, not the group's members
