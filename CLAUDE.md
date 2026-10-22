# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

The **Analytics Enrichment Runner** — a standalone service that runs enrichment for DIAL Analytics.
It incrementally scans ADAS (analytics-data-access-service) source tables via ADAS's pipeline-scoped
endpoints and writes results back to ADAS-managed ClickHouse enrichment side-tables. Each pipeline runs in
one of three lanes: **row × LLM** (one model call per row), **group × LLM** (one call per group key, fired
by a readiness sweep over runner-owned group state), and **row × SQL / Class-1** (no model call at all —
ADAS materializes the derivation set-based inside ClickHouse). The runner holds **no definitions and no
ClickHouse connection**: it syncs enabled pipelines from the ADAS control-plane and owns only its own
PostgreSQL runtime state (db-scheduler job queue, scan cursors, group state, DLQ, spend counters).
Correctness is **at-least-once execution / exactly-once effect** (duplicate writes collapse on the
enrichment table's `ReplacingMergeTree` grain key read with `final=1`).

## Source of Truth

- **Project conventions** (mandatory read): `openspec/config.yaml` `context:` — tech stack, package
  layout, API + error conventions, configuration, testing, and the ADAS integration contract.
- **Specs**: `openspec/specs/<capability>/` — the source of truth for shipped behavior.
- **In-flight changes**: `openspec/changes/<change>/` — proposals, design, tasks, and delta specs
  for work in progress; completed changes are archived.

## Essential Commands

```bash
./gradlew clean build                       # compile + checkstyle + tests (needs Docker for Testcontainers)
HTTP_PORT=5100 ./gradlew bootRun            # run locally on http://localhost:5100
./gradlew checkstyleMain checkstyleTest     # code style (Google Java Style, 180-char lines)
./gradlew test                              # run the test suite
```

If Testcontainers integration tests fail during container startup with a Ryuk error
(`RyukResourceReaper ... Timed out waiting for log output matching '.*Started.*'`), the reaper
sidecar can't launch in this Docker environment — not a code problem. Prefix the build with
`TESTCONTAINERS_RYUK_DISABLED=true` to work around it:

```bash
TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build
```

Smoke test a running instance:

```bash
curl -s localhost:5100/actuator/health/liveness   # {"status":"UP"} — process is up
```

`HTTP_PORT` defaults to **5000** (the `dial-extension` Helm chart's container port); local runs override it to
5100 because a local ADAS or DIAL Core already holds 5000.

Use the **liveness** group, not the aggregate `/actuator/health`: the aggregate includes the `db` and
`pipelineSync` contributors, so it reports `DOWN` with HTTP 503 until the first ADAS pipeline sync succeeds. Same
reason a liveness probe must never point at the aggregate — see the probe table in `docs/configuration.md`.

## Spec-Driven Development (OpenSpec)

This project uses **OpenSpec** (CLI 1.4.1). The workflow runs through `/opsx:*` commands (skills
under `.claude/skills/`, commands under `.claude/commands/opsx/`):

- `/opsx:propose "<idea>"` — create a change with proposal, design, specs, and tasks
- `/opsx:apply` — implement the current change's tasks
- `/opsx:archive` — finalize: merge delta specs into `openspec/specs/` and archive the change

CLI: `openspec list`, `openspec show <change>`, `openspec validate <change> --strict`.

Each change is archived under `openspec/changes/archive/` once its delta specs are merged.

### Spec maintenance is part of every code change

When you change behavior, contracts, or invariants, update the relevant
`openspec/specs/<capability>/` spec in the same change. Before reporting a change complete, confirm
the spec matches the shipped behavior (or state explicitly that no spec update was needed).

## Conventions quick reference

See `openspec/config.yaml` `context:` for the full set. Highlights:

- Base package `com.epam.aidial.enrichment`; layered `web/`, `service/`, `configuration/`.
- Functional endpoints under `/v1`; operational endpoints under `/actuator/*`.
- All errors use the `ErrorView` contract (`{status, error, message, path, method}`); `error` is a
  stable machine code. Add `@ExceptionHandler`s to `DefaultExceptionHandler` for new failure modes.
- Config is env-driven (`${ENV_VAR:default}`). **Property descriptions live ONLY in
  `docs/configuration.md`** (one table per config group). Keep `application.yml` to bare
  `key: ${ENV:default}` lines.
- A change is done only when `./gradlew clean build` passes.

## Public-repo hygiene

This repository is open source. Everything tracked — code, comments, Javadoc, tests, `application.yml`,
docs, `openspec/specs/` and `openspec/changes/` (archive included) — must read as the
project's own neutral engineering record, not as one company's operations log.

- **No environment attribution.** Don't write "on dev", "UAT", "prod", "in production", "the reference
  deployment", or cluster, namespace, host, bucket or tenant names. Say "a running deployment" or
  "existing deployments", or describe the condition itself. "Local development" and "production code"
  (main source as opposed to tests) are fine.
- **No operational history.** No incident or outage dates and times, deploy or drain narratives, MR or
  ticket numbers, branch or merge chatter (`origin/development`, "renumbered at merge"), people, teams,
  or "the user decided". A design decision is attributed to "the maintainers", or simply stated with
  its reasons.
- **No real-data figures.** Never copy row counts, percentages, sizes, rates or timings measured on a
  real environment. Use order-of-magnitude wording ("millions of rows", "on the order of GiB"). Spec
  scenarios and test fixtures use invented numbers and patterned ids (`00000000-0000-4000-8000-000000000001`),
  never ids or values copied from logs.
- **Entity names: real is fine, framing is not.** Pipeline and table names such as `session-insights-live`
  or `chat-topic-live` may appear as examples and fixture names. Describe them by what they do, never as
  live, deployed or ours, and never by their history. Don't make near-copy renames: they fix nothing.
- **Only reference what ships.** Don't point at untracked files (`docs/*.local.md`), deleted
  directories, or internal design ids that resolve nowhere (`D5`, `Phase-1`, `ADR 0006`, "task 7.4").
  Link the `openspec/specs/<capability>/spec.md` that owns the behaviour instead.
- **No machine-specific content.** Keep local socket overrides (`DOCKER_HOST=…/.rd/docker.sock`), home
  paths and personal tooling out of tracked files. A task line says `./gradlew clean build`.
- **No temporary scaffolding.** No "TODO: remove before prod" code or demo pages. Remove it, or make it
  a documented, opt-in feature.
- **Applied Flyway migrations are frozen.** Their comments are checksummed, so never edit one to
  reword it (`V3__group_state.sql` keeps its `ADR 0013` reference for that reason). Write new migrations to these rules from the start.
- **Verify with PCRE.** `git grep -E` on macOS ignores `\b`, so a sweep built on it silently misses
  hits. Use `git grep -nP`, for example:
  `git grep -nIP '\b(dev|UAT|prod)\b|reference deployment|in production|DevOps|MR ?!\d+|\.local\.md'`.
