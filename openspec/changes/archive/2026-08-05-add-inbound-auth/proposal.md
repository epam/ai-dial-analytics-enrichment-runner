## Why

The runner has **no inbound authentication**. `ADAS_API_KEY` and `DIAL_API_KEY` are outbound credentials —
what the runner presents *to* ADAS and DIAL — and nothing validates an incoming request: there is no
security dependency on the classpath and no credential is read from any request. The result is a confused
deputy. A caller supplies nothing, and the runner acts using its own ADAS identity, which is
`FULL_ADMIN`-entitled for sensitive-column rules. Anyone who can reach the port can start a backfill,
requeue a dead-letter item, or re-drive a group — each of which enqueues work that makes real DIAL model
calls and spends budget — or silence a rule with a pause.

Network isolation is the current de facto posture and was never a recorded decision: no spec addresses
inbound auth at all. Deploying the runner beyond local development forces the issue, and the runner should authenticate the
way ADAS already does rather than invent a second dialect.

## What Changes

- Add a `security.mode` switch with two modes: `none` (default, permit-all — preserves today's behavior)
  and `oidc` (enforce authentication and authorization). Not breaking: an existing deployment is unchanged
  until it opts in.
- Authenticate **Bearer JWTs** in `oidc` mode against one or more configured identity providers, verified
  locally against each provider's JWKS with issuer, audience, and expiry checks.
- Authenticate a **DIAL Core `Api-Key`** header, layered onto `oidc` mode only, by introspecting the key
  against Core's `/v1/user/info` and caching successful introspections. A request carrying an
  `Authorization` header takes precedence over `Api-Key`.
- Map provider role names onto application roles (`FULL_ADMIN`, `READ_ONLY_ADMIN`) via configurable JSON
  mappings, reusing ADAS's env-var names so an operator's ADAS configuration transfers unchanged.
- Require **`FULL_ADMIN` on every `/v1` endpoint**, reads included. The runner has no per-target
  authorization of its own, so there is no read/write split to make; `READ_ONLY_ADMIN` is accepted as a
  mapping target but grants nothing.
- Keep operational endpoints credential-free: `/actuator/health/**`, `/actuator/info`,
  `/actuator/prometheus`, and the springdoc paths stay public in `oidc` mode, so container and Kubernetes
  probes and metric scraping need no credential.
- Render authentication and authorization failures as the existing `ErrorView` contract, adding three
  stable machine codes: `unauthorized` (401), `forbidden` (403), and `service_unavailable` (503, when Core
  is unreachable during key introspection).
- State the inbound-auth posture in the startup log — at WARN when authentication is disabled — so an
  unsecured instance is visible in a log aggregator given the permissive default.

Deliberately **out of scope**: opaque (non-JWT) bearer tokens, which ADAS supports by introspecting a
provider userinfo endpoint. JWT plus DIAL `Api-Key` covers the runner's realistic callers; the wiring
leaves a seam to add opaque providers later.

## Capabilities

### New Capabilities
- `api-authentication`: how an inbound caller proves identity and what they are allowed to invoke — the
  two security modes, JWT and DIAL API-key authentication, role mapping, the full-admin authorization rule
  on functional endpoints, the credential-free operational surface, the error contract for auth failures,
  and the startup posture statement.

### Modified Capabilities

None. The `observability` spec's health-endpoint requirement and the `service-runtime` spec's endpoint and
`ErrorView` requirements all hold unchanged. The fact that probe and metrics paths stay reachable without
credentials is a property of authentication, so it is stated once in `api-authentication` rather than
duplicated as a second source of truth in `observability`.

## Impact

- **Dependencies**: adds `spring-boot-starter-security`,
  `spring-boot-starter-security-oauth2-resource-server`, and Caffeine (for the introspection cache, not
  currently on the runtime classpath). Test-only: `spring-boot-starter-security-test` and jjwt for minting
  locally-signed tokens.
- **New code**: `web/security/**` and `web/security/apikey/**`, mirroring ADAS's package layout so the two
  implementations stay diffable as ADAS evolves. This places `@ConfigurationProperties` holders outside
  `configuration/`, a deliberate deviation from the project's package convention.
- **Configuration**: a new `security.*` group and a `providers.<name>.*` map, using ADAS's env-var names
  (`SECURITY_MODE`, `SECURITY_ROLES_MAPPING`, `API_KEY_ENABLED`, `API_KEY_CORE_URL`, …). Documented in
  `docs/configuration.md`.
- **API surface**: no endpoint is added, removed, or renamed. In `oidc` mode every `/v1` endpoint gains
  401/403 responses.
- **Operations**: with `API_KEY_ENABLED=true`, the Core startup probe defaults to on and fails startup when
  Core is unreachable — relevant to how a Kubernetes startup probe is configured.
- **Existing tests**: unaffected. `security.mode` defaults to `none` and the permit-all chain keeps the
  current suites and local `bootRun` working without modification.
- **No migration**: the runner's PostgreSQL schema is untouched.
