## ADDED Requirements

### Requirement: Inbound authentication modes

The runner SHALL support two inbound authentication modes selected by configuration: `none`, which permits
every request without authenticating it, and `oidc`, which authenticates every request and authorizes it by
application role. `none` SHALL be the default, so an existing deployment's behavior is unchanged until it
opts in. The mode SHALL be resolved once at startup rather than per request.

In `oidc` mode at least one usable identity provider SHALL be configured, and startup SHALL fail otherwise
rather than silently accepting no credential. A provider is usable when it declares the JWKS endpoint
needed to verify tokens.

#### Scenario: Default mode permits requests without a credential

- **GIVEN** no security mode is configured
- **WHEN** a functional endpoint is requested with no credential
- **THEN** the request is served and no authentication is attempted

#### Scenario: OIDC mode without a usable provider fails startup

- **GIVEN** the security mode is `oidc` and no provider declares a JWKS endpoint
- **WHEN** the application starts
- **THEN** startup fails with an error naming the missing provider configuration

### Requirement: Bearer JWT authentication against configured identity providers

The runner SHALL authenticate a Bearer JWT in `oidc` mode by selecting the provider matching the token's
`iss` claim and verifying the token against that provider's JWKS, rejecting a token whose signature,
issuer, audience, or expiry does not validate. Several providers SHALL be configurable simultaneously, and a
token from an unconfigured issuer SHALL be rejected rather than accepted unverified.

The caller's provider role names SHALL be read from configurable claim paths, including nested paths, and
mapped onto the application roles `FULL_ADMIN` and `READ_ONLY_ADMIN` through a configurable mapping. A
provider's own mapping SHALL take precedence over the service-wide default mapping. A role with no mapping
entry SHALL be dropped rather than granted. A valid token SHALL authenticate even when it maps to no
application role; what it may then reach is decided by authorization.

Configuration SHALL reuse ADAS's environment-variable names and mapping shape so an operator's existing
ADAS configuration transfers unchanged, and `READ_ONLY_ADMIN` SHALL be accepted as a mapping target even
though it grants no access on the runner, so an ADAS-shaped mapping binds instead of failing startup on an
unknown role name.

#### Scenario: A valid token from a configured issuer authenticates

- **GIVEN** a provider is configured with an issuer and JWKS endpoint
- **WHEN** a request carries an unexpired Bearer JWT from that issuer with an accepted audience and a role
  mapping to `FULL_ADMIN`
- **THEN** the caller is authenticated and holds the `FULL_ADMIN` authority

#### Scenario: A token from an unconfigured issuer is rejected

- **WHEN** a request carries a Bearer JWT whose `iss` claim matches no configured provider
- **THEN** the request is rejected as unauthenticated and the token is never verified

#### Scenario: An expired or tampered token is rejected

- **WHEN** a request carries a Bearer JWT that is expired, carries an unaccepted audience, or fails
  signature verification
- **THEN** the request is rejected as unauthenticated

#### Scenario: An unmapped provider role grants no application role

- **GIVEN** a roles mapping that does not name the caller's provider role
- **WHEN** a request carries an otherwise valid Bearer JWT with only that role
- **THEN** the caller is authenticated with no application role

### Requirement: DIAL API-key authentication

The runner SHALL authenticate a DIAL `Api-Key` request header by introspecting the key against DIAL Core's
user-info endpoint and mapping the role names Core returns onto application roles. This SHALL be layered
onto `oidc` mode only and SHALL never activate when the mode is `none`, so enabling it cannot be the only
thing standing between an open instance and a closed one.

Core returns two key shapes and each SHALL use its own roles mapping: a project key, identified by a
project in the response, uses the API-key mapping; a per-request key rooted in a user JWT, identified by
user claims in the response, uses the service-wide default mapping, because it carries the same role names
that user would carry calling with a JWT directly.

A request carrying an `Authorization` header SHALL take precedence, with the `Api-Key` header ignored, so a
caller presenting both is authenticated exactly once and by the stronger credential. A request carrying
neither SHALL pass through unauthenticated to be resolved by authorization.

Successful introspections SHALL be cached under a bounded time-to-live and maximum size, keyed by a digest
of the key rather than the key itself, so a valid key reaches Core at most once per interval. A rejected key
SHALL NOT be cached, so revocation takes effect on the next request.

#### Scenario: A valid project key authenticates

- **GIVEN** API-key authentication is enabled and Core resolves the key to a project with a role mapped to
  `FULL_ADMIN`
- **WHEN** a request carries that key in the `Api-Key` header
- **THEN** the caller is authenticated with the `FULL_ADMIN` authority

#### Scenario: A JWT-rooted key uses the default roles mapping

- **GIVEN** Core resolves the key to user claims rather than a project
- **WHEN** a request carries that key in the `Api-Key` header
- **THEN** the returned role names are mapped through the service-wide default mapping

#### Scenario: An Authorization header takes precedence over an API key

- **WHEN** a request carries both an `Authorization` header and an `Api-Key` header
- **THEN** the Bearer token is authenticated and the API key is not introspected

#### Scenario: A key Core rejects is unauthorized

- **WHEN** a request carries a key that Core rejects
- **THEN** the request is rejected as unauthenticated and the result is not cached

#### Scenario: A valid key reaches Core at most once per cache interval

- **WHEN** the same valid key is presented on several requests within the cache time-to-live
- **THEN** Core is introspected once and the cached authentication serves the rest

#### Scenario: API-key authentication is inert when authentication is disabled

- **GIVEN** the security mode is `none` and API-key authentication is enabled
- **WHEN** the application starts
- **THEN** no API-key authentication component is active

### Requirement: Full-admin authorization on functional endpoints

The runner SHALL require the `FULL_ADMIN` application role on every functional endpoint under `/v1` in
`oidc` mode, reads included. There is deliberately no read-versus-write split: the runner holds no
per-target grants of its own, so it has no basis on which to authorize a narrower caller, and every
functional endpoint either mutates runtime state or reveals which rules and groups exist. A caller that
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

### Requirement: Operational endpoints require no credential

The runner SHALL serve its health, info, Prometheus scrape, and API-documentation paths without a
credential in every mode. Probes are the mechanism an orchestrator uses to decide whether the process is
alive and ready, so requiring a credential there would couple process supervision to the identity provider
and turn an authentication outage into a restart loop. Metrics carry identifiers, counts, and timings only —
never enrichment content — so scraping them needs no credential either.

Both health probe groups SHALL be reachable without a credential, not only the aggregate health endpoint,
so liveness and readiness probes are unaffected by enabling authentication.

#### Scenario: Probes answer without a credential while authentication is enforced

- **GIVEN** the security mode is `oidc`
- **WHEN** the aggregate health endpoint, the liveness probe group, or the readiness probe group is
  requested with no credential
- **THEN** each returns its status rather than an authentication failure

#### Scenario: Metrics are scrapeable without a credential

- **GIVEN** the security mode is `oidc`
- **WHEN** the Prometheus scrape endpoint is requested with no credential
- **THEN** the metrics are returned

### Requirement: Authentication and authorization failures use the ErrorView contract

The runner SHALL render an authentication or authorization failure as the same `ErrorView` shape every other
endpoint returns — `{status, error, message, path, method}` — with a stable machine code: `unauthorized` for
a missing, malformed, or rejected credential; `forbidden` for an authenticated caller lacking the required
role; and `service_unavailable` when a credential cannot be validated because DIAL Core is unreachable.

These responses are produced inside the security filter chain, before request handling, so they SHALL be
rendered there rather than relying on the application exception handler. A failure message SHALL NOT reveal
why a credential was rejected beyond the stable code, and SHALL NOT echo the credential.

Distinguishing an unreachable validator from a rejected credential is required rather than cosmetic: a
`401` tells an operator the caller is wrong, while a `503` tells them the runner cannot currently tell.

#### Scenario: A missing credential returns the standard error shape

- **WHEN** a `/v1` endpoint is requested with no credential
- **THEN** the response is `401` carrying the `ErrorView` shape with the `unauthorized` code

#### Scenario: An insufficient role returns the standard error shape

- **WHEN** an authenticated caller without `FULL_ADMIN` requests a `/v1` endpoint
- **THEN** the response is `403` carrying the `ErrorView` shape with the `forbidden` code

#### Scenario: An unreachable validator is distinguishable from a rejected credential

- **GIVEN** API-key authentication is enabled and DIAL Core is unreachable
- **WHEN** a request carries an `Api-Key` header
- **THEN** the response is `503` with the `service_unavailable` code rather than `401`

### Requirement: Inbound authentication posture stated at startup

The runner SHALL state its inbound authentication posture in a single factual startup log line, at warning
severity when authentication is disabled and at informational severity when it is enforced. Because the
permissive mode is the default, an unsecured instance is otherwise indistinguishable from a secured one in
a log aggregator, and a deployment that forgot to set the mode would look healthy.

The line SHALL name what is in effect — the mode, and when enforced the configured providers and whether
API-key authentication is active. It SHALL carry no rationale, deviation history, or follow-up roadmap:
those belong in this spec and `docs/configuration.md`, which are where a reader looks for *why* and which
cannot be acted on from a log.

#### Scenario: Disabled authentication is stated as a warning

- **GIVEN** the security mode is `none`
- **WHEN** the application starts
- **THEN** it logs a warning that inbound authentication is disabled and functional endpoints are open

#### Scenario: Enforced authentication names what is in effect

- **GIVEN** the security mode is `oidc`
- **WHEN** the application starts
- **THEN** it logs that authentication is enforced, naming the configured providers and whether API-key
  authentication is active, without restating rationale
