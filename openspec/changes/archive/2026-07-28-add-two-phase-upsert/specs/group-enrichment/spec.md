## MODIFIED Requirements

### Requirement: Evaluated groups are stamped clean

After a successful write the runner SHALL stamp the group's `computed_version` to the version it evaluated,
`computed_at` to the evaluation time, and `computed_truncated` to whether the input was trimmed — which is
what makes the group clean and stops the readiness sweep re-enqueuing it. Because the write happens in its own
work item, the stamp SHALL happen there too: stamping when the label was *computed* would mark a group clean
before its row landed, so a write that then failed permanently would leave a group the sweep never revisits and
an enrichment table with no row for it. "Clean" SHALL mean "written".

`group_state` SHALL remain the authority for readiness even though the row also carries provenance, because
deriving dirtiness from the enrichment table would put the sweep behind a ClickHouse read the runner does not
have.

An empty group SHALL be treated as a successful no-op: nothing is written and the group is still stamped, so a
group whose members the membership filter excludes does not re-fire on every sweep. Since there is no write to
defer to, that stamp happens in the evaluating item.

The write SHALL precede the stamp and SHALL NOT share a transaction with it. A crash between them leaves the
group dirty, so it is re-swept and re-evaluated, and the identical row collapses on the target's
`ReplacingMergeTree` — at-least-once execution, exactly-once effect.

#### Scenario: A successful evaluation makes the group clean

- **GIVEN** a dirty group that is evaluated successfully
- **WHEN** the sweep next runs
- **THEN** no further work item is enqueued for that version

#### Scenario: An empty group is stamped without a write

- **GIVEN** a group whose `groups:fetch` returns no members
- **WHEN** the work item executes
- **THEN** no enrichment row is written, no dead-letter is produced, and the group is stamped clean

#### Scenario: A crash before the stamp re-evaluates rather than losing the group

- **GIVEN** a group whose row was written but whose stamp did not persist
- **WHEN** the sweep next runs
- **THEN** the group is still dirty and is re-evaluated, and the re-written row leaves the `final=1` result
  unchanged

#### Scenario: A group whose write has not landed stays dirty

- **GIVEN** a group whose evaluation succeeded but whose write is still failing and retrying
- **WHEN** the sweep runs
- **THEN** the group is still recorded as dirty, and it is not marked clean until the write succeeds
