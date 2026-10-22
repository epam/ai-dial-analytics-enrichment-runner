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

**The evaluator fold raises it to 2**, on both sides in the same window, and that raise is the whole of
the fold's deploy-order guard on this side. ADAS reshapes the compiled projection — the inlined
`evaluator` object removed, the top-level `vars` removed, a derived top-level `response_schema` added —
which is exactly the condition this version exists to be raised for. Because the echo is verified before
a response body is interpreted, an old runner meeting the new feed classifies it incompatible and never
reads it: the expensive failure the fold could otherwise cause — binding no variables, rendering literal
`{{placeholder}}` text, being charged for the call and storing the answer as a label — is unreachable
without any new mechanism.

**No `contract` query parameter SHALL be added to the feed request, and no `contract` member SHALL be
read from a response body.** An earlier draft of the fold specified both on ADAS's side and this
capability would have had to send and verify them; they are withdrawn as a duplicate of this header. The
header already covers the feed and all four data-plane endpoints, and it covers responses a body member
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
