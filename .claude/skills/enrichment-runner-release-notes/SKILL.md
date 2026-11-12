---
name: enrichment-runner-release-notes
description: Use when the user asks to enhance, refine, polish, or "look at" the release notes for an Enrichment Runner (ai-dial-analytics-enrichment-runner) tag, typically a fresh CI-generated pre-release (e.g. `0.5.0-rc.0`) or a stable cut. Reads the auto-generated notes off the GitHub release, then classifies and rewrites each bullet in this project's editorial voice. Adds the `Deployment Changes` pointer to `docs/release-<X.Y>-upgrade.md`, plus the ADAS pairing when the contract version moves. Saves a draft to `claude/release-notes/`. Never edits GitHub directly.
allowed-tools: Read Grep Glob LSP Bash(gh release view:*) Bash(gh release list:*) Bash(gh pr view:*) Bash(gh pr list:*) Bash(gh pr diff:*) Bash(gh api:*) Bash(git log:*) Bash(git show:*) Bash(git diff:*) Bash(git tag:*) Bash(git rev-parse:*) Bash(git cat-file:*) Bash(git ls-tree:*) Bash(git merge-base:*) Bash(git describe:*) Bash(date:*) Write(claude/release-notes/*) Bash(mkdir -p claude/release-notes)
argument-hint: "[tag]"
arguments: tag
model: opus
effort: xhigh
context: fork
agent: general-purpose
---

# Enrichment Runner release-notes enhancer

The CI publishes a GitHub release for every tag. The raw bullets come from `epam/ai-dial-ci`'s
`generate_release_notes` action, wired through `.github/workflows/release.yml` → `java_release.yml`. Know how it
builds them, because every rewrite rule below corrects one of its habits:

- It takes the **commit subject lines** since a baseline tag. A pre-release or an incremental build uses the
  nearest tag on the branch. A stable promotion uses merge-base mode: everything since the last stable tag. With
  **no stable tag at all**, which is the case for the first GitHub release, it takes the **full history**.
- It de-duplicates subjects, sorts them **alphabetically**, and drops `[skip ci] Update version` and
  `Merge branch …` lines.
- It routes `feat` / `fix` / `docs` / `test` / `ci` / `chore` to `Features` / `Fixes` / `Docs` / `Tests` / `CI` /
  `Other`. It strips the type prefix and renders an optional scope as a leading parenthetical:
  `ci(deps): bump …` becomes `* (deps) bump …`.
- A `!` subject (`feat!:`, `fix!:`) is written **twice**, once under `BREAKING CHANGES` and once under its type
  section, with identical text.
- Any other type (`refactor`, `perf`, `build`, `revert`) and any non-conventional subject (`initial commit`,
  `Revert "…"`, a hotfix pushed without a PR) goes to `Other` **verbatim, with the full subject**.
- Bullets carry whatever casing and phrasing the PR title had, plus the `(#<PR>)` GitHub appends to a squash
  commit. PR titles in this repo carry **no issue number**. The issue is linked in the PR body.

This skill turns that raw list into notes an operator can read. You are running in a forked, isolated context.
Read and research freely: only the final summary you return reaches the main conversation. The draft lands at
`claude/release-notes/<tag>-draft.md`.

## When to use

- "Enhance the release notes for `0.5.0-rc.0`"
- "Look at the latest pre-release notes and refine them"
- "The CI just published `<tag>`, make it readable"

Do **not** trigger on a recall question such as "what changed in 0.4?". That is not a notes-editing task.

## Inputs

`tag` = `$tag`, the GitHub release tag to enhance (e.g. `0.5.0-rc.0`, `0.5.0`). If it is empty, pick the most
recent tag from `gh release list --limit 5` and confirm it with the user before editing.

## Workflow

### 1. Resolve the target, the range and the style anchors

1. `gh release view <tag> --json body,name,tagName,isPrerelease`: capture the raw CI notes.
2. `gh release list --limit 10` and `git tag --list | sort -V`: locate the previous tag of the same kind. For a
   stable release that is the last stable tag. For a delta `rc.N+1` it is the predecessor `rc.N`.
3. **First release.** If no earlier tag exists, the range is the full history. Expect an `initial commit`
   bullet and every PR since. Say so in the editorial notes (§8).
4. **Style anchors.** If earlier releases exist, read the previous stable body and, when relevant, the previous
   rc body (`gh release view <prev> --json body`). They are the anchors, provided a human already edited them.
   If none exist yet, the rules in this skill are the house style. The house style is **terse: one line per
   bullet**, issue ref first, PR ref last, no multi-paragraph descriptions. Match that terseness, not your own
   instincts.
5. `git log <prev-tag>..<tag> --oneline`, or `git log <tag> --oneline` for a first release: the full commit list.
   It shows hotfix commits pushed without a PR, revert pairs, and the real chronology behind the alphabetical
   CI order.

### 2. Pull source context for each bullet

For every bullet in the raw notes:

1. Parse the trailing `(#<PR>)`. `gh pr view <PR> --json title,body,labels` returns the body. Read the body,
   not just the title: the *what changed* and the *upgrade notes* live there, and the title is usually too
   compressed.
2. **Find the issue ref in the PR body.** The template's `### Applicable issues` section lists it as
   `fixes #<n>`, `closes #<n>`, `resolves #<n>` or a bare `#<n>`. **Ignore the template's HTML comment**: it
   contains the placeholder `#123`, which is not an issue. Several issues → `#<a>, #<b>`. No issue → none. Never
   guess an issue from a branch name or the PR title.
3. Bullets with no `(#<PR>)` were pushed straight to `development` or a `release-*` branch. Find the commit
   with `git log <range> --oneline | grep -i <keywords>` and `git show <hash>`. These are usually hotfixes or
   reverts that fold into a related entry (§4) rather than stand alone.
4. A PR body that names a spec (`openspec/specs/<capability>/spec.md`) or a topic doc under `docs/` gives you
   the headline framing. Skim it when the PR body alone doesn't tell you what a user sees.

### 3. Cross-check the deployment-relevant sources

The `Deployment Changes` section and the `BREAKING CHANGES` entries are built from primary sources, not PR
titles:

- `git diff <prev-tag>..<tag> -- src/main/resources/application.yml docs/configuration.md README.md`: env-var
  additions, removals, renames and default changes.
- Every runner setting has **two names**: a Spring property path in `application.yml` and the environment variable
  it reads (`key: ${ENV_VAR:default}`). The operator-facing token is the **env var**. Verify it in
  `application.yml` and the bound `@ConfigurationProperties` class under
  `src/main/java/com/epam/aidial/enrichment/configuration/` (LSP `goToDefinition`, or `Grep`). Then
  confirm it against the variable's row in `docs/configuration.md`. A PR body may cite the property path or a
  pre-rename name. The code wins.
- Confirm defaults and bounds in the config class or its validator, not in the PR description.
- `git diff --stat <prev-tag>..<tag> -- src/main/resources/db/migration/`: new Flyway migrations on the runner's
  own PostgreSQL state (job queue, scan cursors, group state, DLQ, spend counters). The runner has no ClickHouse
  connection, so a migration never needs a ClickHouse grant. It can still reset or rebuild runner state. Check
  whether it does, and whether the deploy must scale the runner to 0 first.
- `git diff <prev-tag>..<tag> -- src/main/java/com/epam/aidial/enrichment/adas/AdasContract.java`: a changed
  `VERSION` is the ADAS ↔ Enrichment Runner contract bump. It requires a coordinated deploy (§6).
- `git diff <prev-tag>..<tag> -- src/main/java/com/epam/aidial/enrichment/configuration/`: an auth-mode or
  credential change (e.g. which role the API description needs in `oidc` mode), a probe group or a port change.
  These are operator actions even when no env var changed.
- `git ls-tree -r --name-only <tag> docs/ | grep 'release-.*-upgrade'`: the upgrade docs the tag actually
  ships.

### 4. Classify each bullet

The CI sorts by conventional-commit *type*, which says little about user impact. **Keep its section structure**
(`BREAKING CHANGES`, `Features`, `Fixes`, `Docs`, `Tests`, `CI`, `Other`): a reader is better served by dedicated
`Docs` / `Tests` / `CI` headings than by one overflowing `Other`. Move items by what they do:

| Where CI put it | Where it belongs | Rule |
|---|---|---|
| `BREAKING CHANGES` + `Features`/`Fixes` (a `!` commit) | **both**, worded differently | `BREAKING CHANGES` states what an operator or client must do. `Features`/`Fixes` keeps the plain change line. See §5 rule 2. |
| `Other`, a `chore:` that changes runtime behavior or a default | `Features` / `Fixes` | E.g. a default port, a security or CVE bump, a behavior correction. |
| A breaking change not flagged with `!` | also under `BREAKING CHANGES` | An env-var rename or removal, a removed or moved endpoint, a changed `ErrorView` code, a contract bump, a new required credential or role. Verify it from source (§3). |
| `Other`, a `refactor`/`perf`/`build` subject | `Fixes`, `Features` or `Other` | By user impact. A visible perf gain is a `Fixes` or `Features` line, an internal refactor stays in `Other`. |
| `Features`/`Fixes` for a regression introduced and fixed inside this pre-release line | `Fixes` with "(affects pre-release users only)" | Don't surface a transient bug as a feature. |
| Genuine docs / test / CI-workflow work | leave it in `Docs` / `Tests` / `CI` | Tidy the wording, keep the section. |
| Several PRs or hotfix commits on one change | one folded entry in the right section | Cite every PR `(#a, #b)` or commit hash. |
| A commit and its `Revert "…"` both in range | drop both | Net effect is zero. Say so in the editorial notes. |
| A later PR in the range undoes or reshapes an earlier one | one entry with the **net** effect | Don't announce a change the release doesn't ship. Cite both PRs. |

**Drop from the notes entirely.** These are the only items with zero signal for any reader:

- `Merge branch …` / `Merge remote-tracking branch …` and `[skip ci] Update version`. The CI strips these, so
  flag any that slipped through.
- `initial commit` on a first release. It is the import of the code base, not a change.
- Revert pairs with zero net effect, and exact duplicates folded into another entry.

**Keep everything else, filed under its real section.** Test work goes to `Tests`, documentation to `Docs`, and CI
workflow changes to `CI`. `Other` takes the rest: OpenSpec and Claude Code tooling, dependency and GitHub-Actions
bumps, internal renames visible in the API. When unsure whether something is worth a line, keep it, and raise the
borderline call in the editorial notes (§8) instead of dropping it silently.

### 5. Rewrite each kept bullet

The target form is:

```
* #<issue> <Active-voice description of what changed> (#<PR>)
```

Rules in order of importance:

1. **One line per bullet.** No multi-paragraph descriptions. If more detail matters, put it in the editorial-notes
   file (§8), not the draft.
2. **A breaking change gets two lines, not one line twice.** Under `BREAKING CHANGES`, say what breaks and what
   to do: "Pause and resume collapse into a single run control. Stop a pipeline with `enabled: false` in ADAS". Under
   `Features`/`Fixes`, keep the plain description of the change. Both carry the same refs.
3. **Lead with the issue ref, end with the PR ref:** `#<issue> … (#<PR>)`. Multiple PRs → `(#<a>, #<b>)`.
   Multiple issues → `#<a>, #<b> …`. No issue → start with the description and keep `(#<PR>)` at the end. Orphan
   commits → short hashes in backticks, `` (`a1b2c3d`) ``. Never strip a ref: the numbers auto-link on the
   release page.
4. **Fold the scope parenthetical into the prose.** `* (deps) bump the ai-dial-ci group …` becomes "Bump the
   `ai-dial-ci` workflow group …". A leading `(scope)` never survives.
5. **Keep it terse.** A bullet states *what changed*, not *why*. Add a short clarifying clause only when it
   materially helps a reader.
6. **Quote code identifiers in backticks**: env vars (`DISABLE_SWAGGER_AUTHORIZATION`), endpoints
   (`/v1/pipelines/paused`), headers (`Adas-Contract-Version`), roles (`FULL_ADMIN`), probe groups, ports, config
   values. Name an env var, not a Spring property path.
7. **Mark preview features** with an inline `(preview)` suffix, and regressions restoring previously working
   behavior with `(regression fix)`.
8. **Quote CVE IDs verbatim** for security upgrades, and keep the dependency and version detail.
9. **Re-order from the CI's alphabetical sort to a logical grouping.** Within each section, lead with the headline
   features. Then group related items: pipeline sync and the ADAS client, incremental scan, the row × LLM and
   group × LLM lanes, Class-1 enrichment, resilience and DLQ, spend tracking, the ops API, auth, observability.
   Trail dependency and tooling bumps.
10. **Capitalize the first word** of the description. The CI keeps the PR title's lower case.
11. **Public-repo hygiene applies to the draft.** These notes are public. Follow the "Public-repo hygiene" rules in
    `CLAUDE.md`: no environment attribution, no operational history, no real-data figures, no internal design
    ids. PR bodies sometimes carry these. Never copy them into a bullet.

#### Example transformations

Each pair is `raw CI` → `enhanced`. They are illustrative: PR and issue numbers are invented.

```
# Capitalize, take the issue from the PR body ("fixes #12"), backtick the identifiers:
- * return the partition member on every write-back row (#109)
+ * #12 Return the `__partition_v` member on every write-back row of an aligned enrichment (#109)

# A breaking change: the CI duplicates it. Reword the BREAKING CHANGES line as the action to take:
- ## BREAKING CHANGES
- * single pipeline run control (#105)
- ## Features
- * single pipeline run control (#105)
+ ## BREAKING CHANGES
+ * Operator pauses no longer survive a restart. Stop a pipeline on purpose with `enabled: false` in ADAS (#105)
+ ## Features
+ * Replace pause/resume with a single pipeline run control driven by ADAS (#105)

# An auth change found in source (§3) with no `!`: promote it:
- * require admin credential for api docs in oidc mode (#111)
+ ## BREAKING CHANGES
+ * In `oidc` mode `/v3/api-docs` and the Swagger UI need a `FULL_ADMIN` credential; set `DISABLE_SWAGGER_AUTHORIZATION=true` to keep them open (#111)

# Fold the scope parenthetical, and fold repeated dependency bumps into one line:
- ## CI
- * (deps) bump the ai-dial-ci group with 4 updates (#104)
- * (deps) bump the ai-dial-ci group with 4 updates (#106)
+ ## CI
+ * Bump the `ai-dial-ci` workflow group (#104, #106)

# Pre-PR history on a first release: drop the import, keep visible work, fold the rest:
- * initial commit
- * chore: address preflight-check gaps (dockerignore, non-root UID, naming)
- * chore: improve README
+ (dropped: `initial commit`)
+ ## Other
+ * Run the container as a non-root UID and tighten `.dockerignore` (`d41dca0`)
+ ## Docs
+ * Improve the README (`39d06d9`)
```

The env-var names in these examples are placeholders too. Always read the real names from `application.yml`.

### 6. Build the `Deployment Changes` section

`Deployment Changes` is **a pointer, not a table**. It exists only when the range carries something an operator
must act on: an env-var addition, rename or removal, a changed default, a Flyway migration that resets runner
state or needs a stopped runner, a contract bump, an auth or credential change, or a port or probe change. It links to the version line's
upgrade doc:

```markdown
## Deployment Changes

This release includes <changes that need operator action | configuration changes>. Please review the [upgrade guide](https://github.com/epam/ai-dial-analytics-enrichment-runner/blob/<tag>/docs/release-<X.Y>-upgrade.md) before proceeding.

Upgrade together with ADAS <X.Y>: the ADAS ↔ Enrichment Runner contract version rises from `<old>` to `<new>`, and neither build works with the other's previous release. See the [ADAS upgrade guide](https://github.com/epam/ai-dial-analytics/blob/<adas-tag>/docs/release-<X.Y>-upgrade.md).
```

To produce it:

1. **Find the upgrade doc.** There is one per minor line, `docs/release-<X.Y>-upgrade.md` (`0.5.0-rc.2` and
   `0.5.1` both use `release-0.5-upgrade.md`). Confirm it exists **at the tag**:
   `git cat-file -e <tag>:docs/release-<X.Y>-upgrade.md`.
2. **Never link a file the tag doesn't ship.** A `*.local.md` working draft is git-ignored and absent from the
   tag. If the range clearly has infra changes but no tracked upgrade doc exists, don't invent one and don't link
   the draft. Write the section without the link sentence, flag it as an open item in the editorial notes, and
   list what the doc must cover (§3 findings).
3. **Pick the wording from what the doc demands.** Use "changes that need operator action" when there are
   renamed or removed env vars, a coordinated deploy, a scale-to-0 step, a new credential or role, or no
   rollback. Use
   "configuration changes" when there are only new optional properties or changed defaults.
4. **Pin the link to a tag that has the file.** A stable release points at its own tag. A stable release cut
   before its upgrade doc landed points at the rc tag that carried it. Verify the path resolves.
5. **Pairing line: only when `AdasContract.VERSION` changed in the range.** Find the ADAS release that carries the
   same contract version: `gh release list -R epam/ai-dial-analytics --limit 10`. Then read
   `src/main/java/com/epam/aidial/analytics/service/enrichment/support/RunnerContract.java` at that tag (GET with
   `gh api repos/epam/ai-dial-analytics/contents/<path>?ref=<adas-tag>`, decode `.content`, read `VERSION_VALUE`).
   Pair stable with stable and rc with rc. If no ADAS release carries the new version yet, name the version line
   the upgrade doc gives, leave out the ADAS link, and flag it as open.
6. **Do not inline env-var tables, Helm values, the upgrade window's steps or migration steps** in the release body. That detail
   lives in the upgrade doc and `docs/configuration.md`.

Leave out the whole section for a range with nothing to act on. Most delta `rc`s have nothing.

### 7. Pre-release and delta handling

If the target is `<X.Y.Z>-rc.N` with `N ≥ 1`:

- The release covers only what changed since the previous rc. Do **not** consolidate or rewrite the
  predecessor's notes: each pre-release tag has its own release page, and consolidation happens at the stable cut.
- Leave out sections with no entries. A small rc is often a single section.
- Do **not** prepend a "Delta since <prev-rc>" header. The `-rc.N` suffix already says what the release is.

A stable promotion (`X.Y.0`) is generated in merge-base mode, so its raw notes already span the whole line
since the last stable. Enhance it as one release. Don't paste the rc drafts together.

### 8. Save the draft and the optional editorial companion

Create `claude/release-notes/` if it is missing (it is git-ignored), then write:

- **`claude/release-notes/<tag>-draft.md`**: the final notes, ready to paste into the GitHub release body. No
  preamble, no commentary, just headings and bullets.
- **`claude/release-notes/<tag>-editorial-notes.md`** *(optional)*: only when there are non-obvious calls to
  surface:
  - a raw → enhanced mapping for non-trivial rewrites;
  - dropped or folded items, with a one-line reason each;
  - open questions (e.g. "Keep the `harden-runner` bump under CI?");
  - every place a source of truth disagreed with a PR body: env-var name, default, a missing upgrade doc, a
    contract bump not mentioned anywhere.

### 9. Verify nothing was pushed to GitHub

This skill **never** runs `gh release edit`, `gh release create`, a `gh api` call with `-X`/`--method`/`-f`/`-F`,
or any other write against GitHub. Everything is drafted in local files under `claude/release-notes/`. Applying
the draft is a separate, explicit request.

## Output format

`claude/release-notes/<tag>-draft.md` follows this shape:

```markdown
## BREAKING CHANGES

* <what breaks and what to do> (#<PR>)

## Features

* #<issue> <one bullet per change> (#<PR>)

## Fixes

* #<issue> <one bullet per change> (#<PR>)

## Docs

* <documentation changes>

## Tests

* <test additions / migrations>

## CI

* <CI / workflow changes>

## Other

* <chores and anything that fits nowhere above: OpenSpec / Claude Code tooling, dependency bumps, API-visible renames>

## Deployment Changes

This release includes <wording>. Please review the [upgrade guide](https://github.com/epam/ai-dial-analytics-enrichment-runner/blob/<tag>/docs/release-<X.Y>-upgrade.md) before proceeding.

<pairing line, only on a contract bump>
```

Section order follows the CI: `BREAKING CHANGES` → `Features` → `Fixes` → `Docs` → `Tests` → `CI` → `Other` →
`Deployment Changes`. Emit only the sections that have entries.

## Return to the main conversation

Return a short summary of five lines or fewer:

- the draft path (`claude/release-notes/<tag>-draft.md`);
- bullet counts per section after enhancement;
- reclassifications (e.g. "moved 1 behavior-changing chore to Fixes, promoted 1 auth change to BREAKING CHANGES");
- items dropped or folded (count, one example);
- whether `Deployment Changes` was added, which upgrade-doc tag it links to, and whether a pairing line was added;
- open questions (a source/PR-body disagreement, a missing upgrade doc, an ambiguous category).

Example:

> Drafted `claude/release-notes/0.5.0-rc.0-draft.md`. 2 BREAKING CHANGES, 3 Features, 1 Fixes, 1 Docs, 3 CI, 2 Other. Promoted 1 auth change to BREAKING CHANGES. Dropped `initial commit`, folded 2 `ai-dial-ci` bumps. Deployment Changes links `blob/0.5.0-rc.0/docs/release-0.5-upgrade.md` and pairs with ADAS 0.5 (contract `4` → `5`). One open: no ADAS release carries contract `5` yet, so the ADAS link is left out and flagged in the editorial notes.

## Safety rails

- **Never edit GitHub.** No `gh release edit` or `gh release create`, and no `gh api` call other than a GET. Drafts only.
- **Never invent items.** Every kept bullet maps to a PR or a commit in the range.
- **Never silently drop or rename a PR or issue ref.** Each bullet keeps `#<issue> … (#<PR>)` so the links
  resolve.
- **Verify env-var names from `application.yml` and the config class**, not from PR bodies.
- **Don't inline upgrade-doc detail** into the release body. `Deployment Changes` is a pointer.
- **Never link an untracked or missing file.** Check it exists at the linked tag.
- **Don't consolidate pre-release notes** into the stable's notes unless the user explicitly asks.
- **Follow the public-repo hygiene rules** in every line of the draft.

## Maintenance

Conventions drift. A pattern this skill doesn't handle may turn up in the raw notes: a new commit type, a new
section from `epam/ai-dial-ci`, a rewrite the user keeps asking for, or a change in how upgrade docs are named.
If you notice one, mention it in your return summary and offer to update this `SKILL.md`. The user confirms
before any edit lands. The ADAS repo (`ai-dial-analytics`) carries a sibling skill, `analytics-release-notes`.
Keep the two in step.
