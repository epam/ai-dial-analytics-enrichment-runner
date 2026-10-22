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
path, and where that path names a custom role converter, the named converter SHALL exist.

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

## ADDED Requirements

### Requirement: Bearer token shape selected per request

The runner SHALL accept both token shapes an identity provider may issue and SHALL choose between them per
request from the token itself, not from configuration: a Bearer credential that parses as a signed JWT
SHALL be verified locally against the matching issuer's JWKS, and any other Bearer credential SHALL be
treated as opaque and introspected. Neither choice SHALL depend on which provider the caller belongs to,
because an opaque token carries nothing that identifies its issuer.

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

The userinfo call SHALL carry an explicit request timeout, so an unresponsive provider fails the request
rather than holding the serving thread indefinitely.

#### Scenario: An opaque token accepted by a provider authenticates

- **GIVEN** an opaque provider is configured with a userinfo endpoint and a roles mapping
- **WHEN** a request carries an opaque Bearer token that provider's userinfo endpoint accepts, and the
  claims returned carry a role mapping to `FULL_ADMIN`
- **THEN** the caller is authenticated and holds the `FULL_ADMIN` authority

#### Scenario: An opaque token no provider accepts is rejected

- **WHEN** a request carries an opaque Bearer token that every configured opaque provider's userinfo
  endpoint rejects
- **THEN** the request is rejected as unauthenticated

#### Scenario: An unmapped provider role from userinfo grants no application role

- **GIVEN** a roles mapping that does not name the role the userinfo response carries
- **WHEN** a request carries an otherwise valid opaque token
- **THEN** the caller is authenticated with no application role

#### Scenario: An unresponsive userinfo endpoint does not hang the request

- **GIVEN** a configured userinfo endpoint that does not respond within the configured timeout
- **WHEN** a request carries an opaque Bearer token
- **THEN** the introspection fails and the request is rejected rather than waiting indefinitely

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

#### Scenario: A repeated token is not reintrospected

- **GIVEN** an opaque token was successfully introspected
- **WHEN** the same token is presented again within the configured time-to-live
- **THEN** the caller is authenticated without contacting the provider again

#### Scenario: A rejected token is not cached

- **GIVEN** an opaque token that every provider rejected
- **WHEN** the same token is presented again
- **THEN** introspection is attempted again rather than served from the cache
