## MODIFIED Requirements

### Requirement: Full-admin authorization on functional endpoints

The runner SHALL require the `FULL_ADMIN` application role on every functional endpoint under `/v1` in
`oidc` mode, reads included. There is deliberately no read-versus-write split: the runner holds no
per-target grants of its own, so it has no basis on which to authorize a narrower caller, and every
functional endpoint either mutates runtime state or reveals which pipelines and groups exist. A caller that
authenticates but holds no `FULL_ADMIN` role SHALL be refused.

Authorization SHALL be enforced at the URL layer rather than by method-level annotations, because the
runner executes work on background scheduler threads that carry no security context, where method-level
enforcement would fail on work that no inbound request initiated.

Any request path that is neither a functional endpoint nor an explicitly public operational path SHALL be
denied by default rather than served.

#### Scenario: A full admin may invoke a functional endpoint

- **WHEN** a caller holding `FULL_ADMIN` requests any `/v1` endpoint
- **THEN** the request is served

#### Scenario: An authenticated caller without full admin is refused

- **WHEN** an authenticated caller holding no application role, or only `READ_ONLY_ADMIN`, requests any
  `/v1` endpoint including a read
- **THEN** the request is refused as forbidden

#### Scenario: An unauthenticated caller is refused

- **WHEN** a request to any `/v1` endpoint carries no credential
- **THEN** the request is refused as unauthenticated

#### Scenario: Background work is unaffected by authorization

- **GIVEN** authentication is enforced
- **WHEN** a scheduled scan, readiness sweep, or worker item executes on a background thread with no
  security context
- **THEN** it runs to completion and is never refused
