# Exposing the Enrichment Runner from a cluster (Kubernetes / Traefik)

This guide covers three things, in descending order of how often you need them:

1. **Reaching the ops API without exposing it** — the default and recommended posture.
2. **The egress the pod needs** — always applies, exposure or not.
3. **An ingress, if an outside caller genuinely needs one** — with the path allowlist that limits it.

Unlike ADAS, the runner has **no consumer-facing read surface to publish**. Its entire `/v1` surface is an
operator control plane (pause a pipeline, start a backfill, requeue a dead-letter item), and several of those
controls make real DIAL model calls under the runner's own key — real spend. The exposure decision is
therefore not "which read endpoints do we publish" but "does anything outside the cluster need to drive
this at all". Usually nothing does: an operator port-forwards, or an in-cluster admin backend calls the
Service directly. The endpoint-by-endpoint allowlist is the Ingress path list under
[Ingress](#ingress) below; environment variables named inline are described in
[`configuration.md`](configuration.md).

The examples assume a reference layout: a Helm-managed deployment (for example via the `dial-extension`
chart) with namespace and Service `dial-analytics-enrichment-runner` (ClusterIP, port 80 → container port
`http` = `HTTP_PORT`, default `5000`), the runner's PostgreSQL in-namespace, ADAS in its own `dial-adas`
namespace, and the hostname `runner.example.com`. Substitute your own names. The ingress controller is
**Traefik** with two ingress classes:

- `traefik` — public LoadBalancer
- `traefik-whitelist` — IP-whitelisted LoadBalancer (**start here, and for this service stay here**)

TLS is terminated at Traefik's `443` entrypoint. Certificates are issued **per-ingress** by cert-manager
(the `letsencrypt-production` ClusterIssuer) and DNS records are created **per-ingress** by external-dns —
so each Ingress carries a `cert-manager.io/cluster-issuer` annotation and a `tls.secretName`, and the
hostname drives both the certificate and the DNS record.

## Traffic flow

```
operator ──kubectl port-forward──────────────────────> pod :5000      (default: no ingress at all)
admin backend (in-cluster) ──HTTP──> Service dial-analytics-enrichment-runner:80 ──> pod :5000
client ──HTTPS/443──> Traefik LB ───HTTP──> Service dial-analytics-enrichment-runner:80 ──> pod :5000
```

One HTTP listener, one hostname. The runner has no second protocol listener — nothing corresponds to
ADAS's Arrow Flight SQL gRPC route, so there is no h2c Service, no dedicated hostname, and no second
certificate to manage.

The runner runs **single-replica** (`N>1` is not a running configuration — the DIAL rate limiter, the
pipeline-pause registry, and the DLQ breaker counters are per-process). An ingress therefore needs no session
affinity: there is one pod to route to.

## Reaching the ops API without an ingress (preferred)

```bash
kubectl -n dial-analytics-enrichment-runner port-forward svc/dial-analytics-enrichment-runner 5100:80

curl -s localhost:5100/actuator/health/readiness   # UP once a pipeline sync has succeeded
curl -s localhost:5100/v1/pipelines/cache         # synced pipeline cache
curl -s localhost:5100/v1/dlq                     # dead-letter items
```

With `SECURITY_MODE=oidc` these calls need a credential like any other — port-forwarding bypasses the
ingress, not the runner's own authentication:

```bash
curl -s -H "Authorization: Bearer $TOKEN" localhost:5100/v1/pipelines/cache
curl -s -H "Api-Key: $DIAL_KEY"           localhost:5100/v1/pipelines/cache   # when API_KEY_ENABLED=true
```

An in-cluster caller (an admin backend, a CronJob that triggers backfills) skips the port-forward and calls
`http://dial-analytics-enrichment-runner.dial-analytics-enrichment-runner.svc.cluster.local/v1/...`
directly (`<service>.<namespace>.svc.cluster.local` — the two are the same word here). Neither route needs
an Ingress, a certificate, or a DNS record.

## Prerequisites for exposing it

1. **Authentication enabled.** `SECURITY_MODE` defaults to `none`, which permits every request — and an
   open runner means any caller that can reach the port can start a backfill (real model calls, real spend)
   or pause a pipeline, acting through the runner's own ADAS and DIAL identity. Set `SECURITY_MODE=oidc` with
   at least one identity provider configured, and optionally `API_KEY_ENABLED=true` plus `API_KEY_CORE_URL`
   for DIAL Core API keys. Startup fails if `oidc` has no usable provider, so a half-finished configuration
   cannot silently serve open endpoints.
2. **API docs closed.** In `oidc` mode the OpenAPI description and Swagger UI require `FULL_ADMIN` unless
   `DISABLE_SWAGGER_AUTHORIZATION=true`; leave that unset. Set `SWAGGER_UI_ENABLED=false` and
   `API_DOCS_ENABLED=false` as well unless API exploration through the route is intended.
3. **A spend budget, if the backfill paths are routed.** Nothing caps DIAL spend by default: the gate is
   off unless `SPEND_PAUSE_ENABLED=true` with at least one non-zero `SPEND_BUDGET_DAILY_*`. A routed
   `/v1/pipelines` prefix admits `POST /v1/pipelines/{name}/backfill`, and a bulk DLQ requeue fans out
   into many model calls at once.
4. **Posture confirmed in the log.** The runner logs the posture in effect at startup, at **WARN** when
   authentication is disabled. Grep for that line in the deployed pod before routing anything to it; an
   instance that meant to enable authentication and did not is otherwise indistinguishable from one that
   did.

## Deployment environment

If the deployment is Helm-managed, apply these through the release's Helm values
rather than `kubectl edit`:

```yaml
env:
  - { name: SECURITY_MODE, value: "oidc" }
  - { name: SWAGGER_UI_ENABLED, value: "false" }
  - { name: API_DOCS_ENABLED, value: "false" }
ports:
  - { name: http, containerPort: 5000 }
```

Probes point at the health groups, never at the aggregate `/actuator/health` — liveness at
`/actuator/health/liveness`, readiness and startup at `/actuator/health/readiness` with headroom for the
first ADAS pipeline sync. See the probe table in [`configuration.md`](configuration.md#health-probes).

## Ingress

The existing `dial-analytics-enrichment-runner` Service (port 80 → `http`) fronts the API unchanged; the
Ingress reuses it. The declared paths are the exposure allowlist: anything not listed — `/actuator`, the
springdoc paths — is 404 at Traefik and never reaches the pod.

Note what a path list can and cannot do here. `/v1/pipelines` admits `GET /v1/pipelines/cache` **and**
`POST /v1/pipelines/{name}/backfill`: an Ingress routes by path, not by method, and the runner requires
`FULL_ADMIN` for reads and writes alike. Drop a prefix entirely if its callers do not need it — routing
`/v1/dlq` alone is a coherent exposure, routing "only the reads under `/v1/pipelines`" is not.

**Two services now speak `/v1/pipelines`, and that is a legibility problem rather than a routing one.**
Each has its own hostname and its own Ingress, so nothing is ambiguous to Traefik — but a reader looking at
a bare path cannot tell which service owns it. The split, on the same noun:

| Verb | Host | What it does |
|---|---|---|
| `POST /v1/pipelines`, `PATCH`/`DELETE /v1/pipelines/{name}` | ADAS (`adas.example.com`) | Registers and edits a pipeline of either kind. The registry lives there; the runner is a read-only consumer of it. |
| `GET /v1/pipelines?kind=enrich&enabled=true` | ADAS | The runner's own sync feed. Not something an operator routes. |
| `POST /v1/pipelines/{name}/preview`, `/refresh` | ADAS | Dry-runs and drives an `aggregate` pipeline. |
| `POST /v1/pipelines/{name}/scan`, `/group-rows`, `/materialize`, `/rows` | ADAS | The enrichment data plane the runner calls. Not something an operator routes either. |
| `GET /v1/pipelines/cache`, `/paused` | **this runner** | What this runner has synced, and what it has paused. |
| `POST /v1/pipelines/{name}/pause`, `/resume`, `/backfill` | **this runner** | Operator control of this runner's execution. |
| `GET /v1/pipelines/{name}/groups`, `POST …/groups/{key}/requeue` | **this runner** | Group state this runner owns. |
| `GET /v1/pipelines/{name}/runtime` | **this runner** | What one pipeline is doing right now: status, schedule, progress, queue, groups, spend and dead-letter counts. Not ADAS's `GET /v1/pipelines/{name}`, whose `state` carries ADAS's own positions. |

The same division held before the fold, when the noun was `rules` — `scan` was ADAS's and `pause` was the
runner's. What changed is only that the shared word now makes it worth writing down.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: dial-analytics-enrichment-runner
  namespace: dial-analytics-enrichment-runner
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-production   # cert-manager issues the cert; external-dns creates the DNS record
spec:
  ingressClassName: traefik-whitelist   # keep the whitelist class: the callers are named operators, not the internet
  tls:
    - secretName: dial-analytics-enrichment-runner-tls
      hosts:
        - runner.example.com
  rules:
    - host: runner.example.com
      http:
        paths:
          - path: /v1/pipelines        # pipeline cache + pause/resume + group state + runtime view + backfill trigger
            pathType: Prefix
            backend: { service: { name: dial-analytics-enrichment-runner, port: { number: 80 } } }
          - path: /v1/backfills        # backfill run listing + status + pause/resume/cancel
            pathType: Prefix
            backend: { service: { name: dial-analytics-enrichment-runner, port: { number: 80 } } }
          - path: /v1/dlq              # dead-letter listing + requeue
            pathType: Prefix
            backend: { service: { name: dial-analytics-enrichment-runner, port: { number: 80 } } }
```

**Group keys containing `/`.** `POST /v1/pipelines/{name}/groups/{key}/requeue` addresses such a key with the slash
percent-encoded as `%2F` (`…/groups/team%2Fa/requeue`). The runner accepts that, but everything in front of it must
forward `%2F` **unchanged**:
- A proxy that decodes it turns one segment into two, and the request misses the route (404).
- A proxy that rejects it answers 400 before the runner sees the request.

The default `kubectl port-forward` path forwards bytes and needs nothing. For the Traefik ingress, check how the
cluster's Traefik version handles encoded characters on the entrypoint. Recent releases can refuse an encoded slash
by default, and the entrypoint's encoded-characters option is then where `%2F` is allowed.
The listing and every other endpoint are unaffected.

A few keys stay unaddressable however they are encoded, because the runner's request firewall (or Tomcat, for `\`)
answers 400 before routing. Such groups are still listed and still evaluated by the readiness sweep; only the
per-group requeue cannot reach them:
- the empty key, and a key that is exactly `.` or `..`;
- a key with a `.` or `..` segment between slashes, such as `a/../b`, `./a` or `a/.`: it would read as path
  traversal once the slashes are decoded;
- a key containing `//` (two slashes in a row), `%`, `;` or `\`;
- a key containing a line feed, a carriage return, U+2028 or U+2029.

A slash at either end of a key (`/a`, `a/`, or `/` alone) is fine, and so is any other character once percent-encoded.
Send `.` literally: an encoded period (`%2E`) is refused.

Do **not** add `/actuator` here. Those paths are credential-free in every security mode — that is
deliberate, because gating a liveness probe on the identity provider turns an IdP outage into a restart
loop — so routing them publishes health, build info, and the Prometheus scrape (pipeline names, counts, timings,
spend) to every caller that can resolve the host. Probes reach them through the kubelet, which needs no
ingress.

## Egress NetworkPolicy (optional hardening)

This section applies whether or not you expose anything. The `dial-extension` chart defaults to
`networkPolicy.allowExternalEgress: true`, so all outbound traffic is allowed; the policy below is optional
hardening that locks egress down to what the pod actually needs.

Everything the pod dials out to. Note what is **absent**: the runner holds no ClickHouse connection — every
source read and enrichment write goes through ADAS — so unlike ADAS there is no ClickHouse rule here.

| Destination | Port | When active |
|---|---|---|
| PostgreSQL (`POSTGRES_HOST` — the runner's own runtime state) | 5432 TCP | always |
| ADAS (`ADAS_BASE_URL`) — pipeline sync, scan, group-rows, upsert, materialize | 80/443 TCP | always |
| DIAL Core chat/completions (`DIAL_BASE_URL`) | 443 TCP | whenever a row×LLM or group×LLM pipeline runs; a Class-1-only deployment never calls it |
| DIAL Core `/v1/user/info` (`API_KEY_CORE_URL`) | 443 TCP | `oidc` mode with `API_KEY_ENABLED=true` (incl. the optional startup probe) |
| Identity providers (JWKS / userinfo) | 443 TCP | `oidc` mode |
| OTLP collector (`OTEL_EXPORTER_OTLP_ENDPOINT`) | 4318 TCP (`http`, the default) / 4317 TCP (`grpc`) | when any OTLP export toggle is on |
| DNS | 53 UDP/TCP | always |

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: dial-analytics-enrichment-runner-egress
  namespace: dial-analytics-enrichment-runner
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: dial-analytics-enrichment-runner
      app.kubernetes.io/instance: dial-analytics-enrichment-runner
  policyTypes: [Egress]
  egress:
    - to:
        - podSelector:
            matchLabels: { app.kubernetes.io/instance: dial-analytics-enrichment-runner-postgresql }
      ports: [{ port: 5432, protocol: TCP }]
    - to: [{ namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: dial-adas } } }]   # ADAS, in its own namespace
      ports: [{ port: 80, protocol: TCP }, { port: 443, protocol: TCP }]
    - to: [{ ipBlock: { cidr: 0.0.0.0/0 } }]                                  # DIAL Core + IdP JWKS/userinfo
      ports: [{ port: 443, protocol: TCP }]
    - to: [{ namespaceSelector: {}, podSelector: { matchLabels: { app.kubernetes.io/name: opentelemetry-collector } } }]
      ports: [{ port: 4318, protocol: TCP }]   # add 4317 when OTEL_EXPORTER_OTLP_PROTOCOL=grpc
    - to: [{ namespaceSelector: {}, podSelector: { matchLabels: { k8s-app: kube-dns } } }]
      ports: [{ port: 53, protocol: UDP }, { port: 53, protocol: TCP }]
```

Tightening options: if DIAL Core runs in-cluster, replace the open 443 CIDR with a namespace/pod selector
and keep 443 open only for the identity providers; drop the OTLP rule entirely when export is off.

An egress policy that blocks ADAS is worth recognizing by its symptom: readiness never turns UP, because
the readiness gate waits on the first successful pipeline sync. A policy that blocks DIAL Core instead leaves
readiness healthy while every row and group work item retries and eventually dead-letters.

## Verification

```bash
HOST=runner.example.com

# authentication is actually on — an unauthenticated call to a routed path is 401, not 200
curl -s -o /dev/null -w '%{http_code}\n' https://$HOST/v1/pipelines/cache          # 401
curl -s -H "Authorization: Bearer $TOKEN" https://$HOST/v1/pipelines/cache         # 200 + pipeline cache JSON

# unrouted paths never reach the pod — Traefik answers with its own bare 404
curl -si https://$HOST/actuator/health      | head -1   # 404
curl -si https://$HOST/actuator/prometheus  | head -1   # 404
curl -si https://$HOST/v3/api-docs          | head -1   # 404
curl -si https://$HOST/swagger-ui.html      | head -1   # 404
curl -si https://$HOST/                     | head -1   # 404

# the same actuator paths still answer in-cluster (through a port-forward)
curl -s localhost:5100/actuator/health/readiness
```

Read the failure signatures carefully — the status code alone is not enough, because both Traefik and the
runner can produce a `404`:

- **A `200` on an unauthenticated `/v1` call** means the pod is running with `SECURITY_MODE=none`. The
  ingress is fine and the runner is open; fix the deployment, not the route.
- **Any real response body on an `/actuator` path** — `{"status":"UP"}`, Prometheus text — means the ingress
  is routing it. Those paths are `permitAll` in every security mode, so a leak shows up as a `200`, never as
  a `401`.
- **A `401`/`403` carrying the runner's `ErrorView` body** (`{status, error, message, path, method}`) on a
  path you did not list means the ingress is routing more than the allowlist: in `oidc` mode everything that
  is neither public nor under `/v1` is denied by the security chain, so the runner — not Traefik — answered.
- **A `404` carrying that same `ErrorView` body** means the request reached the runner and Spring produced
  the 404. Under a routed prefix that is normal (`/v1/pipelines/does-not-exist` for an authenticated caller);
  outside one it is a leak.
