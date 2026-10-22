## ADDED Requirements

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

### Requirement: An incompatible ADAS stalls the runner instead of steering it

On an incompatible response the runner SHALL keep its current cache and its recurring tasks, SHALL NOT
enter reconciliation, and SHALL NOT mark the pipeline sync as having succeeded. It SHALL log the condition
at ERROR **once per transition** rather than once per poll, and SHALL publish the condition as a metric
(see the `observability` capability). Recovery SHALL be automatic: the next poll that receives a matching
echo proceeds normally, with no operator action and nothing to re-enable by hand.

Not marking the sync succeeded is the load-bearing half. The readiness group includes the pipeline-sync
contributor, and the deployment's startup probe reads it, so a **freshly started** pod against an
incompatible ADAS never becomes ready and is restarted — which, under a rolling update that keeps the
outgoing pod until the incoming one is ready, leaves the previous pod serving against the ADAS it matches.
The wrong deploy order therefore stalls the rollout instead of putting a mismatched pair into service.

Readiness SHALL NOT otherwise change: a pod that has already latched readiness and then meets an
incompatible ADAS SHALL stay ready, so its own admin surface — the DLQ, requeue and cache reads — remains
reachable through its Service during exactly the skew when an operator needs it. The condition is
reported through the metric and the log, not by withdrawing the pod.

#### Scenario: An incompatible poll keeps the cache and the tasks

- **WHEN** a sync poll receives an incompatible response
- **THEN** the cache is unchanged, no recurring instance is created, rescheduled or cancelled, and the
  sync is not marked succeeded

#### Scenario: A new pod against an incompatible ADAS never reports ready

- **GIVEN** a process that has not yet completed a successful sync
- **WHEN** every poll is incompatible
- **THEN** the readiness contributor stays DOWN, so the pod fails its startup probe rather than joining
  the service

#### Scenario: A running pod stays ready and reports through metrics

- **GIVEN** a process whose sync has already succeeded once
- **WHEN** ADAS becomes incompatible
- **THEN** readiness stays UP, the condition is logged once at ERROR and published as a metric, and no
  work is dispatched (see the `enrichment-worker` capability)

#### Scenario: Compatibility returning needs no intervention

- **GIVEN** a runner that has been refusing an incompatible ADAS
- **WHEN** a poll receives a matching echo
- **THEN** that poll reconciles normally and the condition clears

### Requirement: Admission refuses a pipeline missing any field its lane dereferences

The admission gate SHALL refuse a feed entry that carries **no inlined evaluator**, and one whose
**`grain_key` is blank**, naming each as its reason. Neither is a routable pipeline: the lane is chosen
from the evaluator's `type`, and the row and group lanes both read the grain or group column out of every
scanned row by that key.

These two arms close the gap the earlier skew fell through, in the form it will next take. An absent
evaluator makes both the Class-1 and the model-calling tests `false`, so every arm that depends on
knowing the lane — the missing response schema, the model declared on a sql evaluator — is skipped, and
the entry routes to the scan lane as though it were an ordinary row-grain pipeline. The one arm that does
still fire, on absent `outputs`, is what refuses the *authored* projection today; it does not fire on a
projection that drops the evaluator and keeps `outputs`, which is what the in-flight
`fold-evaluator-into-pipeline` change produces. A blank `grain_key` is the single field whose absence
produced a silent cursor burn rather than a refusal. The gate is the agreed place for this, and the reason stands whatever the cause of the missing
field: the feed is remote, and a remote invariant is not a safe guard for a local, irreversible loss.

#### Scenario: An entry with no evaluator is refused rather than routed

- **GIVEN** a feed entry carrying no inlined `evaluator`, with `outputs` present so no other arm refuses it
- **WHEN** the synced set is admitted
- **THEN** the pipeline is refused with a reason naming the absent evaluator, is not cached, and is
  scheduled on no lane

#### Scenario: An entry with a blank grain key is refused

- **GIVEN** a feed entry whose `grain_key` is absent or blank
- **WHEN** the synced set is admitted
- **THEN** the pipeline is refused with a reason naming the grain key, and the refusal is counted and
  logged once for that generation

### Requirement: Reconciling to empty requires a confirming poll

A single successful but **empty** feed SHALL NOT cancel the runner's recurring tasks. The runner SHALL
treat the first empty feed as unconfirmed — logging it at WARN, publishing it as a metric, and leaving the
cache and tasks in place — and SHALL reconcile to empty only when a second consecutive poll also returns
empty. A non-empty poll in between SHALL reset the count.

The first, unconfirmed empty feed SHALL still mark the pipeline sync as having **succeeded**. The poll did
succeed, and the readiness contributor reports whether this process has ever read the registry, not
whether the registry has contents — withholding readiness for a registry that is legitimately empty would
keep a freshly started pod out of service, and with a poll interval longer than the startup budget would
restart it indefinitely over a state that is not an error.

An empty feed remains an authoritative signal, not a glitch to be ignored: disabling or deleting the last
pipeline must eventually cancel its tasks, and this requirement only delays that by one poll interval.
What it removes is the one-poll path from an unexpected empty answer to "every recurring instance
cancelled, cache cleared, readiness latched, and nothing anywhere reporting that enrichment has stopped".
Zero enabled pipelines is not a steady state in this deployment, so paying one poll interval to confirm it
costs nothing that matters.

#### Scenario: The first empty feed changes nothing but the signals

- **GIVEN** a runner with enabled pipelines cached
- **WHEN** one poll returns an empty envelope
- **THEN** the cache and the recurring tasks are unchanged, and the condition is logged at WARN and
  published as a metric

#### Scenario: A first empty feed still counts as a successful sync

- **GIVEN** a process that has not yet completed a successful sync and a registry with no enabled
  pipelines
- **WHEN** its first poll returns an empty envelope
- **THEN** the pipeline sync is marked succeeded and the readiness contributor reports UP, because the
  poll succeeded — the confirmation only governs whether tasks are cancelled

#### Scenario: A second consecutive empty feed reconciles to empty

- **WHEN** the following poll also returns an empty envelope
- **THEN** the runner reconciles to empty, cancelling every recurring instance and clearing the cache

#### Scenario: A non-empty poll resets the confirmation

- **GIVEN** one empty poll has been seen
- **WHEN** the next poll returns at least one enabled pipeline
- **THEN** the empty count resets and nothing is cancelled
