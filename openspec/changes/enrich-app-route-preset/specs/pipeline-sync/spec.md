## MODIFIED Requirements

### Requirement: Every ADAS call declares a contract version and every response is checked for its echo

The runner SHALL send an `Adas-Contract-Version` request header, naming the contract version this build
was written against, on **every** call to ADAS — the sync feed and all four data-plane endpoints — and
SHALL verify that each ADAS response carries the same value in a header of the same name. A response whose
echo is **absent** SHALL be treated as coming from an ADAS older than the contract; a response whose echo
**differs** SHALL be treated as coming from an ADAS on the other side of a version bump. Both SHALL be
classified as *incompatible* and SHALL NOT be interpreted further.

The version SHALL be a constant of the build, compared for equality, and SHALL NOT be configurable — the
same rule and the same reason as the hard-coded `view=compiled`: the only wrong setting disables every
pipeline at once and does so silently.

**Contract 2** reshaped the compiled projection: the transform is carried on the declaration, its
variable map at `transform.inputs` rather than a top-level `vars`, and a derived top-level `response_schema`
is served. Because the echo is verified before a response body is interpreted, a runner on an earlier
contract meeting that feed classifies it incompatible and never reads it: the expensive failure a shape
mismatch could otherwise cause — binding no variables, rendering literal `{{placeholder}}` text, being
charged for the call and storing the answer as a label — is unreachable without any new mechanism.

**Retiring `on_ingest` and `advanced.scan_every` raises it to 3**, again on both sides in the same window.
Under contract 3 every row-grain entry arrives as `schedule` with a `trigger.cron`, and no entry carries
`scan_every`. The runner reads that feed with one clock per lane and no lane default to fall back to, so a
runner on 3 and an ADAS on 2 are refused by the same echo check, and nothing else is added for the bump.

**Aligning row-grain enrichments with their source's partitions raises it to 4**, on both sides in the same
window. For a row-trigger pipeline whose target is aligned, ADAS's scan returns a `__partition_v` member in
every row and its write-back requires that member back on every row (see the `enrichment-worker` capability).
A runner on 3 would drop the member and have every such write-back refused row by row after its model call
was paid for; the bump turns that into one refusal of the whole build by the same echo check, and nothing
else is added for it.

**Adding the `dial_app_route` transform preset raises it to 5**, on both sides in the same window. Under
contract 5 an entry's `transform.preset` may be `dial_app_route` and its `transform` may carry `route`. A
runner on 4 cannot decode the preset. Without the bump it would fail on those entries one at a time, or,
reading an unknown preset as the default, call a route application's chat-completions endpoint. The echo
check refuses the build as a whole instead, and nothing else is added for it.

**No `contract` query parameter SHALL be added to the feed request, and no `contract` member SHALL be
read from a response body.** Both would duplicate this header. The header already covers the feed and all four data-plane endpoints, and it covers responses a body member
cannot reach — a `401`, a `403`, an unmapped-path `404` — which is the distinction that makes an absent
echo mean "older than the contract" rather than "malformed". Two integers with one bump condition would
be two spellings of one fact.

The header SHALL be applied and verified in **one** place on the shared HTTP client rather than in each
ADAS client class, so a client that is added later cannot omit it. That same place SHALL **record** the
verdict, because it is the only component that observes every response: a skew beginning between polls is
met first by whichever call comes first — usually a scan tick or a write-back, not the feed — and a
verdict recorded only by the poll would leave the paid-work gate (see the `enrichment-worker` capability)
reading a stale *compatible* for up to a poll interval.

ADAS carries the echo on responses under its `/v1/pipelines` prefix, where all five of this runner's call
sites lie. A call site added outside that prefix SHALL be treated as a change to both halves of the
contract, since it would receive no echo and be classified as skewed.

Verification is what makes the skew detectable at all: an unknown *request* header is silently ignored by
any Spring service, so nothing the runner sends can prove the far side understood it. An unknown *response*
header is the one signal an older ADAS cannot forge, so its absence is a fact rather than an inference.

**The verdict SHALL be taken only on a response ADAS produced.** Two kinds of outcome carry no echo
without meaning skew, and neither SHALL produce a verdict or change the recorded compatibility state:

- **A transport failure.** The response status SHALL be read before any header. On a request with a body,
  the HTTP client does not read the status line itself. After a read timeout or a reset, header access
  then returns an empty set instead of failing, which looks exactly like an absent echo — so a timed-out
  write-back would otherwise hold every paid work item as contract skew. Reading the status first
  surfaces the failure as the transport failure it is. It SHALL reach callers as a `RestClientException`
  subtype (`AdasTransportException`, a `ResourceAccessException`), so the existing transient
  classification and its stage are preserved. Its message SHALL name the call, the pipeline where known,
  the elapsed time and the `adas.read-timeout-ms` it ran against — never a response body.
- **A gateway error.** A `502`, `503` or `504`, or any other `5xx` without the echo, comes from the ingress
  or a proxy in front of ADAS, because ADAS sets the echo on every response it produces under the prefix,
  error responses included. It SHALL be surfaced as a server error to the caller.

#### Scenario: A response without the echo is incompatible

- **GIVEN** an ADAS that answers 200 with a well-formed envelope but no `Adas-Contract-Version` header
- **WHEN** the sync poll runs
- **THEN** the response is classified incompatible and its body is not interpreted

#### Scenario: A response echoing a different version is incompatible

- **GIVEN** an ADAS that echoes a contract version other than this build's
- **WHEN** any ADAS call is made
- **THEN** the call fails as incompatible, naming both versions

#### Scenario: A matching pair behaves exactly as before

- **GIVEN** an ADAS echoing this build's contract version
- **WHEN** the sync poll and the data-plane calls run
- **THEN** every behaviour specified elsewhere in this capability is unchanged

#### Scenario: A read timeout on a write is a transport failure, not skew

- **GIVEN** a compatible ADAS that delays its response to a data-plane POST past `adas.read-timeout-ms`
- **WHEN** the call times out
- **THEN** it fails with `AdasTransportException` naming the call, the elapsed time and the timeout
- **AND** no contract-mismatch error is raised and the compatibility state stays compatible
- **AND** a row chunk that hits it retries as a transient failure in its own stage

#### Scenario: A gateway error leaves the verdict untouched

- **GIVEN** a compatible runner
- **WHEN** an ADAS call returns 502 with no `Adas-Contract-Version` header
- **THEN** the call fails as a server error and the compatibility state stays compatible

#### Scenario: A runner on 5 refuses an ADAS on 4

- **GIVEN** an ADAS echoing contract `4`
- **WHEN** a runner built on contract `5` polls the feed
- **THEN** the response is classified incompatible and no pipeline is admitted from it

### Requirement: Admission refuses a pipeline missing any field its lane dereferences

The admission gate SHALL refuse a feed entry that carries **no `transform`**, one whose
**`grain_key` is blank**, and one whose `transform.preset` is `dial_app_route` with a `route` that is
**absent or outside the route grammar**, naming each as its reason. Under that grammar a route is 1–256
characters of `/`-separated `[A-Za-z0-9._~-]+` segments, with no `.` or `..` segment, no leading or
trailing `/`, and no `?`, `#` or `%`. ADAS refuses the same shapes at declaration. The runner checks them
again because the route becomes part of a URL it sends with its own credentials, and a remote invariant
is not a safe guard for that. An entry whose `transform.response_jsonata` does not compile SHALL also be
refused, naming it, rather than failing on every call. Neither is a routable pipeline: the lane is chosen
from `transform.type`, and the row and group lanes both read the grain or group column out of every
scanned row by that key.

**The first arm tests `transform`** because the declaration is the transform: an entry without one
describes nothing the runner can run. Left testing nothing, an entry with no transform would reach a lane
with no type to route by.

An absent transform makes both the Class-1 and the
model-calling tests `false`, so every arm that depends on knowing the lane — the missing response schema,
the model declared on a `sql` transform — is skipped, and the entry routes to the scan lane as though it
were an ordinary row-grain pipeline. A blank `grain_key` is the single field whose absence produced a
silent cursor burn rather than a refusal. The gate is the agreed place for this, and the reason stands
whatever the cause of the missing field: the feed is remote, and a remote invariant is not a safe guard
for a local, irreversible loss.

#### Scenario: An entry with no transform is refused rather than routed

- **GIVEN** a feed entry carrying no `transform`, with `outputs` present so no other arm refuses it
- **WHEN** the synced set is admitted
- **THEN** the pipeline is refused with a reason naming the absent transform, is not cached, and is
  scheduled on no lane

#### Scenario: An entry with a blank grain key is refused

- **GIVEN** a feed entry whose `grain_key` is absent or blank
- **WHEN** the synced set is admitted
- **THEN** the pipeline is refused with a reason naming the grain key, and the refusal is counted and
  logged once for that generation

#### Scenario: A route entry with a traversing route is refused

- **GIVEN** a feed entry with `preset: dial_app_route` and `route: "../admin"`, or with no `route`
- **WHEN** the synced set is admitted
- **THEN** the pipeline is refused with a reason naming the route, is not cached, and no DIAL call is made
  for it
