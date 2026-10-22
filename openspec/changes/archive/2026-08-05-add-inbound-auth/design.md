## Context

The runner exposes four controllers under `/v1` — rule admin, group admin, backfill, DLQ — with no inbound
authentication of any kind. ADAS, the service the runner is a client of, already solved this problem: it has
a `security.mode` switch, an OAuth2 resource server validating Bearer JWTs from several configured
providers, and a DIAL Core `Api-Key` filter layered on top, with provider roles mapped onto `FULL_ADMIN` /
`READ_ONLY_ADMIN`. That implementation lives in
`analytics-data-access-service/src/main/java/com/epam/aidial/analytics/web/security/` and is roughly 1,960
lines across 29 files.

Two constraints shape the design. First, the runner already mirrors ADAS deliberately — same Spring Boot
version, same Jackson 3 mapper configuration, an `ErrorView` whose constructor signature is already
identical — so the cheapest correct answer is a port, not a fresh design. Second, the runner's
authorization needs are strictly simpler than ADAS's: ADAS authorizes per table and per enrichment target
(`RuleAuthorizer`, `@PreAuthorize @tableAccess.can*`), whereas the runner holds no grants of its own and
every functional endpoint is an operator action. That means large parts of ADAS's machinery have no
counterpart here and porting them would add surface without adding safety.

## Goals / Non-Goals

**Goals:**

- Authenticate inbound callers the way ADAS does, so an operator configures one dialect rather than two and
  an existing ADAS configuration transfers unchanged.
- Close the confused-deputy exposure: no caller should be able to spend DIAL budget or pause a rule without
  presenting a credential.
- Land without touching the existing test suite or local development flow.
- Keep the two implementations diffable, so a future fix or CVE response in ADAS's security code can be
  applied here mechanically.

**Non-Goals:**

- Opaque (non-JWT) bearer token support. ADAS introspects those against a provider userinfo endpoint; the
  runner's realistic callers present JWTs or DIAL keys.
- Per-rule or per-target authorization on the runner. There is nothing to authorize against — the runner
  holds no grants; ADAS remains the enforcement point for what a rule may read and write.
- Any change to the runner's *outbound* identity. `ADAS_API_KEY` / `DIAL_API_KEY` and the sensitive-column
  posture recorded in `service-runtime` are unaffected.
- Authenticating the operational surface. Probes and metrics stay open by design (see Decisions).

## Decisions

### Port ADAS's implementation, mirroring its package layout

`web/security/**` and `web/security/apikey/**`, file-for-file with ADAS where the file survives the trim.
The alternative — writing a fresh, smaller implementation shaped to the runner — was rejected because the
subtle parts (multi-issuer decoder dispatch, nested role-claim extraction, Azure v1/v2 issuer expansion,
Core's `application/octet-stream`-labelled JSON) are exactly where a fresh implementation would introduce
bugs ADAS has already fixed.

This places `@ConfigurationProperties` holders in `web/security/` rather than `configuration/`, deviating
from the project's stated package convention. Accepted deliberately: cohesion with the ported code and
diffability against ADAS are worth more than convention here. Recorded in `openspec/config.yaml` `context:`
so it reads as a decision rather than an oversight.

### Default to `none`, and make that state loud

`security.mode` defaults to `none`, matching ADAS. The alternative, defaulting to `oidc`, is more secure on
paper but would require provider configuration for every local run and test-config changes across 17
`@SpringBootTest` suites — a materially larger and slower change, for a service that currently has no
ingress anywhere.

The cost of the permissive default is that a deployment can silently forget to enable authentication. The
mitigation is a startup log line at WARN when the mode is `none`, following the existing
`SensitivePostureLogger` pattern. That is a real mitigation rather than a gesture: an unsecured instance
becomes greppable in a log aggregator, which is how such a misconfiguration is actually found.

### `FULL_ADMIN` on every functional endpoint, reads included

ADAS splits reads (any mapped role) from mutations (`FULL_ADMIN`), because a read-only catalog consumer is a
real user of ADAS. The runner has no such user: its reads enumerate which rules and groups exist and why one
is stuck, which is operator information, and every write spends money or changes scheduling. A
`READ_ONLY_ADMIN` tier would therefore encode a distinction nobody needs and would have to be maintained.

`READ_ONLY_ADMIN` is nonetheless kept as a `UserRole` constant. It grants nothing, but its absence would
make an ADAS-shaped `SECURITY_ROLES_MAPPING` — which commonly names it — fail to bind, turning a
copy-pasted configuration into a startup failure with an opaque enum error.

### Authorize at the URL layer only; no method security

ADAS enables `@EnableMethodSecurity` and annotates mutations with `@FullAdminOnly`. The runner will not,
for a reason specific to it: db-scheduler executes scans, readiness sweeps, and worker items on background
threads with no `SecurityContext`. A `@PreAuthorize` on a service method reached from both an HTTP request
and a scheduled task would pass on the former and throw on the latter, and the failure would surface as
mysteriously dead background work rather than as a 403. Since every `/v1` path resolves to the same required
role, a single URL rule expresses the whole policy:

```
PUBLIC_PATHS      -> permitAll
/v1/**            -> hasAuthority(FULL_ADMIN)
anyRequest        -> denyAll
```

`denyAll` as the fallback means a future endpoint added outside `/v1` is closed until someone decides
otherwise, which is the correct default direction.

### Drop the raw provider-role authorities

ADAS carries each caller's raw provider role names as `PROVIDER_ROLE_`-prefixed authorities alongside the
mapped application roles, so per-table access can match on them. With no per-target authorization on the
runner they are unreachable state. Dropping them removes a concept from the ported `JwtAuthenticationConverter`
and `ApiKeyAuthenticationFilter` and keeps `ProviderRoleAuthority` and `SecurityClaimsExtractor` out of the
port entirely.

### API-key filter placement

The `Api-Key` filter runs *before* the Bearer filter, and returns early when an `Authorization` header is
present. That ordering gives the Bearer token precedence while letting either credential work, and means a
caller presenting both is introspected zero extra times against Core.

One non-obvious piece of ADAS's wiring must come across with it: a `FilterRegistrationBean` that *disables*
Boot's auto-registration of the filter. Because the filter is a `@Component`, Boot would also register it as
a top-level servlet filter, running it a second time outside the security chain. Omitting that bean is a
silent double-execution bug, so it is ported deliberately rather than incidentally.

### Introspection caching

Successful key introspections are cached in Caffeine under a digest of the key — never the key itself —
bounded by TTL and maximum size. Failures are not cached, so a revoked key stops working within one TTL
rather than at eviction. Caffeine is not currently on the runner's runtime classpath and must be added; it
is Spring Boot BOM-managed, so no version pin.

The trade-off is a revocation window of up to `API_KEY_CACHE_TTL_SECONDS` (default 60). Accepted: the
alternative is introspecting Core on every request, which couples the runner's request latency and
availability to Core far more tightly.

### Operational endpoints stay open

`/actuator/health/**`, `/actuator/info`, `/actuator/prometheus`, and the springdoc paths are `permitAll`,
matching ADAS. The decisive argument is failure mode, not convenience: authenticating a liveness probe makes
an identity-provider outage indistinguishable from a dead process, and Kubernetes resolves that ambiguity by
restarting the pod in a loop. Metrics are counters, gauges, and identifiers with no enrichment content, so
the exposure is a rule inventory rather than data.

Note that `/actuator/health/**` also matches `/actuator/health` itself under `PathPatternParser`, where
`/**` spans zero or more segments — so the aggregate endpoint and both probe groups are covered by the one
pattern.

### Render auth failures inside the filter chain

401 and 403 responses are produced by an `AuthenticationEntryPoint` and an `AccessDeniedHandler` that write
the `ErrorView` shape directly, because both fire inside the security filter chain before
`DefaultExceptionHandler` is reachable. A shared `ErrorViewWriter` serializes with the application's
Jackson 3 `JsonMapper` so the bodies are byte-comparable with every other error the service returns.

## Risks / Trade-offs

- **Permissive default ships an open service if a deployment forgets the flag** → Startup WARN naming the
  open state; `docs/configuration.md` states the production posture explicitly; a deployment without an
  ingress is not reachable from outside the cluster regardless.
- **`API_KEY_STARTUP_PROBE` defaults to `true`, so unreachable Core fails startup** → With a tight
  Kubernetes `startupProbe` this becomes a CrashLoopBackOff whose cause is Core, not the runner. Documented
  alongside the probe guidance, and `API_KEY_STARTUP_PROBE=false` disables it. Kept on by default because
  the alternative failure — every request 503ing at runtime — is harder to diagnose than a startup failure
  that names the endpoint.
- **Provider config uses relaxed binding with a silent footgun** → `PROVIDERS_AZURE_JWKSETURI` binds;
  `PROVIDERS_AZURE_JWK_SET_URI` binds to nothing, with no error. Ported verbatim into the docs. Partly
  self-mitigating: `oidc` mode fails startup when no usable provider results, which catches the common case
  of getting every variable wrong the same way.
- **`denyAll` fallback could refuse a path nobody considered** → The public list is ADAS's, already exercised
  there; an integration test asserts both probe groups, the aggregate health endpoint, and the scrape endpoint
  answer uncredentialed in `oidc` mode.
- **Revoked API key remains valid for up to one cache TTL** → TTL is configurable and defaults to 60s;
  failures are never cached.
- **The two implementations drift as ADAS's security code evolves** → Mirrored package layout and
  file names keep a directory diff meaningful; the deliberate omissions are enumerated here so a future
  reader can tell an intentional gap from a missed backport.
- **`FULL_ADMIN` on reads blocks a read-only operator** → No such consumer exists today. If one appears,
  the URL rule is the single place to change, and `READ_ONLY_ADMIN` is already a valid mapping target.

## Migration Plan

1. Ship with `SECURITY_MODE` unset. Deployment behavior is unchanged and no consumer needs updating; the
   startup WARN begins reporting the open state.
2. Per environment, configure at least one identity provider and set `SECURITY_MODE=oidc`. Optionally set
   `API_KEY_ENABLED=true` with `API_KEY_CORE_URL` for DIAL key callers.
3. Verify uncredentialed `/v1` returns 401 and both probe groups still return 200 before considering the
   environment done.
4. Outside local development, also set `SWAGGER_UI_ENABLED=false` and `API_DOCS_ENABLED=false`.

**Rollback:** unset `SECURITY_MODE` (or set it to `none`) and restart. The permit-all chain is selected by
the same property, there is no schema change, and no persisted state is involved — so rollback is a config
change, not a redeploy of different code.

## Open Questions

- Do any real callers present opaque bearer tokens? If so, ADAS's opaque path is a contained follow-up: the
  resource-server wiring changes from a single JWT `AuthenticationManager` to ADAS's
  `AuthenticationManagerResolver` split, and the four opaque classes port unchanged.
- Should `/actuator/prometheus` eventually require a credential? Left open rather than open-ended: the
  answer depends on whether the scrape path ever leaves the cluster network.
