## 1. Startup validation

- [x] 1.1 `IdentityProviderUtils.validateProvider` — call `validateCustomAuthoritiesConverter` for every provider, not only opaque ones, passing the config so the check can see the kind
- [x] 1.2 `validateCustomAuthoritiesConverter` — reject a `fn:` selection on a provider that does not introspect opaque tokens, naming the provider and the property; keep the existing unknown-name branch unchanged
- [x] 1.3 `validateIssuersAreDistinct` — rename the `usable` parameter to `jwtProviders` and reword the javadoc's first sentence, which still describes any two providers though it now receives only JWT ones

## 2. Configuration bounds

- [x] 2.1 `OpaqueTokenProperties` — compact constructor rejecting `cacheTtlSeconds < 0`, `cacheMaxSize < 0` and `requestTimeoutMs <= 0`, each message naming the environment variable, in the style of `SecurityProperties`
- [x] 2.2 Record in the javadoc why `0` is legal for the TTL (disables caching, documented) and illegal for the timeout (`SimpleClientHttpRequestFactory` reads it as no timeout)

## 3. Logging and duplication

- [x] 3.1 `MultiIssuerJwtDecoder` — unrecognized-issuer rejection from `warn` to `debug`, with the justification comment `MultiPathGrantedAuthoritiesConverter` and `UserRolesResolver` already carry
- [x] 3.2 New `web/security/TokenDigest` holding the single `sha256Hex(String)`
- [x] 3.3 `CachingOpaqueTokenIntrospector` and `apikey/ApiKeyCache` delegate to it and drop their own copies
- [x] 3.4 `DefaultOpaqueTokenIntrospector` — one-line comment marking the `principalClaim != null` guard as a deliberate divergence from the otherwise verbatim ADAS port

## 4. Tests

- [x] 4.1 New `MultiIssuerOpaqueTokenIntrospectorTest` — the second provider accepts after the first throws; every provider rejecting yields `BadOpaqueTokenException`; an empty introspector list yields `BadOpaqueTokenException`
- [x] 4.2 New `OpaqueAuthenticationConverterTest` — required email absent yields `InvalidBearerTokenException` (not `AuthenticationServiceException`); email resolved in `emailClaims` order onto `UserSecurityDetails`; unmapped provider roles yield no authority
- [x] 4.3 New `OpaqueTokenPropertiesTest` — the three bounds, and that TTL `0` is accepted
- [x] 4.4 `IdentityProviderUtilsTest` — a `fn:` converter on a JWT-only provider fails startup
- [x] 4.5 `OpaqueAuthIntegrationTest` — a userinfo endpoint delayed past the configured timeout renders `401` rather than hanging; and correct the class javadoc, which claims to cover the introspection cache while the test sets `cache-ttl-seconds=0`

## 5. Documentation

- [x] 5.1 `docs/configuration.md` § Authentication — a credential that parses as a signed JWT is never introspected, so a provider issuing JWT-shaped access tokens must be configured as a JWT provider
- [x] 5.2 `docs/configuration.md` § Opaque token introspection — an unreachable userinfo endpoint renders `401`, unlike the API-key path's `503`; and the per-request cost of a token no provider accepts (N round trips, each bounded by the timeout)
- [x] 5.3 `docs/configuration.md` — the `OPAQUE_TOKEN_REQUEST_TIMEOUT_MS` row states it must be greater than zero and that startup fails otherwise
- [x] 5.4 `docs/configuration.md` § Custom role converters — a `fn:` converter is opaque-only and fails startup on a JWT provider; and a Google provider should set its own `email-claims`, because the service-wide `SECURITY_EMAIL_CLAIM` default is Azure-shaped and absent from a Google userinfo response

## 6. Close out

- [x] 6.1 `openspec validate harden-opaque-token-auth --strict` passes
- [x] 6.2 `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build` passes (compile + checkstyle + tests)
- [x] 6.3 Sync the delta into `openspec/specs/api-authentication/spec.md` and archive
