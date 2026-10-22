## Context

The runner originally chose least privilege: the runner holds the target enrichment's WRITE grant and nothing
more, with no global read role and no generic table-write. That choice survives contact with row rules whose
inputs are ordinary columns. It does not survive a rule that reads `request_body`.

ADAS's `SensitiveProjectionGuard.enforce` rejects a projection touching a `sensitive` column unless
`SensitiveColumnAccess.maySeeSensitive()` passes, which requires `FULL_ADMIN` — or `security.mode` not being
`oidc`, in which case it passes for everyone. The check runs at scan, at `groups:fetch` and at materialize,
against the calling identity, and it **rejects rather than strips**: a non-entitled caller gets `403`, not a
row with the body column removed. That is deliberate on ADAS's side (a partial projection would produce a
quietly wrong label) and it means there is no degraded mode for the runner to operate in.

So the runner must present `FULL_ADMIN` to run any body-reading rule. `add-group-worker` shipped the group worker that does
exactly that, and the reasoning has lived only in archived design notes.

## Goals / Non-Goals

**Goals:** record the deviation from the original stance where an operator will encounter it; state precisely
what still bounds the runner so `FULL_ADMIN` is not read as unbounded; make the body-handling rules
verifiable rather than aspirational; and name the follow-up that would undo the deviation.

**Non-Goals:** changing which identity the runner presents (a deployment concern); adding a runtime
entitlement probe; encrypting or redacting anything the runner holds in memory; and anything about how
ADAS decides whether a *derived* column inherits its source's sensitivity, which is ADAS's question.

## Decisions

- **The posture is documented and logged, not enforced in code.** The runner cannot verify its own
  entitlement without making a call, and a probe would only tell it what the next real call would tell it
  anyway — while adding a startup dependency on ADAS being reachable. A startup log line plus the docs is
  the honest scope. *Rejected:* a readiness probe that calls ADAS to test entitlement (couples startup to a
  remote service to learn something the first tick learns for free); and a config flag asserting "we are
  FULL_ADMIN" (a claim the runner cannot check, so it would only ever mislead).

- **What bounds the runner is stated as precisely as what widens it.** `FULL_ADMIN` widens *which columns a
  rule may reference*, not *which rules the runner may operate*: the scan projects only rule-referenced
  columns, `groups:fetch` returns only the rule's membership, and write-back rejects any column outside the
  rule's declared outputs plus permitted provenance. Without that sentence the posture reads as "the runner
  can read everything", which is wrong and would make the deviation harder to accept than it should be.

- **The `security.mode=none` fail-open gets its own warning.** `maySeeSensitive()` returns true for everyone
  when security is off, which is the local default — so every body-reading rule works locally regardless of
  identity, and the first time identity matters is in an environment where it is expensive to discover.
  Calling that out at startup is cheap; discovering it on a running deployment is not.

- **The logging rule is tested, not trusted.** "Never log assembled group input or rendered prompts" is a
  property of every present and future line in the group path, and the cost of breaking it is a body in a log
  aggregator. A test that drives the worker with identifiable member content and asserts no log record
  contains it turns the rule into something CI defends. *Rejected:* a code-review convention, which is what
  the runner already had and which nothing checks.

- **The framework had to be pinned, which the test discovered rather than the design predicting.** Writing the
  test at TRACE surfaced a real leak that had nothing to do with the runner's own log statements: Spring's HTTP
  client logs request bodies at DEBUG/TRACE, and the DIAL request body is the rendered prompt. So "never log
  content" was not true of the process, only of the runner's code. `org.springframework.web.client` and
  `org.springframework.http.converter` are now pinned at INFO in `log4j2-spring.xml`, independently of
  `APP_LOG_LEVEL`, so raising verbosity to diagnose something cannot start writing source content to the log
  pipeline. This is exactly the class of thing a convention would never have caught.

- **The test asserts on captured log records, not on a log file.** A Log4j2 in-memory appender attached for
  the test's duration keeps it hermetic and fast, and captures every level — including the `debug` that a
  future change is most likely to add carelessly.

- **`GroupPayload` carrying keys only gets documented as a privacy boundary.** It was introduced for size
  reasons and is already correct; what was missing is the note that it is *also* what keeps member bodies out
  of `scheduled_tasks`, so a future change that "just adds the rows to the payload for convenience" is
  visibly wrong rather than merely inefficient.

- **The dead-letter `raw_response` is named as the sole persistence point for model output**, with its 30-day
  TTL as the bound. Anyone auditing where derived content lands should find one answer, not have to infer it.

## Risks / Trade-offs

- **`FULL_ADMIN` widens the blast radius of a compromised runner key** → mitigated only partially, by the
  rule-scoped surface in both directions; a stolen key can operate the runner's rules, not read arbitrary
  tables. The real mitigation is the deferred narrower grant, and the honest statement is that this is a
  deliberate, recorded deviation rather than an oversight.

- **A documented posture drifts from the deployed one** → the startup log line is the mitigation: it states
  what the runner expects every time it boots, so a mismatch is visible in the logs of the environment that
  actually has it, not just in a document.

- **The logging test only covers the group path** → that is where the largest and most sensitive payload is,
  and where `add-group-worker` added the code. The row path's inputs are the same class of data, so the test is a
  representative guard rather than an exhaustive one; extending it to the row worker is cheap when wanted.

- **Prefer Track B where the derivation is expressible in SQL** → recorded as guidance rather than enforced.
  A Class-1 rule keeps bodies inside ClickHouse entirely, so it is both cheaper and a smaller exposure
  surface; nothing in the runner can tell that an LLM rule *could* have been a SQL one, so this stays advice
  to whoever authors the rule.
