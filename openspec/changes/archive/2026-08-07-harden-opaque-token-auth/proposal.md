## Why

Opaque-token authentication shipped in `2026-08-07-add-opaque-token-auth` and the port from ADAS is
faithful, but a review of the shipped code found two configuration mistakes that still start cleanly and
then refuse every caller — the exact failure class the provider-completeness checks exist to prevent —
plus three requirements the spec states with no test behind them, and four operator-visible behaviours
that are correct as ported but written down nowhere. None of it is a defect at runtime today; all of it
is a support cost the first time an operator hits it.

## What Changes

- A `fn:` custom role converter selected on a provider that does **not** introspect opaque tokens fails
  startup. Today such a provider starts, the `fn:...` string is read as a dotted claim path, no role is
  ever extracted, and every caller of that provider is refused `403` with only a per-request debug line
  to say why.
- `security.opaque-token.*` values are bounds-checked at startup, naming the environment variable.
  `OPAQUE_TOKEN_REQUEST_TIMEOUT_MS=0` currently means *no timeout* (HttpURLConnection semantics),
  silently defeating the requirement that the userinfo call always be bounded; a negative TTL or maximum
  size currently fails with an unattributed `IllegalArgumentException` from Caffeine.
- The unrecognized-issuer rejection in `MultiIssuerJwtDecoder` drops from `warn` to `debug`. Every
  JWS-shaped credential now reaches it, so an unauthenticated caller drives WARN volume — the same
  reasoning already applied to `MultiPathGrantedAuthoritiesConverter` and `UserRolesResolver`.
- Three untested spec requirements gain tests: trying each opaque provider in turn until one accepts,
  `SECURITY_REQUIRE_EMAIL` applying to an opaque token, and an unresponsive userinfo endpoint failing
  the request rather than hanging it.
- Two ADAS-inherited behaviours are documented rather than changed, keeping the ported classes
  byte-comparable: an unreachable userinfo endpoint renders `401`, not the `503` the API-key path
  deliberately renders; and a credential that parses as a JWS always takes local verification, so an
  identity provider issuing JWT-shaped access tokens must be configured as a JWT provider.
- `CachingOpaqueTokenIntrospector` and `apikey/ApiKeyCache` stop carrying two byte-identical copies of
  the same SHA-256 helper.

No breaking changes: every new startup check rejects a configuration that could not have authenticated
anyone, and the only default that changes is a log level.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `api-authentication`: provider completeness gains the rule that a custom role converter is selectable
  only by a provider introspecting opaque tokens; the userinfo timeout becomes a validated bound rather
  than merely an explicit one; and the status an unreachable provider renders is stated on the record.

## Impact

- `web/security/IdentityProviderUtils`, `web/security/OpaqueTokenProperties`,
  `web/security/MultiIssuerJwtDecoder`, `web/security/CachingOpaqueTokenIntrospector`,
  `web/security/apikey/ApiKeyCache`, `web/security/DefaultOpaqueTokenIntrospector` (comment only), plus
  a new `web/security/TokenDigest`.
- Untouched by design, so they stay diffable against ADAS: `MultiIssuerOpaqueTokenIntrospector`,
  `OpaqueTokenCustomGrantedAuthoritiesConverters`, `OpaqueAuthorityExtractionContext`,
  `OpaqueAuthenticationConverterFactory`, and the `authenticationManagerResolver` bean.
- `docs/configuration.md` § Authentication, § Identity providers, § Custom role converters for opaque
  providers, § Opaque token introspection.
- Tests: two new unit test classes, three extended.
- No API, dependency, schema or deployment change. An existing deployment is unaffected unless it holds
  one of the two configurations that now fail startup.
