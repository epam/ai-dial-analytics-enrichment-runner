## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: Rejected credentials do not drive log volume

The runner SHALL NOT emit a warning-or-higher log record for a credential it rejects. A rejection is driven
by whatever an unauthenticated caller chooses to send, so recording one at a level an operator is alerted
on lets that caller set the runner's log volume, and buries the records that do warrant attention. The
reason a credential was rejected SHALL remain available at debug level, where an operator investigating a
specific caller can turn it on.

This SHALL hold for every rejection route: an unrecognized issuer, a claim path that matches nothing, a set
of provider roles that maps to no application role, and a failed introspection.

#### Scenario: A token from an unrecognized issuer is not logged as a warning

- **WHEN** a request carries a signed JWT whose issuer matches no configured provider
- **THEN** the rejection is recorded below warning level

#### Scenario: The rejection reason remains available

- **GIVEN** debug logging is enabled for the security package
- **WHEN** a credential is rejected
- **THEN** the reason is recorded
