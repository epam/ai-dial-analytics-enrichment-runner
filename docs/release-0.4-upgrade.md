# Enrichment Runner release-0.4 — upgrade notes

How to move a runner from **0.3** to **0.4**. A runner on an older build first follows
[`release-0.3-upgrade.md`](release-0.3-upgrade.md) to 0.3.

The runner moves as a pair with **ADAS 0.4**. Follow ADAS's own `docs/release-0.4-upgrade.md` for its
side. The contract version rises from `3` to `4`, and neither build works with the other's previous
release, so both are upgraded in one window. The main change is on ADAS: row-grain enrichments are
**aligned** with their source's partitions. The runner's part is small: for a per-row LLM pipeline over an
aligned enrichment, ADAS's scan returns a `__partition_v` member in every row, and the runner hands it back
on every write-back row.

The runner also closes the API description in `oidc` mode: it now needs a `FULL_ADMIN` credential (§2).

---

## 1. Upgrade procedure

### Before the window — on the old build

Set `DISABLE_SWAGGER_AUTHORIZATION=true` before the deploy if people open the Swagger UI or fetch
`/v3/api-docs` without a token in `oidc` mode (§2). Nothing else changes in your values.

### The window

1. **Scale the runner to 0** and wait for the pod to be gone. A rolling update is not enough: an old and a
   new pod would overlap against a half-updated ADAS.
2. **Deploy ADAS 0.4.**
3. **Deploy runner 0.4.** There is no migration.
4. **Scale the runner back to 1.** Readiness stays DOWN until the first pipeline sync succeeds, so a Ready
   pod confirms the pair matches.

**While the runner is at 0**, nothing is lost. ADAS's Class-1 materialize positions stop advancing, so
dependent rollups lag until a cadence after the runner is back. Keep the window short.

### After the window

1. **Verify:**
   - the pod is Ready and `runner.adas.contract.compatible` is `1`;
   - `runner.pipelines.cached` equals the number of enabled pipelines you expect;
   - no `422` write-back failures naming `__partition_v` appear in the DLQ (`GET /v1/dlq`).
2. **Expect a few failed Class-1 ticks** once ADAS's fixed 10-minute post-Ready delay has passed (§3). Do not
   alert on them.

**No runner state is migrated or discarded.** Scan cursors, Class-1 positions, group state and queued work
are untouched.

**Rollback is closed.** A 0.3 runner speaks contract `3`, and ADAS 0.4 refuses it. Fix a failing new
image forward.

---

## 2. What breaks for scripts and clients

**The API description needs a credential in `oidc` mode.** `/v3/api-docs`, `/swagger-ui.html` and
`/swagger-ui/**` now answer `401` without one, and `403` to a caller without `FULL_ADMIN`. A browser cannot
send a Bearer token or `Api-Key` when it loads the Swagger UI page and its assets, so in practice the UI is
closed; the OpenAPI JSON stays reachable by an API client sending a `FULL_ADMIN` credential.
`DISABLE_SWAGGER_AUTHORIZATION=true` restores the old behaviour for these paths only, and `/v1` stays
protected: set it where the UI is needed and the runner is not broadly reachable. In `none` mode the API
description stays open.

ADAS-side changes to pipeline registration (a group-trigger pipeline, or one reading another table, cannot
target an aligned enrichment) are in ADAS's guide.

---

## 3. Behaviour changes

### The write-back echoes `__partition_v`

For a row-trigger pipeline whose target is aligned, every scan row carries `__partition_v` (an ISO-8601
string such as `2026-10-15T10:00:00.000Z`). The runner keeps it on the queued chunk row and writes it back,
unchanged, on that row's write. A row scanned without it is written without it. This covers the live scan
and backfill alike. The group lane is unaffected: ADAS never aligns a target a group pipeline writes.

Chunks and dead letters queued by 0.3 carry no value. They still deserialize and run, and are written
without the member. That is correct for every table that existed before the upgrade, because ADAS's
automatic relayout leaves a table with a per-row LLM writer unaligned. If such a row ever reaches an aligned
target, ADAS answers `422 validation_error` and the row is dead-lettered like any other refused write.

### JSONata evaluation is bounded

Every `vars` input mapping and `outputs` transform now fails if one evaluation runs longer than 2 seconds or
exceeds a depth of 300 nested node evaluations. A failed evaluation dead-letters its row or group at `input_map` or
`output_map`, like any other mapping error. Ordinary mappings, including ones over arrays of thousands of
elements, take milliseconds and stay in single digits; a recursive function costs about three per level of
recursion, so it can go about 100 levels deep. A
pipeline that starts dead-lettering at those stages after the upgrade, with an error naming the depth or the
time, has an expression that loops or recurses without terminating; fix it in the pipeline declaration. The
bounds are not configurable.

### Credential checks are capped under a flood

At most 16 introspections of an uncached credential run at once, per credential kind. Beyond that an opaque
token is refused with `401` and an `Api-Key` with `503`, without contacting the identity provider or Core.
A credential already cached within its TTL is unaffected, so normal tooling never meets the cap. A
validly signed JWT for the wrong audience is now logged at DEBUG instead of WARN.

### ADAS's relayout holds writers for a few minutes

ADAS 0.4 waits a fixed 10 minutes after it is Ready, then relays out each existing row-grain enrichment, one
at a time. While a table's writers are held — ADAS's `PIPELINE_BUDGET_TIMEOUT` (default `120s`) plus a short
margin, so a few minutes per table — materialize and row writes to it answer `503 table_relayout_in_progress`.
The runner treats a `503` as transient and retries from its committed cursor, so this shows as a few failed
Class-1 ticks (WARN lines and `runner.class1.tick.failures` increments) per table and then clears by itself.

---

## 4. If the deploy goes out of order

| What happened | Result |
|---|---|
| Runner deployed first, old runner already at 0 | Harmless. The new pod never becomes Ready and holds its work until ADAS 0.4 is up. |
| ADAS deployed without scaling the 0.3 runner to 0 | The 0.3 runner stalls: it starts no new work, logs `ADAS is incompatible with this build's contract version`, and `runner.adas.contract.compatible` reads `0`. A call already under way may be paid for; its write is held and lands after the upgrade. Deploy the runner. |

The contract check (`Adas-Contract-Version: 4`, echoed back by ADAS) runs before a response is read, so
a mismatched pair never renders a template or calls a model on the new feed.

---

## 5. Reference

### Migrations

None.

### Configuration

Added, optional, with a default that applies the closure in §2:

| Variable                        | Default | Effect                                                                 |
|---------------------------------|---------|------------------------------------------------------------------------|
| `DISABLE_SWAGGER_AUTHORIZATION` | `false` | `true` serves the API description without a credential in `oidc` mode. |

Nothing is removed. Descriptions are in [`configuration.md`](configuration.md).

### Probes

Unchanged: liveness `/actuator/health/liveness`; readiness and startup `/actuator/health/readiness`.

For troubleshooting the contract check, a stuck write lane or a silent pause, see
[`e2e-runbook.md`](e2e-runbook.md).
