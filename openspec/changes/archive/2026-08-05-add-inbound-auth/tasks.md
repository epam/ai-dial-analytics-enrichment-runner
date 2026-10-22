## 1. Dependencies and configuration

- [x] 1.1 Add `spring-boot-starter-security` and `spring-boot-starter-security-oauth2-resource-server` (Boot 4 artifact name) to `build.gradle` as BOM-managed `implementation` deps
- [x] 1.2 Add `com.github.ben-manes.caffeine:caffeine` (BOM-managed, not currently on the runtime classpath) for the introspection cache
- [x] 1.3 Add test deps: `spring-boot-starter-security-test`, `io.jsonwebtoken:jjwt-api:0.13.0`, and `jjwt-impl` / `jjwt-jackson` as `testRuntimeOnly`
- [x] 1.4 Add the `security:` block to `application.yml` — `mode`, `require-email`, `defaults.{roles-mapping,email-claim,principal-claim}`, `api-key.*` — as bare `key: ${ENV:default}` lines using ADAS's env-var names and default roles mapping. No `providers:` entry (map, bound externally)
- [x] 1.5 Confirm `./gradlew compileJava` still passes with security on the classpath and no filter chain defined yet

## 2. Shared security scaffolding

- [x] 2.1 Port `UserRole` (both constants; `READ_ONLY_ADMIN` documented as accepted-but-unprivileged on the runner) to `web/security/`
- [x] 2.2 Port `SecurityProperties` and `IdentityProvidersProperties` (with `ProviderConfig`, minus the `hasUserInfoEndpoint` opaque marker)
- [x] 2.3 Port `UserRolesResolver` and `UserSecurityDetails`
- [x] 2.4 Port `IdentityProviderUtils`, keeping the fail-fast on no usable provider and the Azure v1/v2 issuer expansion; drop the opaque-provider branch of the usability check
- [x] 2.5 Port `ErrorViewWriter`, `RestAuthenticationEntryPoint` (`unauthorized`/401) and `RestAccessDeniedHandler` (`forbidden`/403) — the runner's `ErrorView` constructor signature already matches ADAS's
- [x] 2.6 Port `NoneSecurityConfiguration` (permit-all chain, `@ConditionalOnProperty ... matchIfMissing = true`) so the default mode keeps existing behavior

## 3. JWT authentication path

- [x] 3.1 Port `TokenClaimsValidator` (issuer + audience binding)
- [x] 3.2 Port `MultiIssuerJwtDecoder` and `NimbusJwtDecoderResolver`
- [x] 3.3 Port `IssuerToDecoderMapFactory`, attaching the claims validator plus `JwtTimestampValidator` per provider
- [x] 3.4 Port `MultiPathGrantedAuthoritiesConverter` (nested dotted claim paths, empty authority prefix)
- [x] 3.5 Port `JwtAuthenticationConverter` and `JwtAuthenticationConverterFactory`, removing the raw provider-role block — mapped application roles only
- [x] 3.6 Do NOT port `ProviderRoleAuthority`, `FullAdminOnly`, `SecurityClaimsExtractor`, or any opaque-token class; record the omissions against `design.md`

## 4. API-key authentication path

- [x] 4.1 Port `IntrospectionResult` and `ApiKeyProperties`, preserving `ACTIVE_CONDITION` (`security.mode == 'oidc' and api-key.enabled`) and the `@PostConstruct` validation that rejects an enabled-but-unmapped configuration
- [x] 4.2 Port `CoreApiKeyIntrospector` — raw-string body read then parse with the application `JsonMapper` (Core labels JSON as `application/octet-stream`), project vs `userClaims` shapes, `BadCredentialsException` for rejection and `AuthenticationServiceException` for unreachable Core, plus the optional startup probe
- [x] 4.3 Port `ApiKeyCache` (Caffeine, SHA-256-digest keys, successes only) and `ApiKeyAuthorityResolver` (project-key mapping vs default mapping)
- [x] 4.4 Port `ApiKeyAuthenticationToken` and `ApiKeyAuthenticationFilter`, keeping the early return when `Authorization` is present and the 401/503 split; drop the raw provider-role authorities

## 5. Filter chain and posture logging

- [x] 5.1 Write `OidcSecurityConfiguration`: `@EnableWebSecurity`, `@ConditionalOnProperty(security.mode=oidc)`, CSRF disabled, stateless sessions, `.oauth2ResourceServer(o -> o.jwt(...))` with the issuer-dispatching converter — no `AuthenticationManagerResolver`, no `@EnableMethodSecurity`
- [x] 5.2 Add the URL rules: `PUBLIC_PATHS` (`/actuator/health/**`, `/actuator/info`, `/actuator/prometheus`, `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**`) permitAll; `/v1/**` `hasAuthority(FULL_ADMIN)`; `anyRequest().denyAll()`
- [x] 5.3 Register the API-key filter with `addFilterBefore(..., BearerTokenAuthenticationFilter.class)` and port the `FilterRegistrationBean` that disables Boot's auto-registration — without it the filter runs twice, outside the chain
- [x] 5.4 Wire the entry point and access-denied handler into `exceptionHandling` and into `oauth2ResourceServer`
- [x] 5.5 Add `SecurityPostureLogger` on `ApplicationReadyEvent`, mirroring `SensitivePostureLogger`: WARN when mode is `none`, INFO naming providers and API-key state when `oidc`; factual only, no rationale

## 6. Tests

- [x] 6.1 Port `JwtTestTokens` (HS256 minting, incl. expired) and `TestSecurityConfig` (HMAC `NimbusJwtDecoderResolver` replacing the JWKS one)
- [x] 6.2 `JwtAuthIntegrationTest`: 401 with no credential; 401 for malformed, expired, and unknown-issuer tokens; 403 for an authenticated caller with no mapped role and for `READ_ONLY_ADMIN` only; 200 with `FULL_ADMIN` — covering all four controllers
- [x] 6.3 `ApiKeyAuthIntegrationTest` (WireMock Core): project-key and `userClaims` paths; `Authorization` header wins over `Api-Key`; 401 on a rejected key; 503 on unreachable Core; one Core hit per cache TTL
- [x] 6.4 `PublicEndpointsAuthIntegrationTest`: in `oidc` mode, `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, and `/actuator/prometheus` all return 200 with no credential
- [x] 6.5 Port `CoreApiKeyIntrospectorTest`, `ApiKeyPropertiesTest`, and `ApiKeyAuthenticationFilterTest`
- [x] 6.6 Context test: `security.mode=oidc` with no usable provider fails startup with a message naming the missing provider config
- [x] 6.7 Assert 401 and 403 bodies carry the full `ErrorView` shape with `unauthorized` / `forbidden` codes, and that the message echoes no credential
- [x] 6.8 Confirm the existing suites still pass unmodified (mode defaults to `none`)

## 7. Documentation and spec maintenance

- [x] 7.1 Add an **Authentication** table to `docs/configuration.md` (`SECURITY_MODE`, `SECURITY_REQUIRE_EMAIL`, `SECURITY_ROLES_MAPPING`, `SECURITY_EMAIL_CLAIM`, `SECURITY_USER_CLAIM`) with an "Applied when" column, matching ADAS's layout
- [x] 7.2 Add an **Identity providers** section documenting `providers.<name>.*` by property path, including the relaxed-binding warning verbatim — dashes are dropped, not underscored (`PROVIDERS_AZURE_JWKSETURI`, not `PROVIDERS_AZURE_JWK_SET_URI`), and the wrong form binds silently to nothing
- [x] 7.3 Add an **API-key authentication** table (`API_KEY_ENABLED`, `API_KEY_CORE_URL`, `API_KEY_ROLES_MAPPING`, `API_KEY_CACHE_TTL_SECONDS`, `API_KEY_CACHE_MAX_SIZE`, `API_KEY_REQUEST_TIMEOUT_MS`, `API_KEY_STARTUP_PROBE`), noting the two key shapes and their separate mappings
- [x] 7.4 Add a **Security posture** note: `none` is the default and open; production sets `SECURITY_MODE=oidc`; `SWAGGER_UI_ENABLED=false` and `API_DOCS_ENABLED=false` outside local development; document the three new error codes (`unauthorized`, `forbidden`, `service_unavailable`)
- [x] 7.5 Amend the existing *Management / Actuator* section to state that probe and metrics paths stay credential-free in `oidc` mode, and flag that `API_KEY_STARTUP_PROBE=true` fails startup when Core is unreachable — relevant to Kubernetes startup-probe headroom
- [x] 7.6 Note in `openspec/config.yaml` `context:` that `web/security/**` mirrors ADAS's layout, so `@ConfigurationProperties` living outside `configuration/` reads as a decision
- [x] 7.7 Run `openspec validate add-inbound-auth --strict` and confirm the delta spec matches shipped behavior
- [x] 7.8 Run `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build` — compile, checkstyle (180-char lines), and the full suite must pass
