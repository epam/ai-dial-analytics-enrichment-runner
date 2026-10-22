## Why

The runner originally stated a least-privilege stance: per-target grants only, no global admin role. The group lane broke it.
ADAS gates sensitive-column access on `SensitiveColumnAccess.maySeeSensitive()` — `FULL_ADMIN`, or any
caller when `security.mode` is not `oidc` — and **rejects rather than strips** at scan, `groups:fetch` and
materialize egress, re-checked against the *calling* identity. So a least-privilege runner cannot serve any
rule that references a body column: it gets a `403`, not a redacted row.

Group enrichment made this concrete rather than theoretical. A group's assembled members are the largest
and most sensitive payload the runner handles, and `add-group-worker` shipped the code that fetches them. The posture that
code implies has been recorded only in archived design notes.

This change states the deviation where an operator will find it, and converts
the body-handling rules from convention into something enforced and tested.

## What Changes

- **The `FULL_ADMIN` deviation is recorded** in `README.md`, `docs/configuration.md` and the spec, naming
  what forced it, what still bounds the runner, and the narrower-grant follow-up it defers.
- **A startup log line states the identity expectation**, so the posture is visible in the first seconds of
  a deployment's logs rather than only in a design document nobody reads at 3am.
- **A `security.mode=none` warning.** ADAS's sensitive check fails **open** when security is off, which is
  the local-development default — so local development cannot catch an identity misconfigured on a running
  deployment. The runner says so
  at startup rather than letting the difference surprise someone.
- **Body-handling discipline becomes a test, not a convention.** A test drives the group worker with
  identifiable member content and asserts that no log record at any level contains it. The rule "never log
  assembled group input or rendered prompts" is otherwise one careless `log.debug` from being false.
- **The reasoning is written down where the code is**: `GroupPayload` carries keys only, and the dead-letter
  `raw_response` is the sole place model output persists — both already true, neither previously explained
  as a privacy boundary rather than a size one.

## Capabilities

### New Capabilities

<!-- none: records a posture and enforces a discipline across existing components -->

### Modified Capabilities

- `service-runtime`: adds the runner's identity posture and body-handling constraints — what identity it
  requires, why, and what it must never persist or log.
- `runner-ops`: "Structured chunk-boundary logging" — the logging contract now states what must **not** be
  logged, at any level, and that this is verified rather than trusted.

## Impact

- **New code**: `configuration/SensitivePostureLogger` (a startup log line and the `security.mode=none`
  caveat). Nothing else — this milestone deliberately adds no runtime component.
- **Modified code**: javadoc on `worker/group/GroupPayload` and `worker/group/GroupWorkerService` explaining
  the privacy boundary; no behaviour change.
- **Docs**: `README.md` gains a posture section and loses its stale row-only framing;
  `docs/configuration.md` documents the identity requirement and the fail-open caveat.
- **Migration**: none.
- **ADAS contract**: none. This is entirely about which identity the runner presents and what it does with
  what comes back.
- **Configuration**: none. The identity is a deployment concern (the `ADAS_API_KEY`'s role), not a runner
  setting — and inventing a toggle would suggest the runner can enforce something only ADAS can.
- **Explicitly deferred**: a per-rule or service-identity sensitive grant that would let the runner drop
  back to per-target access. That needs a grant store on the ADAS side and is named here as the follow-up.
