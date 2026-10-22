-- Per-group state for group-trigger rules: the dirty-mark's target and the readiness sweep's input.
--
-- "Group changed" and "group ready" are deliberately decoupled (ADR 0013 §4.2). The dirty-mark rides
-- the scan cursor and only ever writes here, so its cost is proportional to NEW ROWS rather than to
-- the number of groups; the sweep reads here on its own schedule and never re-reads the source.
--
-- group_version is max(source version column) over the group's members seen so far, as epoch millis
-- (for dial_usage_log the version column is _ingested_at). A group is dirty when group_version exceeds
-- computed_version, so a new member re-fires the group and a re-run of an unchanged group does not.
--
-- computed_truncated records that ADAS's row/byte caps trimmed the group when it was last evaluated.
-- It lives here because ADAS's write-back allow-list rejects it (and every other `_`-prefixed or
-- non-declared column), so this table is the ONLY record that a group's label is partial.
CREATE TABLE group_state (
    rule_id             TEXT    NOT NULL,
    group_key           TEXT    NOT NULL,
    group_version       BIGINT  NOT NULL,
    last_activity_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    computed_version    BIGINT,
    computed_at         TIMESTAMP WITH TIME ZONE,
    computed_truncated  BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (rule_id, group_key)
);

-- The sweep's dirty predicate: per rule, group_version > computed_version (or never computed).
-- Leading rule_id matches the per-rule query; the two version columns let the index answer the
-- comparison without touching the heap.
CREATE INDEX group_state_dirty_idx ON group_state (rule_id, group_version, computed_version);

-- Eviction (and the idle readiness window) scan by rule and last activity.
CREATE INDEX group_state_activity_idx ON group_state (rule_id, last_activity_at);
