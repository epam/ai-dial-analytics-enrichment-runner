## MODIFIED Requirements

### Requirement: Dirty-mark rides the scan cursor

For a group pipeline the runner SHALL derive per-group state from the same pipeline-scoped scan page the row
path uses, so the cost of detecting change is proportional to the number of **new rows** and not to the
number of groups. Each scanned row SHALL contribute its `group_by` value as the group key and the
source's version column — projected under the physical name the pipeline's `version_column` names — as both
the version and the activity timestamp. The runner SHALL take that name from the pipeline rather than from
configuration, so it may differ per source.
A group's `group_version` SHALL be the maximum version seen for it, expressed as epoch
milliseconds, and `last_activity_at` SHALL likewise only move forward, so replaying a page or
re-driving an older window SHALL NOT move a group backwards. A row missing its group key SHALL be
skipped, and a row whose version cannot be interpreted SHALL be dead-lettered at stage `input_map`
rather than silently dropped.

For a pipeline declaring `ready_when.signal`, each row SHALL also contribute **ADAS's computed verdict** of that
predicate, which the scan page carries per row. The runner SHALL record that verdict and SHALL NOT evaluate
the predicate itself: it is governed by ADAS's bounded grammar, and a second implementation would be free to
diverge and would then mark the wrong groups ready rather than failing. A group SHALL be marked signalled if
**any** of its rows was signalled, both within a page and across pages, so a member arriving after the
completion marker SHALL NOT un-signal the group. An absent verdict — every pipeline that declares no signal —
SHALL NOT signal anything.

The mark scan SHALL run on a fixed delay of the runner's configured `group.scan-cadence`
(`GROUP_SCAN_CADENCE`, default `1m`), the same for every group pipeline. It SHALL NOT read a per-pipeline
cadence: the feed carries none, because ADAS retired `advanced.scan_every`. That makes it runner
configuration rather than a declaration knob, and the reason is about direction. Readiness is measured from
the rows' own `last_activity_at`, not from when the scan ran, so a slower mark scan never makes a group
wait longer. What it does is let a group's later rows sit unmarked while the readiness sweep sees only its
older activity. The group can then look idle while it is still active, and it is evaluated **early**, and
then again once its later rows are marked and its version moves. The only safe direction for the cadence
is short, relative to every pipeline's `ready_when.idle`, and a knob whose only safe direction is short
is not a per-pipeline choice.

#### Scenario: The mark scan runs on the configured group cadence

- **GIVEN** `group.scan-cadence` is `1m` and an enabled group pipeline in the feed
- **WHEN** the runner reconciles its recurring task
- **THEN** the pipeline's `pipeline-scan` instance runs on a fixed delay of one minute, whatever its
  `ready_when` declares

#### Scenario: A group's version is the maximum seen

- **GIVEN** a page with three rows for one group key at increasing versions
- **WHEN** the page is committed
- **THEN** the group's `group_version` is the largest of the three, as epoch milliseconds

#### Scenario: Re-driving an older window does not move a group backwards

- **GIVEN** a group whose `group_version` reflects a recent member
- **WHEN** a page carrying only older members of that group is committed
- **THEN** the group's `group_version` and `last_activity_at` are unchanged

#### Scenario: A signalling row marks its group signalled

- **GIVEN** a page in which one row of a group carries a true readiness verdict
- **WHEN** the page is committed
- **THEN** that group's state records it as signalled, and a group with no signalling row does not

#### Scenario: A later member does not un-signal a group

- **GIVEN** a group already recorded as signalled
- **WHEN** a page carrying a newer, non-signalling member of that group is committed
- **THEN** the group's version advances and it remains signalled

#### Scenario: An uninterpretable version dead-letters only its row

- **GIVEN** a page in which one row's version column cannot be interpreted as a timestamp
- **WHEN** the page is committed
- **THEN** that row produces a `dlq_item` at stage `input_map`, the page's other rows still mark their
  groups, and the cursor advances

