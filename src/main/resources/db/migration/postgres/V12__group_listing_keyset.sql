-- Give the group-state listing a total order and an index it can page under in either direction.
--
-- The listing pages by keyset over (last_activity_at, group_key), oldest or newest first: a cursor names the last
-- group returned and the next page reads strictly past it. last_activity_at alone is not a total order — one scanned
-- page stamps many groups with the same instant — so group_key, unique within a pipeline, breaks the tie, and the
-- index carries it so a page is an index range scan (backwards for newest first) rather than a sort.
--
--   * group_state_pipeline_name_activity_key_idx replaces group_state_activity_idx from V3 (its rule_id column renamed
--     to pipeline_name by V8): the sweep's idle predicate and oldest-first order and the eviction range read its
--     leading columns just as well.
--
-- Indexes only, no data change; the previous image's queries are served by the new index equally well.
--
CREATE INDEX group_state_pipeline_name_activity_key_idx ON group_state (pipeline_name, last_activity_at, group_key);
DROP INDEX group_state_activity_idx;
