## Why

The runner authenticates Bearer **JWTs** only. ADAS accepts a second token shape — an opaque
(non-JWT) access token, introspected against the identity provider's OIDC userinfo endpoint — so an
operator whose IDP issues opaque tokens (Google is the concrete case; Okta and Auth0 also do so in
some configurations) can reach ADAS but gets `401` from the runner with the same credential and the
same `providers.*` configuration block. That asymmetry was a recorded non-goal of
`2026-08-05-add-inbound-auth`, deferred there as a contained follow-up; this change closes it so one
operator configuration drives both services.

## What Changes

- A provider may now declare a `user-info-endpoint` instead of a `jwk-set-uri`, marking it an
  **opaque provider**. Both kinds may be configured side by side.
- Token shape is selected per request: a token that parses as a JWS goes down the existing JWT path;
  anything else is introspected against each opaque provider's userinfo endpoint until one accepts
  it. The claims returned supply the principal, email, and provider role names, which map onto
  application roles through the same `roles-mapping` merge the JWT path uses.
- A provider whose userinfo response carries no roles may name a **custom role converter** as the
  single `role-claims` entry, prefixed `fn:`. One converter ships: `fn:getGoogleWorkspaceGroups`,
  which derives role names from the caller's direct Google Workspace group memberships using the
  caller's own token. An unknown converter name fails startup.
- Startup validation is split by provider kind: an opaque provider needs no `issuer` and no
  `audiences` (there is no local audience check to perform), but still needs `role-claims`.
- Successful introspections are cached under a bounded TTL and maximum size, keyed by a digest of
  the token; rejected tokens are not cached. The userinfo call carries an explicit timeout. Neither
  has an ADAS counterpart — both are recorded divergences.
- No change to the credentials the runner presents **outbound**: `ADAS_API_KEY` and `DIAL_API_KEY`
  are untouched.
- Not breaking: a deployment configuring only JWT providers behaves exactly as before, and `none`
  mode is unaffected.

## Capabilities

### New Capabilities

None. This extends the existing inbound authentication contract rather than introducing a new one.

### Modified Capabilities

- `api-authentication`: the Bearer-token requirement widens from "JWT" to "either token shape, chosen
  per request"; two requirements are added for opaque introspection and for custom role derivation;
  the usable-provider and provider-completeness rules gain their opaque-provider forms.

## Impact

- **Code**: `web/security/` gains the six opaque classes ported from ADAS
  (`DefaultOpaqueTokenIntrospector`, `MultiIssuerOpaqueTokenIntrospector`,
  `OpaqueAuthenticationConverter`, `OpaqueAuthenticationConverterFactory`,
  `OpaqueAuthorityExtractionContext`, `OpaqueTokenCustomGrantedAuthoritiesConverters`) plus two
  runner-only ones (`OpaqueTokenProperties`, `CachingOpaqueTokenIntrospector`).
  `IdentityProvidersProperties`, `IdentityProviderUtils`, `OidcSecurityConfiguration`, and
  `SecurityPostureLogger` are modified. `MultiPathGrantedAuthoritiesConverter` needs no change — it
  was already left generic over `ClaimAccessor` for this port.
- **Configuration**: one new provider property (`providers.<name>.user-info-endpoint`) and one new
  group (`security.opaque-token.*`: cache TTL, cache max size, request timeout).
- **Dependencies**: none added. Caffeine and the OAuth2 resource-server starter are already present;
  the opaque support ships in the same starter as the JWT support.
- **APIs**: no endpoint changes. The credential-free operational surface is unchanged.
- **Runtime**: an opaque token costs one userinfo round trip on a cache miss, and the Google
  converter a second call to the Cloud Identity API.
