## Context

`2026-08-05-add-inbound-auth` ported ADAS's inbound authentication into `web/security/`, deliberately
mirroring ADAS's file layout so the two packages stay diffable. It ported the **JWT half** and recorded
the opaque half as a non-goal with a stated follow-up shape: *"the resource-server wiring changes from a
single JWT `AuthenticationManager` to ADAS's `AuthenticationManagerResolver` split, and the four opaque
classes port unchanged."* That estimate holds; this change executes it.

The seam was left open on purpose. `MultiPathGrantedAuthoritiesConverter` is already generic over
`ClaimAccessor` rather than `Jwt`, with a javadoc saying so, precisely so the same role extraction serves
userinfo attributes. `NimbusJwtDecoderResolver` already isolates decoder construction for tests.

Constraint that shapes every decision below: `openspec/config.yaml` records that `web/security/` mirrors
ADAS file-for-file "so the two stay diffable as ADAS evolves". Divergence is allowed but must be
deliberate, minimal, and documented at the point of divergence.

## Goals / Non-Goals

**Goals:**

- Accept every token shape ADAS accepts, from a byte-identical `providers.*` configuration block, so one
  operator configuration drives both services.
- Ship `fn:getGoogleWorkspaceGroups`, without which a Google provider authenticates but resolves no roles
  and is therefore useless — the converter is part of "supporting Google", not an extra.
- Keep the JWT path's observable behavior unchanged.

**Non-Goals:**

- **Any change to the runner's outbound identity.** `ADAS_API_KEY` / `DIAL_API_KEY` stay static `Api-Key`
  headers. ADAS has no client-credentials flow either (no `oauth2-client` on either classpath), so this is
  already at parity; changing it is a separate concern with its own rotation and caching questions.
- Per-target authorization on the runner. Every `/v1` endpoint stays `FULL_ADMIN`-gated at the URL layer.
- Retaining raw provider role names as authorities. See Decision 2.
- Additional `fn:` converters beyond the one ADAS ships.
- Authenticating the operational surface. Probes and metrics stay open by design.

## Decisions

### 1. Port ADAS's classes near-verbatim rather than designing fresh

The six opaque classes keep ADAS's names, package position, and internal structure — including
`IDP_CLAIM = "idp"` as the synthetic attribute that carries the provider name from introspector to
converter. Two implementations of the same protocol that drift in structure stop being diffable, and the
diffability is the stated reason this code lives in `web/security/` instead of `configuration/`.

*Alternative considered:* express the opaque path as a single `OpaqueTokenIntrospector` bean with the
per-provider loop inlined. Fewer files, but it collapses the introspector/converter split that ADAS uses
to keep role mapping out of the HTTP call, and it makes the next ADAS change hard to follow across.

### 2. Do not port `ProviderRoleAuthority`

ADAS attaches each raw provider role as a `PROVIDER_ROLE_`-prefixed authority so per-table access
evaluation can match on it. The runner has no per-target grants — the whole `/v1` surface is one
`FULL_ADMIN` rule — so those authorities would be state nothing reads. The JWT path already made this call
and documented it in `JwtAuthenticationConverter`'s javadoc; the opaque path follows it so the two runner
paths agree with each other. Should per-target authorization ever arrive, both paths change together.

### 3. Throw `InvalidBearerTokenException`, not `AuthenticationServiceException`, on a missing email

ADAS's `OpaqueAuthenticationConverter` throws `AuthenticationServiceException` when `require-email` is set
and no email resolves. The runner's `JwtAuthenticationConverter` deliberately throws
`InvalidBearerTokenException` instead: a token missing a required claim is the caller's problem, not a
failure of this service, and the runner's api-key filter maps a service exception to `503`. Both render
`401` through the entry point, but the distinction matters where the two are told apart. The opaque path
takes the runner's existing choice so the two runner paths do not disagree about what a missing claim means.

### 4. Cache successful introspections; do not cache rejections

ADAS reintrospects on every request. That is defensible for ADAS's query traffic; it is worse here,
because the runner's callers are operational tooling polling `/v1/dlq`, `/v1/backfills/{id}`, and
`/v1/rules/cache` on a loop, and a converter-derived provider costs *two* round trips per request (userinfo
plus Cloud Identity), on the request thread.

`web/security/apikey/ApiKeyCache` already implements exactly the contract needed — Caffeine,
`expireAfterWrite`, bounded `maximumSize`, keyed by a SHA-256 digest so the credential is never a map key,
successes only so revocation lands within the TTL. `CachingOpaqueTokenIntrospector` reuses that shape as a
decorator over `MultiIssuerOpaqueTokenIntrospector`, which keeps the ported class itself verbatim and
confines the divergence to one new file.

*Alternative considered:* no cache, for exact ADAS parity. Rejected — the cost profile genuinely differs,
and the decorator keeps the ported code untouched, so the diff against ADAS stays readable.

### 5. Give the userinfo `RestClient` explicit timeouts

ADAS uses a bare `RestClient.create()`, which has no connect or read timeout: a userinfo endpoint that
accepts the connection and never answers pins a servlet thread until the container gives up. The runner's
`CoreApiKeyIntrospector` already sets `SimpleClientHttpRequestFactory` timeouts for the structurally
identical DIAL Core introspection call. Doing the same here is consistency with the runner's own code, not
a new pattern.

### 6. Split completeness validation by provider kind, not by widening `isUsable()`

`IdentityProviderUtils.validateProvider` currently demands `audiences` and `role-claims` of every provider.
`audiences` is meaningless for an opaque provider — there is no local `aud` check to feed — so requiring it
would make every ADAS-shaped opaque provider fail startup on the runner, which is exactly the config
portability this change exists to deliver. Validation therefore branches on kind, and
`validateIssuersAreDistinct` runs over JWT providers only, since opaque providers match no issuer.

`isUsable()` itself widens to "can verify a token by either route", matching ADAS's own notion. The
narrower completeness rules stay separate from it, as they already are, because `isUsable()` is a shared
name whose meaning should not shift.

### 7. Validate `fn:` converter names at startup, in `IdentityProviderUtils`

ADAS performs this check in `OidcSecurityConfiguration.checkCustomAuthoritiesConverter`. The runner already
concentrates provider-completeness fail-fast in `IdentityProviderUtils` (that is where the audience and
role-claim checks live, with a javadoc explaining why misconfiguration must fail at startup rather than
per request). Putting the converter check beside them keeps one place to look for "why did startup reject
my provider" — a small, deliberate relocation rather than a behavior change.

## Risks / Trade-offs

- **A cached introspection outlives a revoked token by up to the TTL** → Same trade-off the API-key path
  already makes, with the same 60-second default and the same "successes only" rule. Operators who need
  immediate revocation set the TTL to zero.
- **The Google converter reads only the first page of memberships** → Inherited from ADAS and from the
  `ai-dial-admin-deployment-manager-backend` original. A truncated response logs a warning; the documented
  guidance is to map roles onto a few dedicated groups rather than relying on a caller's full group list.
- **Opaque providers are tried in unspecified order** → ADAS iterates a `List` built from a `HashMap`, so
  its javadoc's "configuration order" is not actually guaranteed. Consequence is only which provider is
  contacted first; a token is accepted by at most one. Left as-is for diffability, and noted here so the
  next reader does not mistake it for a runner bug.
- **Every non-JWT Bearer credential now triggers outbound calls** → Before this change a malformed
  `Authorization: Bearer <garbage>` was rejected locally; now it is introspected against each opaque
  provider. Only when at least one opaque provider is configured, and bounded by the request timeout — but
  it is an unauthenticated caller's ability to cause outbound traffic. The cache does not help, since
  failures are not cached. Accepted: it is inherent to opaque tokens and identical to ADAS's exposure.
- **Adding a record component to `ProviderConfig` breaks every construction site** → Compile-time
  breakage in `IdentityProviderUtilsTest` and `TestSecurityConfig` only, caught by `-Werror`; mechanical.

## Migration Plan

No migration. The new provider property is additive and the new config group has working defaults; a
deployment configuring only JWT providers is byte-for-byte unaffected, and `none` mode never reaches this
code. Rollback is redeploying the prior image — nothing persists.

## Open Questions

- Should the JWT path's `NimbusJwtDecoder` JWKS cache be configured explicitly rather than left at Nimbus
  defaults? Out of scope here; noted because this change makes the runner's two remote-key-material paths
  visible side by side for the first time, and only one of them has explicit tuning.
- Should `/actuator/prometheus` eventually require a credential? Carried forward unresolved from
  `2026-08-05-add-inbound-auth`; the answer still depends on whether the scrape path leaves the cluster.
