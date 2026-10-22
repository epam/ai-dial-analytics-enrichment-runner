## 1. Widen the provider model

- [x] 1.1 Add `userInfoEndpoint` to `IdentityProvidersProperties.ProviderConfig`, add `hasUserInfoEndpoint()`, and widen `isUsable()` to `(hasJwkSetUri() && isNotBlank(issuer)) || hasUserInfoEndpoint()`
- [x] 1.2 Replace the class javadoc paragraph declaring JWT-only support with the two-kinds description
- [x] 1.3 Fix the `new ProviderConfig(...)` call sites broken by the new record component — all six are in `IdentityProviderUtilsTest`; `TestSecurityConfig` configures providers through `DynamicPropertyRegistry` and so was unaffected

## 2. Split startup validation by provider kind

- [x] 2.1 Branch `IdentityProviderUtils.validateProvider` on kind: JWT keeps the accepted-issuer, audiences and role-claims checks; opaque requires role-claims only
- [x] 2.2 Reject an opaque provider naming an unknown `fn:` converter, listing the known ones (relocated from ADAS's `OidcSecurityConfiguration.checkCustomAuthoritiesConverter`)
- [x] 2.3 Restrict `validateIssuersAreDistinct` to JWT providers
- [x] 2.4 Update the "no usable identity providers" startup message to name the userinfo route alongside the JWKS route

## 3. Port the opaque authentication path

- [x] 3.1 Port `OpaqueAuthorityExtractionContext` verbatim
- [x] 3.2 Port `OpaqueTokenCustomGrantedAuthoritiesConverters` verbatim, with `fn:getGoogleWorkspaceGroups`
- [x] 3.3 Port `DefaultOpaqueTokenIntrospector` verbatim, keeping `IDP_CLAIM`
- [x] 3.4 Port `MultiIssuerOpaqueTokenIntrospector` verbatim
- [x] 3.5 Port `OpaqueAuthenticationConverter`, dropping the `ProviderRoleAuthority` loop and throwing `InvalidBearerTokenException` on a missing required email (design decisions 2 and 3)
- [x] 3.6 Port `OpaqueAuthenticationConverterFactory`

## 4. Add the introspection cache and timeouts

- [x] 4.1 Add `OpaqueTokenProperties` (`security.opaque-token`: `cacheTtlSeconds`, `cacheMaxSize`, `requestTimeoutMs`), modelled on `ApiKeyProperties`
- [x] 4.2 Add `CachingOpaqueTokenIntrospector` decorating the multi-issuer introspector: Caffeine, `expireAfterWrite` + `maximumSize`, SHA-256 token digest as key, successes only
- [x] 4.3 Build the introspection `RestClient` with a `SimpleClientHttpRequestFactory` carrying connect and read timeouts from `requestTimeoutMs`

## 5. Rewire the filter chain

- [x] 5.1 Add private static `jwtProviders()` / `opaqueProviders()` filters to `OidcSecurityConfiguration` and switch the existing JWT beans onto `jwtProviders()`
- [x] 5.2 Add the `jwtAuthenticationProvider`, `opaqueTokenIntrospector`, `opaqueAuthenticationConverterFactory` and `opaqueTokenAuthenticationProvider` beans
- [x] 5.3 Replace `.jwt(...)` with an `AuthenticationManagerResolver` routing on `SignedJWT.parse()`, leaving the URL rules, public paths, session policy, error handlers and api-key filter ordering untouched
- [x] 5.4 Update `SecurityPostureLogger` so the startup line distinguishes JWT from opaque providers

## 6. Configuration and documentation

- [x] 6.1 Add the `security.opaque-token.*` block to `application.yml` as bare `key: ${ENV:default}` lines
- [x] 6.2 `docs/configuration.md` § Authentication — remove the "opaque tokens are not supported" statement and describe per-request shape selection
- [x] 6.3 `docs/configuration.md` § Identity providers — add the `user-info-endpoint` row, the JWT-vs-opaque narrative, and the note that `issuer`/`audiences` do not apply to opaque providers
- [x] 6.4 `docs/configuration.md` — add § Custom role converters for opaque providers (converter table, Google example YAML, the four documented limits) and a table for `security.opaque-token.*`
- [x] 6.5 `docs/configuration.md` — verify the `API_KEY_STARTUP_PROBE` default. No change needed: the live doc already documents `false`; the `true` claim survives only in the archived `2026-08-05-add-inbound-auth` design, which is history and stays as written

## 7. Tests

- [x] 7.1 `DefaultOpaqueTokenIntrospectorTest` — userinfo success, empty body, non-2xx, `IDP_CLAIM` stamped, principal fallback
- [x] 7.2 `OpaqueTokenCustomGrantedAuthoritiesConvertersTest` — groups become role names, missing email rejected, `nextPageToken` warns, absent `memberships` yields none
- [x] 7.3 `CachingOpaqueTokenIntrospectorTest` — a repeat within the TTL makes no second call; a rejection is not cached
- [x] 7.4 `IdentityProviderUtilsTest` — opaque provider needs no audiences; unknown `fn:` name fails startup; JWT and opaque providers coexist; issuer-collision check ignores opaque providers
- [x] 7.5 `OpaqueAuthIntegrationTest` — opaque token mapping to `FULL_ADMIN` reaches a `/v1` endpoint; unmapped role gets 403; unknown token gets 401 in the `ErrorView` shape
- [x] 7.6 Assert a JWT still authenticates with an opaque provider configured alongside it, and that no userinfo call is made for it — covered in `OpaqueAuthIntegrationTest`, which configures both provider kinds, rather than in `JwtAuthIntegrationTest`, which deliberately configures only the JWT one
- [x] 7.7 `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build` passes (compile + checkstyle + tests)
