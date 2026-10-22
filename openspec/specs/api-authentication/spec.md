# api-authentication Specification

## Purpose
How an inbound caller proves who they are and what they are then allowed to invoke: the two security modes,
Bearer authentication in both token shapes — a JWT verified locally and an opaque token introspected against
a provider's userinfo endpoint — DIAL API-key authentication, the mapping from provider roles onto
application roles, the full-admin rule covering every functional endpoint, the operational surface that
stays credential-free so probes cannot be gated on an identity provider, and the error contract for
authentication failures.

Scope is **inbound** requests only. The credentials the runner presents *outbound* to ADAS and DIAL, and the
sensitive-column entitlement they must carry, belong to `service-runtime`.
## Requirements
### Requirement: Inbound authentication modes

The runner SHALL support two inbound authentication modes selected by configuration: `none`, which permits
every request without authenticating it, and `oidc`, which authenticates every request and authorizes it by
application role. `none` SHALL be the default, so an existing deployment's behavior is unchanged until it
opts in. The mode SHALL be resolved once at startup rather than per request.

The configured mode SHALL be exactly one of those two values, and startup SHALL fail on any other — a
mistyped value, an empty one, or one differing only in case. Such a value selects neither mode's wiring, and
the framework's own default protection then applies to every path including the health probes, which an
orchestrator resolves by restarting the container indefinitely; a value differing only in case can also
enforce authentication while silently deactivating API-key authentication, since the two are guarded by
comparisons that do not agree on case.

In `oidc` mode at least one usable identity provider SHALL be configured, and startup SHALL fail otherwise
rather than silently accepting no credential. A provider is usable when it can verify a token by either
route: it declares both the issuer to match tokens on and the JWKS endpoint needed to verify them, or it
declares the userinfo endpoint against which an opaque token is introspected. Both kinds SHALL be
configurable simultaneously, and a provider declaring neither SHALL be ignored rather than failing startup.

A usable provider SHALL additionally be complete, and startup SHALL fail naming the provider and the
missing setting when it is not: a provider that would start cleanly and then refuse every caller uniformly
is indistinguishable at request time from a correctly configured provider that nobody is entitled to use.
Completeness SHALL be judged per provider kind, because the two kinds validate a token differently. A
provider verifying tokens against a JWKS SHALL accept at least one issuer value and declare at least one
accepted audience; a provider introspecting tokens against a userinfo endpoint SHALL require neither, since
it matches no issuer and performs no local audience check. Both kinds SHALL declare at least one role claim
path, and where that path names a custom role converter, the named converter SHALL exist and the provider
SHALL be one that introspects opaque tokens — a converter derives roles from a source reached with the
caller's token during introspection, so a provider verifying tokens locally never invokes one and would
instead read the converter's name as a claim path that matches nothing.

#### Scenario: Default mode permits requests without a credential

- **GIVEN** no security mode is configured
- **WHEN** a functional endpoint is requested with no credential
- **THEN** the request is served and no authentication is attempted

#### Scenario: An unrecognized mode fails startup

- **GIVEN** a configured security mode that is neither `none` nor `oidc`, including one differing only in case
- **WHEN** the application starts
- **THEN** startup fails with an error naming the mode variable and its permitted values

#### Scenario: OIDC mode without a usable provider fails startup

- **GIVEN** the security mode is `oidc` and no provider declares either an issuer with a JWKS endpoint or a
  userinfo endpoint
- **WHEN** the application starts
- **THEN** startup fails with an error naming the missing provider configuration and both routes

#### Scenario: A provider that could admit nobody fails startup

- **GIVEN** the security mode is `oidc` and a usable provider declares no role claim path, or verifies
  against a JWKS while declaring no accepted audience or an issuer that expands to no issuer value
- **WHEN** the application starts
- **THEN** startup fails with an error naming that provider and the missing setting

#### Scenario: An opaque provider needs no issuer or audience

- **GIVEN** the security mode is `oidc` and a provider declares a userinfo endpoint and a role claim path
  but no issuer and no audience
- **WHEN** the application starts
- **THEN** startup succeeds and that provider is available to introspect opaque tokens

#### Scenario: A custom role converter on a provider that verifies tokens locally fails startup

- **GIVEN** the security mode is `oidc` and a provider that declares no userinfo endpoint names a custom
  role converter as its role claim configuration
- **WHEN** the application starts
- **THEN** startup fails with an error naming that provider and stating that custom role converters apply
  only to providers introspecting opaque tokens

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

### Requirement: Bearer token shape selected per request

The runner SHALL accept both token shapes an identity provider may issue and SHALL choose between them per
request from the token itself, not from configuration: a Bearer credential that parses as a signed JWT
SHALL be verified locally against the matching issuer's JWKS, and any other Bearer credential SHALL be
treated as opaque and introspected. Neither choice SHALL depend on which provider the caller belongs to,
because an opaque token carries nothing that identifies its issuer.

The choice SHALL be made on the credential's shape alone and SHALL NOT fall back from one route to the
other. A credential that parses as a signed JWT is therefore never introspected, even when local
verification rejects it, so an identity provider issuing JWT-shaped access tokens SHALL be configured as a
JWT provider; declaring only its userinfo endpoint would leave its callers unable to authenticate.

Adding an opaque provider SHALL NOT change how a JWT is authenticated, so a deployment configuring only
JWT providers behaves exactly as it did before opaque support existed.

#### Scenario: A JWT is routed to local verification

- **GIVEN** both a JWT provider and an opaque provider are configured
- **WHEN** a request carries a Bearer credential that parses as a signed JWT
- **THEN** it is verified against the matching issuer's JWKS and no userinfo endpoint is called

#### Scenario: A non-JWT credential is routed to introspection

- **GIVEN** an opaque provider is configured
- **WHEN** a request carries a Bearer credential that does not parse as a signed JWT
- **THEN** it is introspected against a userinfo endpoint rather than rejected as malformed

#### Scenario: A JWT from an unconfigured issuer is not introspected

- **GIVEN** an opaque provider is configured and no JWT provider matches the credential's issuer
- **WHEN** a request carries a Bearer credential that parses as a signed JWT
- **THEN** the request is rejected without any userinfo endpoint being called

### Requirement: Opaque token introspection against a provider userinfo endpoint

The runner SHALL authenticate an opaque Bearer token in `oidc` mode by presenting it to a configured
provider's OIDC userinfo endpoint and reading the caller's identity from the claims returned. Because an
opaque token carries no issuer to select a provider by, each configured opaque provider SHALL be tried
until one accepts the token; a token no provider accepts SHALL be rejected as unauthenticated.

The claims returned SHALL supply the principal name, the caller's email, and the caller's provider role
names, read using the same configurable claim paths, email claim order, and principal claim the JWT path
uses. Those role names SHALL map onto application roles through the same mapping merge, with the provider's
own mapping taking precedence over the service-wide default and an unmapped role dropped rather than
granted. Where a requirement that a caller resolve to an email address is configured, it SHALL apply to an
opaque token as it does to a JWT.

The userinfo call SHALL carry a request timeout that is bounded and validated at startup, so an
unresponsive provider fails the request rather than holding the serving thread indefinitely. A timeout
value that would disable the bound SHALL fail startup naming the setting, rather than being accepted as a
request that may never return.

#### Scenario: An opaque token accepted by a provider authenticates

- **GIVEN** an opaque provider is configured with a userinfo endpoint and a roles mapping
- **WHEN** a request carries an opaque Bearer token that provider's userinfo endpoint accepts, and the
  claims returned carry a role mapping to `FULL_ADMIN`
- **THEN** the caller is authenticated and holds the `FULL_ADMIN` authority

#### Scenario: An opaque token no provider accepts is rejected

- **WHEN** a request carries an opaque Bearer token that every configured opaque provider's userinfo
  endpoint rejects
- **THEN** the request is rejected as unauthenticated

#### Scenario: A token the first provider rejects is offered to the next

- **GIVEN** two opaque providers are configured and only the second accepts the caller's token
- **WHEN** a request carries that token
- **THEN** the caller is authenticated with the accepting provider's claim, mapping and email settings

#### Scenario: An unmapped provider role from userinfo grants no application role

- **GIVEN** a roles mapping that does not name the role the userinfo response carries
- **WHEN** a request carries an otherwise valid opaque token
- **THEN** the caller is authenticated with no application role

#### Scenario: A required email applies to an opaque token

- **GIVEN** a resolvable email is required and an opaque provider whose userinfo response carries none of
  the configured email claims
- **WHEN** a request carries a token that provider accepts
- **THEN** the request is rejected as unauthenticated rather than authenticated without an email

#### Scenario: An unresponsive userinfo endpoint does not hang the request

- **GIVEN** a configured userinfo endpoint that does not respond within the configured timeout
- **WHEN** a request carries an opaque Bearer token
- **THEN** the introspection fails and the request is rejected rather than waiting indefinitely

#### Scenario: A timeout that would remove the bound fails startup

- **GIVEN** the security mode is `oidc` and the userinfo request timeout is configured to a value that
  would leave the call unbounded
- **WHEN** the application starts
- **THEN** startup fails with an error naming that setting

### Requirement: Custom role derivation for opaque providers

Where a provider's userinfo response carries no role information, the runner SHALL allow that provider to
derive the caller's provider role names from another source instead of from claim paths. A provider SHALL
select this by naming a converter as the single entry of its role claim configuration, distinguished from a
claim path by a reserved prefix; mixing a converter with claim paths SHALL NOT be supported. The names a
converter yields SHALL be mapped onto application roles by exactly the same mapping that claim-derived
names are.

The runner SHALL provide the converter that derives a Google caller's role names from their **direct**
Google Workspace group memberships, read with the caller's own token, using each group's identifier as a
role name. Nested memberships SHALL NOT grant roles, and only the first page of memberships SHALL be read,
with a truncated response recorded rather than silently accepted as complete.

A provider naming a converter that does not exist SHALL fail startup rather than each request, since a
converter name is fixed configuration rather than per-caller state.

#### Scenario: Group memberships become provider role names

- **GIVEN** an opaque provider whose role claim configuration names the Google Workspace group converter
- **WHEN** a request carries an opaque token whose caller belongs directly to a group the provider's roles
  mapping maps to `FULL_ADMIN`
- **THEN** the caller is authenticated and holds the `FULL_ADMIN` authority

#### Scenario: An unknown converter name fails startup

- **GIVEN** a provider whose role claim configuration names a converter that does not exist
- **WHEN** the application starts
- **THEN** startup fails with an error naming that provider, the unknown converter, and the known ones

#### Scenario: A caller whose email cannot be resolved is rejected

- **GIVEN** an opaque provider using the Google Workspace group converter
- **WHEN** a request carries a token whose userinfo response resolves to no email through the configured
  email claims
- **THEN** the introspection fails and the request is rejected as unauthenticated

### Requirement: Opaque introspection results are cached

The runner SHALL cache a successful opaque introspection under a bounded time-to-live and a bounded maximum
size, so a repeated call with the same token contacts the provider at most once per time-to-live. Cache
entries SHALL be keyed by a digest of the token rather than the token itself, so the credential is not held
in memory as a lookup key. A rejected token SHALL NOT be cached, so revocation takes effect on the next
request rather than persisting for the life of an entry.

This caches strictly more than ADAS does, which reintrospects on every request; the runner's callers are
operational tooling that polls the same endpoints repeatedly, and each miss costs a userinfo round trip and,
for a converter-derived provider, a second call to derive roles.

Cache misses SHALL share a bounded number of concurrent introspections. A miss that finds every permit in use
SHALL be rejected as unauthenticated without contacting any provider, and that refusal SHALL NOT be cached.
A rejection cache cannot bound a caller who sends a fresh random token per request, since every such token is
a new key; without the concurrency bound that caller decides how many outbound calls the runner makes and how
many serving threads wait on them. A token already cached SHALL be served regardless, so a flood of unknown
tokens cannot lock out a caller who has authenticated within the time-to-live.

#### Scenario: A repeated token is not reintrospected

- **GIVEN** an opaque token was successfully introspected
- **WHEN** the same token is presented again within the configured time-to-live
- **THEN** the caller is authenticated without contacting the provider again

#### Scenario: A rejected token is not cached

- **GIVEN** an opaque token that every provider rejected
- **WHEN** the same token is presented again
- **THEN** introspection is attempted again rather than served from the cache

#### Scenario: A miss beyond the concurrency bound is rejected without contacting a provider

- **GIVEN** every concurrent-introspection permit is held by an in-flight introspection
- **WHEN** a request carries an opaque token that is not cached
- **THEN** the request is rejected as unauthenticated, no provider is contacted, and the token is
  introspected normally once a permit is free

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
neither SHALL pass through unauthenticated to be resolved by authorization. An `Api-Key` header on a
credential-free operational path SHALL be ignored entirely rather than introspected: those paths are served
regardless of credential, so validating one there could only turn a probe or a metrics scrape into a
failure for a caller that happened to present a key the runner cannot accept.

Where a requirement that a caller resolve to an email address is configured, it SHALL apply to a
per-request key rooted in a user JWT, which carries the same claims that user's token would, and SHALL NOT
apply to a project key, which is a service identity with no user behind it and for which Core returns no
email — so the requirement stays compatible with project-key authentication rather than silently disabling
it.

Successful introspections SHALL be cached under a bounded time-to-live and maximum size, keyed by a digest
of the key rather than the key itself, so a valid key reaches Core at most once per interval. A rejected key
SHALL NOT be cached, so revocation takes effect on the next request. Cache misses SHALL share a bounded number
of concurrent introspections, for the reason given for opaque tokens; a miss beyond the bound SHALL be answered
as Core being unavailable, without contacting Core, and SHALL NOT be cached.

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

#### Scenario: A key miss beyond the concurrency bound does not reach Core

- **GIVEN** every concurrent-introspection permit is held by an in-flight introspection
- **WHEN** a request carries an `Api-Key` that is not cached
- **THEN** the request is answered as service unavailable and Core is not contacted

#### Scenario: An API key on a public path is ignored rather than validated

- **GIVEN** API-key authentication is enabled and Core is unreachable
- **WHEN** a credential-free operational path is requested with an `Api-Key` header
- **THEN** the path is served as usual and Core is not introspected

#### Scenario: A required email applies to a JWT-rooted key but not to a project key

- **GIVEN** callers are required to resolve to an email address
- **WHEN** a per-request key rooted in a user JWT resolves to no email, and separately a project key is
  presented
- **THEN** the JWT-rooted key is rejected as unauthenticated and the project key still authenticates

#### Scenario: API-key authentication is inert when authentication is disabled

- **GIVEN** the security mode is `none` and API-key authentication is enabled
- **WHEN** the application starts
- **THEN** no API-key authentication component is active

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

### Requirement: Operational endpoints require no credential

The runner SHALL serve its health, info, and Prometheus scrape paths without a credential in every mode. Probes are the mechanism an orchestrator uses to decide whether the process is
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

### Requirement: The API description requires full admin unless opened

In `oidc` mode the OpenAPI description and the interactive console rendered from it SHALL require the
`FULL_ADMIN` role, like the functional endpoints they describe, unless the operator sets the
swagger-authorization switch to serve them without a credential. The description maps every functional
endpoint and its parameters, which is reconnaissance an anonymous caller has no use for, so the open form is
an explicit choice rather than the default. The switch SHALL open only the description paths, never a
functional endpoint, and SHALL have no effect in `none` mode, where every path is already open.

The `Api-Key` filter SHALL treat the description paths as public exactly when the authorization rules do, so
a key presented there is introspected when the paths are protected and ignored when they are open.

#### Scenario: The description requires a credential by default

- **GIVEN** the security mode is `oidc` and the swagger-authorization switch is unset
- **WHEN** the OpenAPI description or the console is requested with no credential
- **THEN** the request is refused as unauthenticated

#### Scenario: A full admin may read the description

- **GIVEN** the security mode is `oidc` and the swagger-authorization switch is unset
- **WHEN** a caller holding `FULL_ADMIN` requests the OpenAPI description
- **THEN** it is served

#### Scenario: The switch opens the description and nothing else

- **GIVEN** the security mode is `oidc` and the swagger-authorization switch is set
- **WHEN** the OpenAPI description, the console, and a `/v1` endpoint are each requested with no credential
- **THEN** the description and the console are served and the `/v1` endpoint is refused as unauthenticated

### Requirement: Published API description declares the accepted credentials

The published OpenAPI description SHALL declare both credentials the runner accepts — the DIAL `Api-Key`
header and the `Authorization` Bearer token — as global security schemes, so the interactive API console
offers an authorization control and attaches the credential to try-it-out requests. A description that
declares no scheme leaves the console unable to send one, which makes every functional endpoint answer
`401` there the moment authentication is enforced.

The two schemes SHALL be declared as separate, independent requirements rather than one requirement listing
both, because either header alone authenticates a request. The advertised API-key header name SHALL be the
one the API-key filter reads, so the description cannot drift from the runtime.

The schemes are documentation only and enforce nothing, so they SHALL be declared in every security mode.

#### Scenario: The API-key header is advertised

- **WHEN** the OpenAPI description is retrieved
- **THEN** it declares an API-key security scheme carried in the `Api-Key` request header

#### Scenario: The bearer token is advertised

- **WHEN** the OpenAPI description is retrieved
- **THEN** it declares an HTTP bearer security scheme carried in the `Authorization` request header

#### Scenario: Either credential alone satisfies the description

- **WHEN** the OpenAPI description is retrieved
- **THEN** its global security is two independent requirements, one per scheme, rather than a single
  requirement demanding both

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
That distinction SHALL apply to the API-key path, whose introspection has exactly one validator to reach.
Opaque-token introspection is the stated exception: a token is offered to every configured provider in
turn, so no single provider's failure decides the outcome, and a token that no provider could be made to
accept — for any reason, unreachable providers included — SHALL be rendered `401`. This keeps the
multi-provider introspector identical to the one it is ported from, and is why an identity-provider outage
appears to an operator as rejected credentials rather than as a dependency error.

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

#### Scenario: An unreachable userinfo endpoint is rendered as a rejected credential

- **GIVEN** the only configured opaque provider's userinfo endpoint is unreachable
- **WHEN** a request carries an opaque Bearer token
- **THEN** the response is `401` with the `unauthorized` code

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

### Requirement: Rejected credentials do not drive log volume

The runner SHALL NOT emit a warning-or-higher log record for a credential it rejects. A rejection is driven
by whatever an unauthenticated caller chooses to send, so recording one at a level an operator is alerted
on lets that caller set the runner's log volume, and buries the records that do warrant attention. The
reason a credential was rejected SHALL remain available at debug level, where an operator investigating a
specific caller can turn it on.

This SHALL hold for every rejection route: an unrecognized issuer, a validly signed token whose audience or
issuer claim is not accepted, a claim path that matches nothing, a set of provider roles that maps to no
application role, a failed introspection, and an introspection refused for want of a concurrency permit.

#### Scenario: A token from an unrecognized issuer is not logged as a warning

- **WHEN** a request carries a signed JWT whose issuer matches no configured provider
- **THEN** the rejection is recorded below warning level

#### Scenario: A token for another audience is not logged as a warning

- **WHEN** a request carries a JWT signed by a configured issuer whose audience the provider does not accept
- **THEN** the rejection is recorded below warning level

#### Scenario: The rejection reason remains available

- **GIVEN** debug logging is enabled for the security package
- **WHEN** a credential is rejected
- **THEN** the reason is recorded
