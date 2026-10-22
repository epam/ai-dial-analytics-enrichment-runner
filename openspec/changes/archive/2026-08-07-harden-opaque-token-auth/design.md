## Context

`2026-08-07-add-opaque-token-auth` ported ADAS's opaque-token path into `web/security/`, which
deliberately mirrors ADAS file-for-file so the two stay diffable as ADAS evolves. A review of the shipped
port against ADAS's current security code found the port faithful — `MultiIssuerOpaqueTokenIntrospector`,
`OpaqueTokenCustomGrantedAuthoritiesConverters`, `OpaqueAuthorityExtractionContext`,
`OpaqueAuthenticationConverterFactory` and the `authenticationManagerResolver` bean are byte-comparable —
and the two divergences in `OpaqueAuthenticationConverter` match the change's own design decisions.

What remains are gaps around the port, in code the runner owns outright: startup validation, the typed
properties holder, one log level, one duplicated helper, and the documentation. The binding constraint on
all of it is that the ported classes stay unchanged, so every fix has to land either before the port
(startup validation, configuration) or beside it (documentation, tests).

## Goals / Non-Goals

**Goals:**

- No opaque-related configuration that authenticates a caller and then refuses them uniformly survives
  startup.
- Every requirement the `api-authentication` spec states about the opaque path has a test behind it.
- The behaviours an operator will actually hit — an identity-provider outage rendering `401`, a
  JWT-shaped access token never reaching introspection, the per-request cost of a garbage token — are
  written down where they are configured.

**Non-Goals:**

- Changing how a token is authenticated. No routing, introspection, converter or caching behaviour
  changes.
- Closing the two ADAS-inherited gaps in code (`401`-not-`503`, no fallback from JWT to introspection).
  Both are recorded in the spec as decisions instead.
- Negative caching. Rejections stay uncached, as the spec requires; only its cost is documented.
- Touching the API-key path beyond having it call the extracted digest helper.

## Decisions

**Validate the `fn:` selection for every provider, not only opaque ones.** `validateProvider` currently
runs the converter check under `if (config.isOpaqueProvider())`, so a JWT-only provider naming
`fn:getGoogleWorkspaceGroups` passes. The converter is only ever consulted by
`DefaultOpaqueTokenIntrospector`; on the JWT path `MultiPathGrantedAuthoritiesConverter` reads the string
as a dotted claim path, matches nothing, and hands back no roles — a clean start followed by `403` for
every caller. Alternative considered: teach the JWT path to run converters too. Rejected — a converter
derives roles by calling an API with the caller's *token* during introspection, which the JWT path has no
step for, and it would put runner-only behaviour on a path ADAS shares.

**Validate `OpaqueTokenProperties` in a compact constructor.** `requestTimeoutMs = 0` reaches
`SimpleClientHttpRequestFactory`, where `0` means *no timeout* — the precise failure the timeouts were
added to prevent, reachable by a value that reads like "as fast as possible". Negative values reach
`Duration.ofSeconds` and Caffeine, which fail startup with an `IllegalArgumentException` naming neither
the property nor the variable. `SecurityProperties` already validates itself this way and names
`SECURITY_MODE` in the message; this follows it. `ApiKeyProperties` does not validate its equivalents,
which is a gap on that side rather than a precedent to match. TTL `0` stays legal: `docs/configuration.md`
documents it as the way to disable caching, and the shipped test pins that behaviour.

**Document the `401`-on-outage contract rather than fixing it.** Rendering `503` would mean
distinguishing "every provider rejected the token" from "every provider was unreachable" inside
`MultiIssuerOpaqueTokenIntrospector`, the one class the port most needs to keep identical — and the
distinction is genuinely murky once more than one provider is configured, since a token can be rejected
by a reachable provider and merely unreached at another. The spec already required the distinction in the
general case, so it now records the opaque path as the stated exception; leaving it implicit is what
would make it an accident.

**Document the JWT-shape routing limitation rather than adding a fallback.** Falling back to
introspection when local verification finds no matching issuer would turn every garbage 3-part credential
into N outbound userinfo calls and would diverge the resolver from ADAS. The operator-facing fix is a
sentence saying which kind to configure a JWT-issuing provider as.

**Extract only the digest helper, not the cache.** `CachingOpaqueTokenIntrospector` and
`apikey/ApiKeyCache` share a byte-identical `sha256Hex` including its comment, and nothing else worth
sharing: different value types, different property sources, different log lines, and one is a Spring
component while the other is constructed by the `oidc` wiring. A `TokenDigest` utility removes the
duplication that exists without inventing an abstraction over the part that differs.

**Downgrade the unrecognized-issuer warning.** `MultiIssuerJwtDecoder` warns per request for a token it
cannot place. Before opaque support that was rare; now every JWS-shaped credential reaches it and an
opaque-only deployment has an empty issuer map, so an unauthenticated caller sets the WARN rate.
`MultiPathGrantedAuthoritiesConverter.extractPath` and `UserRolesResolver.resolve` already carry this
exact reasoning in comments — this makes the third case agree with them, and the spec states it once as a
rule so the next rejection route added does not have to rediscover it.

## Risks / Trade-offs

- **A deployment holding one of the two now-rejected configurations fails to start on upgrade.** →
  Neither configuration could authenticate anybody: a `fn:` converter on a JWT provider refused every
  caller with `403`, and a zero timeout only ever manifested as a stuck request. The startup message
  names the provider or the environment variable and what to set instead. Called out in the proposal's
  Impact section.
- **Downgrading the issuer warning hides a real misconfiguration** — an operator who mistyped `issuer`
  sees `401`s with nothing at INFO to explain them. → The startup posture line already names every
  configured provider by kind, which is where a missing or mistyped issuer shows up; and the per-request
  reason is one log level away. The alternative — leaving an unauthenticated caller able to fill the log
  aggregator — is the worse of the two.
- **Documenting the `401`-on-outage contract makes an awkward behaviour official.** → It is already the
  behaviour; the spec previously implied the opposite. Writing it down is what allows it to be revisited
  deliberately if ADAS ever changes it.
- **The new tests pin two runner divergences from ADAS** (`InvalidBearerTokenException` on a missing
  required email; `BadOpaqueTokenException` from an empty introspector list). → That is the intent: both
  are decisions from the previous change that currently nothing would catch being undone.
