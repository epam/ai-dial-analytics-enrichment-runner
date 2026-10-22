-- Give the dead-letter listing a total order and an index for every filter shape it pages under.
--
-- The listing pages by keyset over (created_at DESC, id DESC): a cursor names the last item returned and the next
-- page reads strictly past it. created_at alone is not a total order — one batch archives many items in the same
-- instant — so id breaks the tie, and each listing index carries it so a page is an index range scan rather than a
-- sort of every row sharing the filter.
--
--   * dlq_item_pipeline_name_created_at_id_idx replaces the (pipeline_name, created_at DESC) index: the per-pipeline
--     listing, the UI's main view.
--   * dlq_item_run_id_created_at_id_idx replaces the partial run index from V10, keeping its WHERE: a run filter
--     and the backfill lane only ever read rows that carry a run.
--   * dlq_item_created_at_id_idx serves the unfiltered and lane-only listings, and replaces the (created_at) index
--     from V2: the retention sweep's created_at range reads its leading column just as well, and every archive
--     maintains one index fewer.
--
-- Indexes only, no data change; the previous image's queries are served by the new indexes equally well.
--
DROP INDEX dlq_item_pipeline_name_created_at_idx;
CREATE INDEX dlq_item_pipeline_name_created_at_id_idx ON dlq_item (pipeline_name, created_at DESC, id DESC);

DROP INDEX dlq_item_run_id_created_at_idx;
CREATE INDEX dlq_item_run_id_created_at_id_idx ON dlq_item (run_id, created_at DESC, id DESC)
    WHERE run_id IS NOT NULL;

CREATE INDEX dlq_item_created_at_id_idx ON dlq_item (created_at DESC, id DESC);
DROP INDEX dlq_item_created_at_idx;
